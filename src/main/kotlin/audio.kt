import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.time.Duration

private val logger = KotlinLogging.logger {}

suspend fun encodeAudioFileToMp3(source: File, destination: File, offsetFromStart: Duration, length: Duration) {
    require(source.canRead()) { "Unable to read ${source.canonicalPath}" }

    val command = arrayOf(
        "ffmpeg",
        "-ss", offsetFromStart.toHHMMSS(), "-i", source.canonicalPath,
        "-c:a", "libfdk_aac",
        "-b:a", "64k",
        "-t", length.toSeconds().toString(), destination.canonicalPath
    )
    withContext(Dispatchers.IO) {
        ProcessBuilder()
            .redirectErrorStream(true)
            .command(*command)
            .directory(File("."))
            .start()!!
            .inputStream
            .bufferedReader()
            .lineSequence()
            .asFlow()
            .flowOn(Dispatchers.IO)
            .onStart {
                logger.info { "commandToFlow.onStart encoding to `${destination.canonicalPath}`" }
            }
            .onCompletion {
                logger.info { "commandToFlow.onCompletion finished `${destination.canonicalPath}`" }
            }.catch { error ->
                logger.error { error }
            }.collect()
    }

}

fun Duration.toHHMMSS(): String {
    val hh = this.toHours()
    val mm = this.toMinutesPart()
    val ss = this.toSecondsPart()
    return String.format("%02d:%02d:%02d", hh, mm, ss)
}

