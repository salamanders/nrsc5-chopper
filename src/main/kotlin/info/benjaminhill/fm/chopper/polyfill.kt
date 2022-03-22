package info.benjaminhill.fm.chopper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.schedule

private val logger = KotlinLogging.logger {}

fun timedProcess(command: Array<String>, maxTime: Duration = Duration.ofHours(1)): Pair<InputStream, InputStream> =
    ProcessBuilder()
        .command(*command)
        .directory(File("."))
        .start()!!.let { process ->
            Timer().schedule(maxTime.toMillis()) {
                if (process.isAlive) {
                    process.destroy()
                }
            }
            logger.debug { "timedProcess launched: `${command.joinToString(" ")}`" }
            process.inputStream to process.errorStream
        }


fun InputStream.toTimedLines(): Flow<Pair<Instant, String>> =
    bufferedReader().lineSequence()
        .map { Instant.now() to it }
        .asFlow()
        .onStart { logger.info { "toTimedLines.onStart" } }
        .onCompletion {
            logger.info { "toTimedLines.onCompletion, closing InputStream" }
            this@toTimedLines.close()
        }.flowOn(Dispatchers.IO)

fun InputStream.toTimedSamples(sampleSize: Int = 4): Flow<Pair<Instant, ByteArray>> =
    flow {
        val bufferedInputStream = this@toTimedSamples.buffered()
        do {
            val sample = bufferedInputStream.readNBytes(sampleSize)
            emit(Instant.now() to sample)
        } while (sample.size == sampleSize)
    }
        .onStart { logger.info { "toTimedSamples.onStart" } }
        .onCompletion {
            logger.info { "toTimedSamples.onCompletion, closing InputStream" }
            this@toTimedSamples.close()
        }
        .flowOn(Dispatchers.IO)
