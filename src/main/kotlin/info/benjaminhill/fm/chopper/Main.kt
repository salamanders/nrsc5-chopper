package info.benjaminhill.fm.chopper

import com.xenomachina.argparser.ArgParser
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
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.concurrent.schedule
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes


private val logger = KotlinLogging.logger {}

private const val WAV_HEADER_BYTES = 44L
private const val BUFFER_SECONDS = 20L

// private val audioWriter = CoroutineScope(Dispatchers.IO)

internal lateinit var parsedArgs: ChopperArgs

internal val cachedStates: ArrayDeque<InstantState<Nrsc5Message.State>> =
    ArrayDeque<InstantState<Nrsc5Message.State>>().also {
        it.add(
            InstantState(Instant.now(), Nrsc5Message.State())
        )
    }
val cachedWav: ArrayDeque<InstantState<ByteArray>> = ArrayDeque()

fun main(args: Array<String>) = runBlocking(Dispatchers.IO) {
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

    Timer().schedule((parsedArgs.maxDuration + Duration.ofSeconds(30)).toMillis()) {
        runBlocking {
            if (processIO.process.isAlive) {
                logger.warn { "Process timed out, another try at destroying." }
                processIO.process.destroy()
                delay(10_000)
                exitProcess(1)
            }
        }
    }

    // Build up state data
    launch {
        processIO.getErrorFrom.toTimedLines().toHDMessages().collect { (ts, hdMessage) ->
            val (_, currentState) = cachedStates.last()
            if (currentState.getValue(hdMessage.type) != hdMessage.value) {
                cachedStates.addLast(
                    InstantState(ts, currentState.updatedCopy(hdMessage.type, hdMessage.value))
                )
            }
        }
    }
    logger.info { "Message to state collector running" }

    // Build up audio data, hopefully doesn't overflow!
    launch(Dispatchers.IO) {
        processIO.getFrom.let { wavDataStream ->
            wavDataStream.skip(WAV_HEADER_BYTES)
            // Each sample is 4 bytes
            wavDataStream.toTimedSamples(sampleSize = 4 * 1024).collect {
                cachedWav.add(InstantState(it.first, it.second))
            }
        }
    }
    logger.info { "Sample collector running" }

    val rareLogger = LogInfrequently(
        logLine = { _ ->
            "Wav cache size:${cachedWav.size}, ${cachedWav.sumOf { it.state.size } / (1024 * 1024)}mb, " +
                    "${Duration.between(cachedWav.first().ts, cachedWav.last().ts).toMinutes()}min;  " +
                    "State cache size:${cachedStates.size}, ${
                cachedStates.groupingBy { it.state.changeType }.eachCount().entries.sortedByDescending { it.value }
            }"
        },
        delay = 2.minutes,
    )

    // Final loop keeps the app from exiting.
    while (processIO.process.isAlive) {
        rareLogger.hit()
        checkIfSongJustEnded()
        delay(15_000)
    }
    logger.info { "Process ended." }
}

private fun checkIfSongJustEnded() {
    if (cachedStates.size < 2) {
        return
    }
    // Optimization
    Duration.between(cachedStates.first().ts, cachedStates.last().ts).let { totalDur ->
        if (totalDur <= Duration.ofMinutes(2)) {
            logger.debug { "Not enough time: ${(totalDur.toSeconds() / 60.0).round(1)}m)" }
            return@checkIfSongJustEnded
        }
    }
    // We don't want a "wrapper" of the station ID bookending a song, we want actual on-the-screen time.
    val longestStretch = cachedStates.map {
        InstantState(it.ts, it.state.artist to it.state.title)
    }.toDurations(contiguous = true).maxByOrNull { it.duration }
    if (longestStretch == null) {
        logger.warn { "Longest stretch was null when looking at ${cachedStates.size} states?" }
        return
    }
    val (duration, artistTitle) = longestStretch
    val (artist, title) = artistTitle
    if (cachedStates.last().state.let { it.artist == artist && it.title == title }) {
        // still working on the longest song
        return
    }
    if (duration < Duration.ofMinutes(2)) {
        logger.info { "Not enough on-screen time with `$artist`:`$title` = ${(duration.toSeconds() / 60.0).round(1)}m" }
        return
    }
    saveSong(artist, title)
}

/**
 * Peel off a fully completed song.
 */
@Synchronized
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

    val songMeta = SongMetadata(artist = artist,
        title = title,
        count = nthTimeSong,
        frequency = parsedArgs.frequency,
        channel = parsedArgs.channel,
        bufferSeconds = BUFFER_SECONDS,
        start = start,
        end = end,
        bitrate = cachedStates.filter { (ts, state) ->
            ts in start..end && state.bitrate > 0.0
        }.map { it.state.bitrate }.average().round(1),
        startBuffered = (start - Duration.ofSeconds(BUFFER_SECONDS)).coerceAtLeast(cachedWav.minOf { it.ts }),
        endBuffered = (end + Duration.ofSeconds(BUFFER_SECONDS)).coerceAtMost(cachedWav.maxOf { it.ts }),
        // which image was on-screen the most during the song?
        topImages = cachedStates.filter {
                it.ts in start..end && it.state.changeType == Nrsc5Message.Type.FILE
            }.map {
                InstantState(it.ts, it.state.file)
            }.toDurations().filter { !it.state.contains("$$") })

    songMeta.save()
    (if (songMeta.topImages.isNotEmpty()) {
        val mostCommonFileName = songMeta.topImages.maxByOrNull { it.duration }!!.state
        val sourceImageFile = File(parsedArgs.temp, mostCommonFileName)
        if (sourceImageFile.exists()) {
            sourceImageFile
        } else {
            logger.warn { "Unable to locate image: `${sourceImageFile.canonicalPath}`" }
            null
        }
    } else {
        logger.warn { "Unable to find a good image state during the song." }
        null
    })?.copyTo(songMeta.imageFile())

    songMeta.wavFile().writeBytes(ByteArrayOutputStream().apply {
        cachedWav.forEach { (ts, ba) ->
            if (ts in songMeta.startBuffered..songMeta.endBuffered) {
                write(ba)
            }
        }
    }.toByteArray())


    // Remove extra history (both states and WAV), don't run out of memory!
    listOf(cachedStates, cachedWav).forEach { cache ->
        while (cache.isNotEmpty() && cache.firstOrNull()?.ts?.let { it < songMeta.end } == true) {
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


