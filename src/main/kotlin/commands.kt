import info.benjaminhill.utils.LogInfrequently
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds


fun commandToFlow(command: Array<String>, maxTime: Duration = Duration.ofMinutes(7)): Flow<String> {
    val timeToStop = CountDownLatch(1)
    val startInstant = Instant.now()
    val logInfrequently = LogInfrequently(delay = 10.seconds, logLine = { perSec: Double ->
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
            println("commandToFlow.onStart")
        }
        .onCompletion {
            println("commandToFlow.onCompletion")
        }
        .onEach {
            logInfrequently.hit()
            if (Duration.between(startInstant, Instant.now()) > maxTime) {
                println("commandToFlow.onEach Time is up, stopping process...")
                timeToStop.countDown()
            }
        }
        .catch { error ->
            // Most likely the process hit the time limit.
            println("commandToFlow.catch:$error")
        }
        .takeUntilSignal(timeToStop)
}

private fun <T> Flow<T>.takeUntilSignal(signal: CountDownLatch): Flow<T> = flow {
    try {
        coroutineScope {
            launch(Dispatchers.IO) {
                println("commandToFlow shutdown hook is waiting for the signal.")
                signal.await()
                println("commandToFlow shutdown hook got the signal that it is time to cancel!")
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
