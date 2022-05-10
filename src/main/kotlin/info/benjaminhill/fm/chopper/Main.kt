package info.benjaminhill.fm.chopper

import com.xenomachina.argparser.ArgParser
import info.benjaminhill.fm.chopper.Nrsc5Message.Companion.toHDMessages
import info.benjaminhill.fm.chopper.SongMetadata.Companion.nextFreeFileSlot
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
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


private val logger = KotlinLogging.logger {}

const val WAV_HEADER_BYTES = 44L
private const val BUFFER_SECONDS = 20L

// private val audioWriter = CoroutineScope(Dispatchers.IO)

internal lateinit var parsedArgs: ChopperArgs

internal val stateHistory: ArrayDeque<InstantState<Nrsc5Message.State>> =
    ArrayDeque<InstantState<Nrsc5Message.State>>().also {
        it.add(
            InstantState(
                Instant.now(), Nrsc5Message.State(
                    messageType = Nrsc5Message.Type.ARTIST
                )
            )
        )
    }
val wavHistory: ArrayDeque<InstantState<ByteArray>> = ArrayDeque()

fun main(args: Array<String>) = runBlocking(Dispatchers.IO) {
    parsedArgs = ArgParser(args).parseInto(::ChopperArgs)

    logger.info { "Launching nrsc5 and tuning in to ${parsedArgs.frequency} ${parsedArgs.channel}" }

    val processIO: ProcessIO = timedProcess(
        command = arrayOf(
            "nrsc5",
            "-l",
            "1",
            "-o",
            "-", // 'dash' means send wav data direct to std output
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

    // Build up state data.  Don't worry that there are lots of extra states that re-assert a value.
    launch(Dispatchers.IO) {
        processIO.getErrorFrom.toTimedLines().toHDMessages().collect { (ts, hdMessage) ->
            val lastState = stateHistory.lastOrNull()?.state ?: Nrsc5Message.State(
                messageType = Nrsc5Message.Type.ARTIST
            )
            stateHistory.addLast(
                InstantState(ts, lastState.updatedCopy(hdMessage.type, hdMessage.value))
            )
        }
    }
    logger.info { "Message to state collector running" }

    // Build up audio data, hopefully doesn't overflow!
    launch(Dispatchers.IO) {
        val wavDataStream = processIO.getFrom
        wavDataStream.skip(WAV_HEADER_BYTES)
        // Each sample is 4 bytes.  1k of samples is still very precise.
        wavDataStream.toTimedSamples(sampleSize = 4 * 1024).collect {
            wavHistory.add(InstantState(it.first, it.second))
        }
    }
    logger.info { "Sample collector running" }

    val rareLogger = LogInfrequently(
        logLine = {
            var result = ""
            val wavFirst = wavHistory.firstOrNull()
            val wavLast = wavHistory.lastOrNull()
            result += if (wavFirst != null && wavLast != null) {
                "Wav cache size:${wavHistory.size}, " + "approx ${
                    (wavHistory.size * arrayOf(
                        wavFirst.state.size, wavLast.state.size
                    ).average()) / (1024 * 1024)
                }mb, " + "${Duration.between(wavFirst.ts, wavLast.ts).toMinutes()}min;  "
            } else {
                "Wav cache is empty; "
            }
            result += "State cache size:${stateHistory.size}; "
            result
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

@OptIn(ExperimentalTime::class)
private fun checkIfSongJustEnded() {
    if (stateHistory.size < 2) {
        return
    }
    // Optimization
    Duration.between(stateHistory.first().ts, stateHistory.last().ts).let { totalDur ->
        if (totalDur <= Duration.ofMinutes(2)) {
            logger.debug { "Not enough time: ${(totalDur.toSeconds() / 60.0).round(1)}m)" }
            return@checkIfSongJustEnded
        }
    }
    // We don't want a "wrapper" of the station ID bookending a song, we want actual on-the-screen time.
    val longestArtistTitleStretch = stateHistory.map {
        InstantState(it.ts, it.state.artist to it.state.title)
    }.toDurations(
        contiguous = true,
        // I wonder how often other stations broadcast the title?
        maxTtl = Duration.ofSeconds(15),
    ).maxByOrNull { it.duration }
    if (longestArtistTitleStretch == null) {
        logger.warn { "Longest stretch was null when looking at ${stateHistory.size} states?" }
        return
    }
    val (duration, artistTitle) = longestArtistTitleStretch
    val (artist, title) = artistTitle
    if (stateHistory.last().state.let { it.artist == artist && it.title == title }) {
        // still working on the longest song
        return
    }
    if (duration < Duration.ofMinutes(2)) {
        logger.info { "Not enough on-screen time with `$artist`:`$title` = ${(duration.toSeconds() / 60.0).round(1)}m" }
        return
    }
    measureTime {
        saveSong(artist, title)
    }.also {
        logger.info { "Saving song took $it" }
    }
}

/**
 * Peel off a fully completed song.
 */
private fun saveSong(artist: String, title: String) {
    logger.info { "Finished a song: $artist: $title" }
    synchronized(wavHistory) {
        synchronized(stateHistory) {
            if (wavHistory.size < 5 || stateHistory.size < 5) {
                logger.warn { "Tried to save with small state sizes: wav:${wavHistory.size}, state:${stateHistory.size}" }
                return@saveSong
            }

            val nthTimeSong = nextFreeFileSlot(artist, title)
            val historyStart = stateHistory.firstOrNull { (_, state) ->
                state.artist == artist && state.title == title
            }
            // Try for the end time (when next song started).  Rely on the buffer to pick up the actual end.
            val historyEnd = stateHistory.lastOrNull { (_, state) ->
                state.artist == artist && state.title == title
            }
            if (historyStart == null || historyEnd == null) {
                logger.warn { "Unable to find start or end moment of song `$artist`:`$title`" }
                return@saveSong
            }
            val bufferedStart: Instant =
                (historyStart.ts - Duration.ofSeconds(BUFFER_SECONDS)).coerceAtLeast(wavHistory.minOf { it.ts })
            val bufferedEnd: Instant =
                (historyEnd.ts + Duration.ofSeconds(BUFFER_SECONDS)).coerceAtMost(wavHistory.maxOf { it.ts })

            val topImages: List<DurationState<String>> = stateHistory.filter {
                it.ts in historyStart.ts..historyEnd.ts && it.state.messageType == Nrsc5Message.Type.FILE
            }.map {
                InstantState(it.ts, it.state.file)
            }.toDurations(
                maxTtl = Duration.ofSeconds(45),
            ).filter { !it.state.contains("$$") }

            val songMeta = SongMetadata(
                artist = artist,
                title = title,
                album = historyEnd.state.album,
                genre = historyEnd.state.genre,
                count = nthTimeSong,
                frequency = parsedArgs.frequency,
                channel = parsedArgs.channel,
                bufferSeconds = BUFFER_SECONDS,
                start = historyStart.ts,
                end = historyEnd.ts,
                bitrate = arrayOf(historyStart.state.bitrate, historyEnd.state.bitrate).average().round(1),
                startBuffered = bufferedStart,
                endBuffered = bufferedEnd,
                topImages = topImages
            )

            songMeta.save()
            if (songMeta.topImages.isNotEmpty()) {
                val mostCommonState = songMeta.topImages.maxByOrNull { it.duration }
                if (mostCommonState != null) {
                    val sourceImageFile = File(parsedArgs.temp, mostCommonState.state)
                    if (sourceImageFile.exists()) {
                        sourceImageFile.copyTo(songMeta.imageFile())
                    } else {
                        logger.warn { "Unable to locate image: `${sourceImageFile.canonicalPath}`" }
                    }
                }
            }

            songMeta.wavFile().writeBytes(ByteArrayOutputStream().apply {
                wavHistory.forEach { (ts, ba) ->
                    if (ts in songMeta.startBuffered..songMeta.endBuffered) {
                        write(ba)
                    }
                }
            }.toByteArray())

            // Remove extra history (both states and WAV), don't run out of memory!
            listOf(stateHistory, wavHistory).forEach { cache ->
                while (cache.size > 3 && cache.firstOrNull()?.ts?.let { it < songMeta.end } == true) {
                    cache.removeFirst()
                }
                if (cache.size > 20_000) {
                    logger.warn { "After clearing cache there is still ${cache.size}" }
                }
            }
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


