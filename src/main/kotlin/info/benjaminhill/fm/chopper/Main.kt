package info.benjaminhill.fm.chopper

import info.benjaminhill.fm.chopper.Nrsc5Message.Companion.toHDMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant


private const val TEMP_IMAGE_FOLDER_NAME = "temp/aas"
private val logger = KotlinLogging.logger {}

private val maxTime = Duration.ofMinutes(5)
private const val frequency = 98.5
private const val channel = 0

private const val WAV_HEADER_BYTES = 44L

/** Current (cumulative) state of play */
data class State(
    val title: String = "",
    val artist: String = "",
    val file: String = "",
)

val ignoredFiles = setOf(
    "52275_SLKUFX\$\$010006.jpg",
    "52276_SLKUFX\$\$020006.jpg",
)

val cachedStates: ArrayDeque<Pair<Instant, State>> = ArrayDeque<Pair<Instant, State>>().also {
    it.add(Instant.now() to State())
}
val cachedWav: ArrayDeque<Pair<Instant, ByteArray>> = ArrayDeque()

fun main() = runBlocking(Dispatchers.IO) {
    val imageFolder = File(TEMP_IMAGE_FOLDER_NAME).also { it.mkdirs() }
    logger.info { "Launching nrsc5 and tuning in..." }

    val (wavDataStream, songInfoStream) = timedProcess(
        command = arrayOf(
            "nrsc5", "-l", "1", "-o", "-", // TEMP_WAV_FILE_NAME ('dash' means send wav data direct to std output)
            "--dump-aas-files", TEMP_IMAGE_FOLDER_NAME, frequency.toString(), channel.toString()
        ),
        maxTime = maxTime,
    )
    logger.debug { "nrsc5 launched" }

    launch(Dispatchers.IO) {
        songInfoStream.toTimedLines().toHDMessages().collect { (ts, hdMessage) ->
                handleHDMessage(ts, hdMessage)
            }
    }

    // Continues to build up old audio data, hopefully doesn't overflow!
    launch(Dispatchers.IO) {
        wavDataStream.skip(WAV_HEADER_BYTES)
        wavDataStream.toTimedSamples().collect(cachedWav::add)
    }
    logger.info { "All Flows running." }
}

/** Not the most clean way to do this. */
private suspend fun handleHDMessage(
    ts: Instant,
    hdMessage: Nrsc5Message,
) {
    val (_, currentState) = cachedStates.last()
    when (hdMessage.type) {
        Nrsc5Message.Type.FILE -> {
            if (currentState.file != hdMessage.value && ignoredFiles.none { hdMessage.value.contains(it) }) {
                cachedStates.addLast(
                    ts to currentState.copy(
                        file = hdMessage.value
                    )
                )
            }
        }
        // Saw a new TITLE, but not sure yet how good it is.
        Nrsc5Message.Type.TITLE -> {
            if (currentState.title != hdMessage.value) {
                logger.info { "New title `${currentState.title}` -> `${hdMessage.value}`" }
                cachedStates.addLast(
                    ts to currentState.copy(
                        title = hdMessage.value
                    )
                )
                checkIfSongJustEnded()
            }
        }
        // Saw a new ARTIST, but not sure yet how good it is.
        Nrsc5Message.Type.ARTIST -> {
            if (currentState.artist != hdMessage.value) {
                logger.info { "New artist `${currentState.artist}` -> `${hdMessage.value}`" }
                cachedStates.addLast(
                    ts to currentState.copy(
                        artist = hdMessage.value
                    )
                )
                checkIfSongJustEnded()
            }
        }
    }
}

suspend fun checkIfSongJustEnded() {
    if (cachedStates.size < 2) {
        return
    }
    Duration.between(cachedStates.first().first, cachedStates.last().first).let { totalDur ->
        if (totalDur < Duration.ofMinutes(3)) {
            logger.info { "Not enough time: ${totalDur.toSeconds()}s (count: ${cachedStates.size})" }
            return
        }
    }
    val artistSongSeconds = mutableMapOf<Pair<String, String>, Duration>()
    val artistSongImageSeconds = mutableMapOf<Triple<String, String, String>, Duration>()
    // Skips last state, which is good - that is where we might trim up to.
    cachedStates.zipWithNext { (ts0, state0), (ts1, _) ->
        (state0.artist to state0.title).let { artistTitle ->
            val count = artistSongSeconds.getOrDefault(artistTitle, Duration.ZERO)
            artistSongSeconds[artistTitle] = count + Duration.between(ts0, ts1)
        }
        Triple(state0.artist, state0.title, state0.file).let { atf ->
            val count = artistSongImageSeconds.getOrDefault(atf, Duration.ZERO)
            artistSongImageSeconds[atf] = count + Duration.between(ts0, ts1)
        }
    }
    val longestStretch = artistSongSeconds.maxByOrNull { it.value }!!
    if (longestStretch.value <= Duration.ofMinutes(3)) {
        logger.info {
            "Not enough time with ${longestStretch.key} = ${longestStretch.value.toSeconds()}s"
        }
    }
    val (artist, title) = longestStretch.key
    logger.info { "Finished a song: $artist: $title (${longestStretch.value.toSeconds()}s)" }

    // Sometimes we don't get the artist or title for a bit...
    val start = cachedStates.first { (_, state) ->
        state.artist == artist || state.title == title
    }.first
    val end = cachedStates.last { (_, state) ->
        state.artist == artist || state.title == title
    }.first
    logger.info { "Expanded time: ${Duration.between(start, end).toSeconds()}s" }

    val safeArtist = StringUtils.stripAccents(artist).replace(Regex("[^A-Za-z0-9-]+"), "_")
    val safeTitle = StringUtils.stripAccents(title).replace(Regex("[^A-Za-z0-9-]+"), "_")

    val outputFolder = File("output", safeArtist).also {
        it.mkdirs()
    }
    // Catch up to this moment (maybe only up to the end of the expanded song?)
    cachedStates.removeIf { (ts, _) ->
        ts < end
    }

    val outputStream = ByteArrayOutputStream()
    cachedWav.forEach { (ts, ba) ->
        if (ts in start..end) {
            outputStream.write(ba)
        }
    }
    cachedWav.removeIf { (ts, _) ->
        ts < end
    }
    val destination = File(outputFolder, "$safeTitle.m4a")
    // This may be badly blocking...
    audioToM4A(
        source = outputStream.toByteArray(),
        destination = destination,
    )
    // TODO: Most popular file (with the same artist+title)
}
/*
song.files.elementSet().forEach { fileName ->
val sourceImage = File(imageFolder, fileName)
if (sourceImage.canRead()) {
    sourceImage.renameTo(File(outputFolder, fileName))
} else {
    logger.warn { "Unable to move image file: `${File(imageFolder, fileName)}`" }
}
*/
