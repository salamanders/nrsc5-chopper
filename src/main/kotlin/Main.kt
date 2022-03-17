import HDMessage.Companion.dropExtras
import HDMessage.Companion.toHDMessages
import com.google.common.collect.HashMultiset
import com.google.common.collect.Multiset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.Serializable
import java.time.Duration
import java.time.Instant

private const val outputWavName = "temp/temp_audio.wav"
private val logger = KotlinLogging.logger {}

fun main() = runBlocking(Dispatchers.IO) {
    logger.info {"Tuning in..." }
    val maxTime = Duration.ofMinutes(60)
    File("temp/aas").mkdirs()
    val frequency = 98.5
    val channel = 0
    File(outputWavName).also {
        if (it.exists()) {
            it.delete()
        }
    }
    val command = arrayOf(
        "nrsc5",
        "-l", "1",
        "-o", outputWavName,
        "--dump-aas-files", "./temp/aas",
        frequency.toString(),
        channel.toString()
    )
    logger.info {"Command: `${command.joinToString(" ")}`"  }
    val firstInstant: Instant = Instant.now()

    /** Current (cumulative) state of play */
    data class Song(
        val title: String,
        val artist: String,
        val start: Instant = Instant.now(),
        val startOffset: Duration = Duration.between(firstInstant, Instant.now()),
        var length: Duration = Duration.ofSeconds(0),
        val files: Multiset<String> = HashMultiset.create()
    ) : Serializable

    val messages = commandToFlow(command = command, maxTime = maxTime)
        .dropExtras()
        .toHDMessages()
        .distinctUntilChanged()
        .onEach {
            logger.trace { "Message: $it" }
        }

    val songs = flow {
        var stateCurrentSong = Song(
            title = "",
            artist = "",
        )
        messages.collect { message ->
            stateCurrentSong.length = Duration.between(stateCurrentSong.start, Instant.now())
            when (message.type) {
                // Enhance
                HDMessage.Type.FILE -> {
                    stateCurrentSong.files.add(message.value)
                }
                // Emit last and restart
                HDMessage.Type.TITLE -> {
                    if (message.value != stateCurrentSong.title) {
                        logger.info {"-- DEBUG: New title `${message.value}`" }
                        emit(stateCurrentSong.copy())
                        stateCurrentSong = Song(
                            title = message.value,
                            artist = stateCurrentSong.artist,
                            // Ok to reset the start time.
                        )
                    }
                }
                // Emit last and restart
                HDMessage.Type.ARTIST -> {
                    if (message.value != stateCurrentSong.artist) {
                        logger.info {"-- DEBUG: New artist `${message.value}`" }
                        emit(stateCurrentSong.copy())
                        stateCurrentSong = Song(
                            title = stateCurrentSong.title,
                            artist = message.value,
                            // Ok to reset the start time.
                        )
                    }
                }
            }
        }
    }.filter { song ->
        (song.length >= Duration.ofMinutes(3)).also {
            logger.info {"-- Length check on `${song.artist}` `${song.title}` = $it" }
        }
    }

    songs
        .buffer()
        .flowOn(Dispatchers.IO)
        .collect { song ->
            logger.info {"Valid song! $song" }
            val safeArtist = StringUtils.stripAccents(song.artist).replace(Regex("[^A-Za-z0-9-]+"), "_")
            val safeTitle = StringUtils.stripAccents(song.title).replace(Regex("[^A-Za-z0-9-]+"), "_")

            launch(Dispatchers.IO) {
                val outputFolder = File("output", safeArtist).also {
                    it.mkdirs()
                }
                val destination = File(outputFolder, "$safeTitle.mp3")
                encodeAudioFileToMp3(
                    source = File(outputWavName),
                    destination = destination,
                    offsetFromStart = song.startOffset,
                    length = song.length,
                )
                logger.info {"-- Finished async encoding ${destination.absolutePath}" }
            }
        }
}





