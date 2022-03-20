import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.File
import java.io.InputStream
import java.time.Duration

private const val TEMP_WAV_FILE_NAME = "temp/temp_audio.wav"
private const val TEMP_IMAGE_FOLDER_NAME = "temp/aas"
private val logger = KotlinLogging.logger {}

private val maxTime = Duration.ofMinutes(60 * 8)
private const val frequency = 98.5
private const val channel = 0

fun main() = runBlocking(Dispatchers.IO) {
    logger.info { "Launching nrsc5 and tuning in..." }
    val command = arrayOf(
        "nrsc5",
        "-l",
        "1",
        "-o",
        "-", // TEMP_WAV_FILE_NAME
        "--dump-aas-files",
        TEMP_IMAGE_FOLDER_NAME,
        frequency.toString(),
        channel.toString()
    )
    logger.info { command.joinToString(" ") }

    val process = ProcessBuilder()
        .command(*command)
        .directory(File("."))
        .start()!!

    logger.debug { "nrsc5 launched" }

    val songInfoFlow =
        process.errorStream.linesToFlow().onStart { println("process.errorStream (song metadata) started") }


    val wavDataFlow = process.inputStream
        .let { inStream ->
            flow {
                val bis = inStream.buffered()
                val buffer = ByteArray(1024 * 16)
                println("Support mark? ${bis.markSupported()}")
                var bytesRead: Int
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    println("Read ${bytesRead / 1024}kb from bis")
                    emit(buffer.copyOfRange(0, bytesRead))
                }
            }
                .onStart { println("Starting errorStream flow") }
                .onCompletion { inStream.close() }
                .flowOn(Dispatchers.IO)
        }

    launch(Dispatchers.IO) {
        songInfoFlow.collect { line ->
            println("errorStream: $line")
        }
    }
    launch(Dispatchers.IO) {
        wavDataFlow.collect { byteArray ->
            println("Collected a byteArray size ${byteArray.size}")
        }
    }
    println("All readers have been launched.")
}
/*
fun main() = runBlocking(Dispatchers.IO) {
    logger.info { "Tuning in..." }

    val ignoredFiles = setOf(
        "52275_SLKUFX\$\$010006.jpg",
        "52276_SLKUFX\$\$020006.jpg",
    )
    val imageFolder = File(TEMP_IMAGE_FOLDER_NAME).also { it.mkdirs() }
    val tempWavFile = File(TEMP_WAV_FILE_NAME).also {
        if (it.exists()) {
            it.delete()
        }
    }

    logger.info { "Command: `${command.joinToString(" ")}`" }
    lateinit var firstInstant: Instant

    /** Current (cumulative) state of play */
    data class Song(
        val title: String,
        val artist: String,
        val start: Instant = Instant.now(),
        var length: Duration = Duration.ofSeconds(0),
        val files: Multiset<String> = HashMultiset.create()
    )

    val messages = commandToFlow(command = command, maxTime = maxTime).dropExtras().toHDMessages().onStart {
            firstInstant = Instant.now()
        }.distinctUntilChanged().onEach {
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
                Nrsc5Message.Type.FILE -> {
                    if (ignoredFiles.none { message.value.contains(it) }) {
                        stateCurrentSong.files.add(message.value)
                    }
                }
                // Emit last and restart.  This part is messy.
                Nrsc5Message.Type.TITLE -> {
                    if (message.value != stateCurrentSong.title) {
                        logger.info { "New title `${message.value}`" }
                        emit(stateCurrentSong.copy())
                        stateCurrentSong = Song(
                            title = message.value,
                            artist = stateCurrentSong.artist,
                            // Ok to reset the start time.
                        )
                    }
                }
                // Emit last and restart
                Nrsc5Message.Type.ARTIST -> {
                    if (message.value != stateCurrentSong.artist) {
                        logger.info { "New artist `${message.value}`" }
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
            logger.info { "Length check on `${song.artist}` `${song.title}` = $it" }
        }
    }

    songs.buffer().flowOn(Dispatchers.IO).collect { song ->
            logger.info { "Valid song! $song" }
            val safeArtist = StringUtils.stripAccents(song.artist).replace(Regex("[^A-Za-z0-9-]+"), "_")
            val safeTitle = StringUtils.stripAccents(song.title).replace(Regex("[^A-Za-z0-9-]+"), "_")

            launch(Dispatchers.IO) {
                val outputFolder = File("output", safeArtist).also {
                    it.mkdirs()
                }
                val destination = File(outputFolder, "$safeTitle.m4a")
                audioToM4A(
                    source = tempWavFile,
                    destination = destination,
                    offsetFromStart = Duration.between(firstInstant, song.start),
                    length = song.length,
                )
                song.files.elementSet().forEach { fileName ->
                    val sourceImage = File(imageFolder, fileName)
                    if (sourceImage.canRead()) {
                        sourceImage.renameTo(File(outputFolder, fileName))
                    } else {
                        logger.warn { "Unable to move image file: `${File(imageFolder, fileName)}`" }
                    }
                }
                logger.info { "Finished async encoding ${destination.absolutePath}" }
            }
        }
}
*/

fun InputStream.linesToFlow(): Flow<String> =
    bufferedReader().lineSequence().asFlow().onCompletion { this@linesToFlow.close() }.flowOn(Dispatchers.IO)