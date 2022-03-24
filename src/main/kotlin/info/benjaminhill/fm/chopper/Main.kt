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

internal val cachedStates: ArrayDeque<Pair<Instant, State>> = ArrayDeque<Pair<Instant, State>>().also {
    it.add(Instant.now() to State())
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
            // topRepeatedImages.inc(hdMessage.value)
            if (currentState.file != hdMessage.value) {
                cachedStates.addLast(
                    hdTs to currentState.copy(
                        file = hdMessage.value
                    )
                )
            }
        }
        // Saw a new TITLE, but not sure yet how good it is.
        Nrsc5Message.Type.TITLE -> {
            if (currentState.title != hdMessage.value) {
                logger.info { "New title `${currentState.title}` -> ${currentState.artist}:`${hdMessage.value}`" }
                cachedStates.addLast(
                    hdTs to currentState.copy(
                        title = hdMessage.value
                    )
                )
                checkIfSongJustEnded()
            }
        }
        // Saw a new ARTIST, but not sure yet how good it is.
        Nrsc5Message.Type.ARTIST -> {
            if (currentState.artist != hdMessage.value) {
                logger.info { "New artist `${currentState.artist}` -> `${hdMessage.value}`:${currentState.title}" }
                cachedStates.addLast(
                    hdTs to currentState.copy(
                        artist = hdMessage.value
                    )
                )
                checkIfSongJustEnded()
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
    val artistSongSeconds = mutableMapOf<Pair<String, String>, Duration>()

    // Skips last state, which is good - that is where we might trim up to.
    cachedStates.zipWithNext { (ts0, state0), (ts1, _) ->
        (state0.artist to state0.title).let { artistTitle ->
            val count = artistSongSeconds.getOrDefault(artistTitle, Duration.ZERO)
            artistSongSeconds[artistTitle] = count + Duration.between(ts0, ts1)
        }
    }
    // We don't want a "wrapper" of the station ID bookending a song, we want actual on-the-screen time.
    val longestStretch = artistSongSeconds.maxByOrNull { it.value }!!
    if (longestStretch.value <= Duration.ofMinutes(3)) {
        logger.info {
            "Not enough time with ${longestStretch.key} = ${longestStretch.value.toSeconds()}s"
        }
    }
    val (artist, title) = longestStretch.key
    logger.info { "Finished a song: $artist: $title (${longestStretch.value.toSeconds()}s)" }
    val songMeta = SongMetadata.create(artist = artist, title = title).apply {
        start = cachedStates.first { (_, state) ->
            state.artist == artist && state.title == title
        }.first
        end = cachedStates.last { (_, state) ->
            state.artist == artist && state.title == title
        }.first
        frequency = stationFrequency
        channel = stationChannel
        bufferSeconds = BUFFER_SECONDS
    }


    // Best to buffer a bit, and trim later.
    songMeta.startBuffered = (songMeta.start!! - Duration.ofSeconds(songMeta.bufferSeconds)).coerceAtLeast(cachedWav.minOf { it.first })
    songMeta.endBuffered = (songMeta.end!! + Duration.ofSeconds(songMeta.bufferSeconds)).coerceAtMost(cachedWav.maxOf { it.first })
    logger.info { "Expanded time: ${Duration.between(songMeta.startBuffered, songMeta.endBuffered).toSeconds()}s" }

    // which image was on-screen the most during the song?
    val artistSongImageSeconds = mutableMapOf<Triple<String, String, String>, Duration>()
    cachedStates.filter { (ts, state) ->
        ts in songMeta.startBuffered!!..songMeta.endBuffered!! && !state.file.contains("$$")
    }.zipWithNext { (ts0, state0), (ts1, _) ->
        Triple(state0.artist, state0.title, state0.file).let { atf ->
            //if (!topRepeatedImages.topN(2).contains(state0.file)) {
            val count = artistSongImageSeconds.getOrDefault(atf, Duration.ZERO)
            artistSongImageSeconds[atf] = count + Duration.between(ts0, ts1)
            //}
        }
    }
    songMeta.nextWavFile.writeBytes(ByteArrayOutputStream().apply {
        cachedWav.forEach { (ts, ba) ->
            if (ts in songMeta.startBuffered!!..songMeta.endBuffered!!) {
                write(ba)
            }
        }
    }.toByteArray())

    val mostPopularImage = artistSongImageSeconds.maxByOrNull { it.value }?.key?.third
    mostPopularImage?.let {
        File(TEMP_IMAGE_FOLDER_NAME, it).copyTo(songMeta.imageFile)
        logger.info { "Copied image `$it` into ${songMeta.imageFile.canonicalPath}" }
    } ?: logger.warn { "Unable to place image for `${songMeta.artist}` `${songMeta.title}`" }

    songMeta.save()

    // Remove extra history (both states and WAV), don't run out of memory!
    listOf(cachedStates, cachedWav).forEach { cache ->
        while (cache.isNotEmpty() && cache.firstOrNull()?.first?.let { it < songMeta.end } == true) {
            cache.removeFirst()
        }
    }

    logger.info { "After a song, there are cachedStates:${cachedStates.size}, cachedWav:${cachedWav.size}" }
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

