import State.Companion.dropExtras
import State.Companion.toState
import info.benjaminhill.utils.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.Serializable
import java.time.Duration
import java.time.Instant

private const val outputWavName = "temp/temp_audio.wav"

fun main() {
    println("Tuning in...")
    val maxTime = Duration.ofMinutes(60)
    runBlocking(Dispatchers.IO) {

        val stateCache = SimpleCache<String, ArrayList<State>>(
            cacheFile = File("temp/states.ser.gz"),
            persistEveryWrites = 1
        )

        val allStates = stateCache("98.5", getStatesFromStation(maxTime))
        println("Collected states: ${allStates.size}")
        allStates.forEach { state ->
            println("${state.ts} ${state.artist} ${state.title} ${state.file}")
        }

        val firstInstant = allStates.minOf { it.ts }
        val songs = allStates
            .filter { it.artist != null && it.title != null }
            .map { it.artist!! to it.title!! }.distinct().mapNotNull { (artist, title) ->
                val sameArtistTitle = allStates.filter { it.artist == artist && it.title == title }

                val start = sameArtistTitle.minOf { it.ts }
                val end = sameArtistTitle.maxOf { it.ts }

                if (Duration.between(start, end) > Duration.ofMinutes(3)) {
                    return@mapNotNull Song(
                        artist = artist,
                        title = title,
                        start = start,
                        end = end,
                        // TODO file
                    )
                } else {
                    null
                }
            }

        songs.forEach { song ->
            println(song)
            val safeArtist = StringUtils.stripAccents(song.artist).replace(Regex("[^A-Za-z0-9-]+"), "_")
            val safeTitle = StringUtils.stripAccents(song.title).replace(Regex("[^A-Za-z0-9-]+"), "_")

            val outputFolder = File("output", safeArtist).also {
                it.mkdirs()
            }

            encodeAudioFileToMp3(
                source = File(outputWavName),
                destination = File(outputFolder, "$safeTitle.mp3"),
                offsetFromStart = Duration.between(firstInstant, song.start),
                length = Duration.between(song.start, song.end),
            )

        }
    }
}

internal data class Song(
    val title: String,
    val artist: String,
    val start: Instant,
    val end: Instant,
) : Serializable


private fun getStatesFromStation(maxTime: Duration): suspend () -> ArrayList<State> = {
    File("temp/aas").mkdirs()
    val frequency = 98.5
    val channel = 0
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

