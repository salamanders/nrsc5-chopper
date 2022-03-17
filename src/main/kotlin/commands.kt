import info.benjaminhill.utils.LogInfrequently
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** This should be part of utils */
fun commandToFlow(command: Array<String>, maxTime: Duration = Duration.ofMinutes(7)): Flow<String> {
    val timeToStop = CountDownLatch(1)
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
        .start()!!
        .inputStream
        .bufferedReader()
        .lineSequence()
        .asFlow()
        .flowOn(Dispatchers.IO)
        .onStart {
            logger.info { "commandToFlow.onStart" }
        }
        .onCompletion {
            logger.info {"commandToFlow.onCompletion" }
        }
        .onEach {
            logInfrequently.hit()
            if (Duration.between(startInstant, Instant.now()) > maxTime) {
                logger.info {"commandToFlow.onEach Time is up, stopping process..." }
                timeToStop.countDown()
            }
        }
        .catch { error ->
            // Most likely the process hit the time limit.
            logger.warn {"commandToFlow.catch:$error"}
        }
        .takeUntilSignal(timeToStop)
}

private fun <T> Flow<T>.takeUntilSignal(signal: CountDownLatch): Flow<T> = flow {
    try {
        coroutineScope {
            launch(Dispatchers.IO) {
                logger.debug {"commandToFlow shutdown hook is waiting for the signal." }
                signal.await()
                logger.info {"commandToFlow shutdown hook got the signal that it is time to cancel!" }
                this@coroutineScope.cancel()
            }

            collect {
                emit(it)
            }
        }
    } catch (e: CancellationException) {
        // ignore
    }
}
