package info.benjaminhill.fm.chopper

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.InvalidArgumentException
import com.xenomachina.argparser.default
import info.benjaminhill.fm.chopper.Nrsc5Message.Companion.toHDMessages
import info.benjaminhill.fm.chopper.SongMetadata.Companion.getFirstUnusedCount
import info.benjaminhill.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant


private val logger = KotlinLogging.logger {}

private const val WAV_HEADER_BYTES = 44L
private const val BUFFER_SECONDS = 15L

// private val audioWriter = CoroutineScope(Dispatchers.IO)

class ChopperArgs(parser: ArgParser) {
    val verbose by parser.flagging(
        "-v", "--verbose",
        help = "enable verbose mode"
    )
    val maxDuration: Duration by parser.storing(
        "-d", "--duration",
        help = "max duration (in days, default limitless)"
    ) { Duration.ofHours(this.toLong()) }
        .default(Duration.ofDays(999999))

    val output:File by parser.storing(
        "-o", "--output",
        help = "output folder location (default: ./output"
    ) { File(this) }.default(File("./output"))
        .addValidator {
            value.mkdirs()
            if (!value.exists() || !value.canWrite())
                throw InvalidArgumentException("Unable to write to `${value.canonicalPath}`")
        }

    val temp: File by parser.storing(
        "-t", "--temp",
        help = "temp file location (default: ./temp)"
    ) { File(this) }.default(File("./temp"))
        .addValidator {
            value.mkdirs()
            if (!value.exists() || !value.canWrite())
                throw InvalidArgumentException("Unable to write to `${value.canonicalPath}`")
        }

    val frequency: Double by parser.positional(
        "FREQUENCY",
        help = "station frequency (default=98.5)",
    ) { toDouble() }.default(98.5)

    val channel:Int by parser.positional(
        "CHANNEL",
        help = "station channel 0..3 (default=0)"
    ) { toInt() }.default(0)
        .addValidator {
            if (value !in 0..3)
                throw InvalidArgumentException("channel must be in 0..3")
        }
}
internal lateinit var parsedArgs: ChopperArgs

internal val cachedStates: ArrayDeque<Pair<Instant, Nrsc5Message.State>> =
    ArrayDeque<Pair<Instant, Nrsc5Message.State>>().also {
        it.add(Instant.now() to Nrsc5Message.State())
    }
val cachedWav: ArrayDeque<Pair<Instant, ByteArray>> = ArrayDeque()



fun main(args:Array<String>) = runBlocking(Dispatchers.IO) {
    parsedArgs = ArgParser(args).parseInto(::ChopperArgs)

    logger.info { "Launching nrsc5 and tuning in..." }

    val processIO: ProcessIO = timedProcess(
        // 'dash' means send wav data direct to std output
        command = arrayOf(
            "nrsc5",
            "-l",
            "1",
            "-o",
            "-",
            "--dump-aas-files",
            parsedArgs.temp.canonicalPath,
            parsedArgs.frequency.toString(),
            parsedArgs.channel.toString()
        ),
        maxDuration = parsedArgs.maxDuration,
    )
    logger.info { "nrsc5 running" }

    // Build up state data
    launch {
        processIO.getErrorFrom.toTimedLines().toHDMessages().collect { (ts, hdMessage) ->
            val (_, currentState) = cachedStates.last()
            if (currentState.getValue(hdMessage.type) != hdMessage.value) {
                cachedStates.addLast(ts to currentState.updatedCopy(hdMessage.type, hdMessage.value))
            }
        }
    }
    logger.info { "Message to state collector running" }

    // Build up audio data, hopefully doesn't overflow!
    launch(Dispatchers.IO) {
        processIO.getFrom.let { wavDataStream ->
            wavDataStream.skip(WAV_HEADER_BYTES)
            // Each sample is 4 bytes
            wavDataStream.toTimedSamples(sampleSize = 4 * 1024).collect(cachedWav::add)
        }
    }
    logger.info { "Sample collector running" }

    // Final loop keeps the app from exiting.
    while (processIO.process.isAlive) {
        val stateChanges =
            cachedStates.groupingBy { it.second.changeType }.eachCount().entries.sortedByDescending { it.value }
        logger.info { "Wav cache size:${cachedWav.size}, ${cachedWav.sumOf { it.second.size } / (1024 * 1024)}mb" }
        logger.info { " State cache size:${cachedStates.size}, $stateChanges" }
        checkIfSongJustEnded()
        delay(30_000)
    }
    logger.info { "Process ended." }
}

private fun checkIfSongJustEnded() {
    if (cachedStates.size < 2) {
        return
    }
    // Optimization
    Duration.between(cachedStates.first().first, cachedStates.last().first).let { totalDur ->
        if (totalDur < Duration.ofMinutes(3)) {
            logger.debug { "Not enough time: ${totalDur.toSeconds()}s (count: ${cachedStates.size})" }
            return@checkIfSongJustEnded
        }
    }
    // We don't want a "wrapper" of the station ID bookending a song, we want actual on-the-screen time.
    val artistTitleDuration: Map<Pair<String, String>, Duration> = sumDurations(cachedStates.map { (ts, state) ->
        ts to (state.artist to state.title)
    })
    val longestStretch = artistTitleDuration.maxByOrNull { it.value }
    if (longestStretch == null) {
        logger.warn { "Longest stretch was null when looking at ${cachedStates.size} states?" }
        return
    }
    val (artistTitle, duration) = longestStretch
    val (artist, title) = artistTitle
    if (cachedStates.last().second.let { it.artist == artist && it.title == title }) {
        // still working on the longest song
        return
    }

    if (duration <= Duration.ofMinutes(3)) {
        logger.info { "Not enough on-screen time with $$artist:$title = ${duration.toSeconds()}s" }
        return
    }
    // TBD if this needs to be synchronized with the appending
    synchronized(cachedStates) {
        saveSong(artist, title)
    }
}

private fun saveSong(artist: String, title: String) {
    logger.info { "Finished a song: $artist: $title" }
    val nthTimeSong = getFirstUnusedCount(artist, title)
    // Try for the end time (when next song started)
    val indexOfEnd = (cachedStates.indexOfLast { (_, state) ->
        state.artist == artist && state.title == title
    } + 1).coerceAtMost(cachedStates.size - 1)
    // Can't be greedy on artist, what if two songs by same artist in a row?
    val (start, _) = cachedStates.first { (_, state) ->
        state.title == title
    }
    val (end, _) = cachedStates[indexOfEnd]
    val songMeta = SongMetadata(
        artist = artist,
        title = title,
        count = nthTimeSong,
        frequency = parsedArgs.frequency,
        channel = parsedArgs.channel,
        bufferSeconds = BUFFER_SECONDS,
        start = start,
        end = end,
        bitrate = cachedStates.filter { (ts, state) ->
            ts in start..end && state.bitrate > 0.0
        }.map { it.second.bitrate }.average().round(1),
        startBuffered = (start - Duration.ofSeconds(BUFFER_SECONDS)).coerceAtLeast(cachedWav.minOf { it.first }),
        endBuffered = (end + Duration.ofSeconds(BUFFER_SECONDS)).coerceAtMost(cachedWav.maxOf { it.first }),
    )

    songMeta.save()

    songMeta.wavFile().writeBytes(ByteArrayOutputStream().apply {
        cachedWav.forEach { (ts, ba) ->
            if (ts in songMeta.startBuffered..songMeta.endBuffered) {
                write(ba)
            }
        }
    }.toByteArray())

    // which image was on-screen the most during the song?
    val goodImageState: Nrsc5Message.State? = cachedStates.firstOrNull { (instant, state) ->
        instant in (songMeta.start + Duration.ofSeconds(15))..songMeta.end && !state.file.contains("$$")
    }?.second
    if (goodImageState != null) {
        val sourceImageFile = File(parsedArgs.temp, goodImageState.file)
        if (sourceImageFile.exists()) {
            sourceImageFile.copyTo(songMeta.imageFile())
        } else {
            logger.warn { "Unable to locate image: `${sourceImageFile.canonicalPath}`" }
        }
    } else {
        logger.warn { "Unable to find a good image state during the song." }
    }

    // Remove extra history (both states and WAV), don't run out of memory!
    listOf(cachedStates, cachedWav).forEach { cache ->
        while (cache.isNotEmpty() && cache.firstOrNull()?.first?.let { it < songMeta.end } == true) {
            cache.removeFirst()
        }
        if (cache.size > 1_000_000) {
            logger.warn { "After clearing cache there is still ${cache.size}" }
        }
    }
}
/*
val destination = File(outputFolder, "$safeTitle.m4a")
// This may be badly blocking...
audioWriter.launch {
    audioToM4A(
        source = outputStream.toByteArray(),
        destination = destination,
    )
}
*/


