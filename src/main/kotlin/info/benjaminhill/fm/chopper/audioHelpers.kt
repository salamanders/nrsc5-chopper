package info.benjaminhill.fm.chopper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Requires ffmpeg with libfdk_aac, see resources/build_ffmpeg.sh
 */
internal suspend fun audioToM4A(source: ByteArray, destination: File) {

    val command = arrayOf(
        "ffmpeg",
        // defined the format https://trac.ffmpeg.org/wiki/audio%20types
        // pcm_s16le ([1][0][0][0] / 0x0001), 44100 Hz, stereo, s16, 1411 kb/s
        "-f", "s16le",
        "-ar", "44100",
        "-ac", "2",
        "-i", "pipe:",
        "-c:a", "libfdk_aac",
        "-b:a", "128k",
        destination.canonicalPath
    )
    logger.info { "audioToM4A `${command.joinToString(" ")}`" }
    withContext(Dispatchers.IO) {
        val process = ProcessBuilder()
            .redirectErrorStream(true)
            .command(*command)
            .directory(File("."))
            .start()!!

        // queue up the entire file
        process.outputStream.buffered(source.size).write(source)

        launch {
            process
                .inputStream
                .bufferedReader()
                .lineSequence()
                .asFlow()
                .flowOn(Dispatchers.IO)
                .onStart {
                    logger.info { "audioToM4A.onStart encoding to `${destination.canonicalPath}`" }
                }
                .onCompletion {
                    logger.info { "audioToM4A.onCompletion finished `${destination.canonicalPath}`" }
                }.catch { error ->
                    logger.error { error }
                }.collect {
                    // TODO: Debug
                    logger.info { "audioToM4A: $it" }
                }
        }

        process.outputStream.apply {
            flush()
            close()
            logger.info { "audioToM4A closed input." }
        }
    }
}
