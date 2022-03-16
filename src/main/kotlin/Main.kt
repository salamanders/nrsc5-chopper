import State.Companion.dropExtras
import State.Companion.toState
import info.benjaminhill.utils.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration

fun main() {
    println("Tuning in...")
    val maxTime = Duration.ofMinutes(15)
    runBlocking(Dispatchers.IO) {

        val stateCache = SimpleCache<String, ArrayList<State>>(
            cacheFile = File("temp/states.ser.gz"),
            persistEveryWrites = 1
        )

        val allStates = stateCache("98.5") {
            File("temp/aas").mkdirs()
            val frequency = 98.5
            val channel = 0
            val outputWavName = "temp/temp_audio.wav"
            File(outputWavName).let {
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
            println("Command: `${command.joinToString(" ")}`")
            val states = commandToFlow(command = command, maxTime = maxTime)
                .dropExtras()
                .toState()
                .toList()

            ArrayList(states)
        }

        println("Collected states: ${allStates.size}")
        allStates.forEach { state ->
            println("${state.ts} ${state.artist} ${state.title} ${state.file}")
        }
    }
}

