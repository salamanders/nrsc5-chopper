package info.benjaminhill.fm.chopper

import info.benjaminhill.fm.chopper.Nrsc5Message.Companion.toHDMessages
import info.benjaminhill.utils.timedProcess
import info.benjaminhill.utils.toTimedLines
import info.benjaminhill.utils.toTimedSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant


private val logger = KotlinLogging.logger {}

private const val TEMP_IMAGE_FOLDER_NAME = "temp/aas"
private val maxTime = Duration.ofHours(10)
private const val stationFrequency = 98.5
private const val stationChannel = 0
private const val WAV_HEADER_BYTES = 44L
private const val BUFFER_SECONDS = 15L

// private val audioWriter = CoroutineScope(Dispatchers.IO)

internal val cachedStates: ArrayDeque<Pair<Instant, Nrsc5Message.State>> =
    ArrayDeque<Pair<Instant, Nrsc5Message.State>>().also {
        it.add(Instant.now() to Nrsc5Message.State())
    }
val cachedWav: ArrayDeque<Pair<Instant, ByteArray>> = ArrayDeque()
//val topRepeatedImages = CounterFIFO()

fun main() = runBlocking(Dispatchers.IO) {
    File(TEMP_IMAGE_FOLDER_NAME).also { it.mkdirs() }
    logger.info { "Launching nrsc5 and tuning in..." }

    val (wavDataStream, songInfoStream) = timedProcess(
        // TEMP_WAV_FILE_NAME ('dash' means send wav data direct to std output)
        command = arrayOf(
            "nrsc5",
            "-l",
            "1",
            "-o",
            "-",
            "--dump-aas-files",
            TEMP_IMAGE_FOLDER_NAME,
            stationFrequency.toString(),
            stationChannel.toString()
        ),
        maxDuration = maxTime,
    )
    logger.debug { "nrsc5 running" }

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
private fun handleHDMessage(
    hdTs: Instant,
    hdMessage: Nrsc5Message,
) {
    val (_, currentState) = cachedStates.last()
    when (hdMessage.type) {
        // New FILEs never mean a new song.  But we still log it for album art.
        Nrsc5Message.Type.FILE -> {
            if (currentState.file != hdMessage.value) {
                cachedStates.addLast(
                    hdTs to currentState.copy(file = hdMessage.value)
                )
            }
        }
        // Saw a new TITLE, but not sure yet how good it is.
        Nrsc5Message.Type.TITLE -> {
            if (currentState.title != hdMessage.value) {
                logger.info { "New title `old:${currentState.title}` -> `${currentState.artist}`:`${hdMessage.value}`" }
                cachedStates.addLast(
                    hdTs to currentState.copy(title = hdMessage.value)
                )
                checkIfSongJustEnded()
            }
        }
        // Saw a new ARTIST, but not sure yet how good it is.
        Nrsc5Message.Type.ARTIST -> {
            if (currentState.artist != hdMessage.value) {
                logger.info { "New artist `old:${currentState.artist}` -> `${hdMessage.value}`:`${currentState.title}`" }
                cachedStates.addLast(
                    hdTs to currentState.copy(artist = hdMessage.value)
                )
                checkIfSongJustEnded()
            }
        }
        Nrsc5Message.Type.BITRATE -> {
            hdMessage.value.toDouble().let { bitrate ->
                if (currentState.bitrate != bitrate) {
                    cachedStates.addLast(hdTs to currentState.copy(bitrate = bitrate))
                }
            }

        }
    }
}

internal fun checkIfSongJustEnded() {
    if (cachedStates.size < 2) {
        return
    }
    Duration.between(cachedStates.first().first, cachedStates.last().first).let { totalDur ->
        if (totalDur < Duration.ofMinutes(3)) {
            logger.debug { "Not enough time: ${totalDur.toSeconds()}s (count: ${cachedStates.size})" }
            return
        }
    }
    // We don't want a "wrapper" of the station ID bookending a song, we want actual on-the-screen time.
    val artistTitleDuration: Map<Pair<String, String>, Duration> = sumDurations(cachedStates.map { (ts, state) ->
        ts to (state.artist to state.title)
    })
    val longestStretch = artistTitleDuration.maxByOrNull { it.value }!!
    if (longestStretch.value <= Duration.ofMinutes(3)) {
        logger.info {
            "Not enough on-screen time with ${longestStretch.key} = ${longestStretch.value.toSeconds()}s"
        }
    }
    val (artist, title) = longestStretch.key
    logger.info { "Finished a song: $artist: $title (${longestStretch.value.toSeconds()}s)" }
    val songMeta = SongMetadata.create(artist = artist, title = title).apply {
        frequency = stationFrequency
        channel = stationChannel
        bufferSeconds = BUFFER_SECONDS

        start = cachedStates.first { (_, state) ->
            state.artist == artist && state.title == title
        }.first

        // Try to end the song when the next one started (if there is room)
        val indexOfEnd = (cachedStates.indexOfLast { (_, state) ->
            state.artist == artist && state.title == title
        } + 1).coerceAtMost(cachedStates.size - 1)
        end = cachedStates[indexOfEnd].first

        bitrate = cachedStates.filter {
            it.first in start!!..end!! && it.second.bitrate > 1.0
        }.map { it.second.bitrate }.average()

        // Best to buffer a bit, and trim later.
        startBuffered = (start!! - Duration.ofSeconds(bufferSeconds)).coerceAtLeast(cachedWav.minOf { it.first })
        endBuffered = (end!! + Duration.ofSeconds(bufferSeconds)).coerceAtMost(cachedWav.maxOf { it.first })
        logger.info { "Expanded time: ${Duration.between(startBuffered, endBuffered).toSeconds()}s" }

        save()
    }

    songMeta.wavFile().writeBytes(ByteArrayOutputStream().apply {
        cachedWav.forEach { (ts, ba) ->
            if (ts in songMeta.startBuffered!!..songMeta.endBuffered!!) {
                write(ba)
            }
        }
    }.toByteArray())

    // which image was on-screen the most during the song?
    val goodImageState: Nrsc5Message.State? = cachedStates.firstOrNull { (instant, state) ->
        instant in (songMeta.start!! + Duration.ofSeconds(30))..songMeta.end!! && !state.file.contains("$$")
    }?.second
    if (goodImageState != null) {
        val sourceImageFile = File(TEMP_IMAGE_FOLDER_NAME, goodImageState.file)
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
}


