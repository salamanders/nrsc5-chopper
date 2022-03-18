import info.benjaminhill.utils.LogInfrequently
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.schedule
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** This should be part of utils */
fun commandToFlow(command: Array<String>, maxTime: Duration = Duration.ofMinutes(7)): Flow<String> {
    val startInstant = Instant.now()
    val logInfrequently = LogInfrequently(delay = 90.seconds, logLine = { perSec: Double ->
        Duration.between(startInstant, Instant.now()).let {
            "Runtime: ${it.toMinutes()}m, Running at ${perSec.toInt()}/sec"
        }
    }
    )
    return ProcessBuilder()
        .redirectErrorStream(true)
        .command(*command)
        .directory(File("."))
        .start()!!.also { process ->
            Timer().schedule(maxTime.toMillis()) {
                if (process.isAlive) {
                    process.destroy()
                }
            }
        }
        .inputStream
        .bufferedReader()
        .lineSequence()
        .asFlow()
        .flowOn(Dispatchers.IO)
        .onStart {
            logger.info { "commandToFlow.onStart" }
        }
        .onCompletion {
            logger.info { "commandToFlow.onCompletion" }
        }
        .onEach {
            logInfrequently.hit()
        }
        .catch { error ->
            // Most likely the process hit the time limit.
            logger.warn { "commandToFlow.catch:$error" }
        }
}