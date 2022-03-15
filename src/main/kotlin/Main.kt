import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Collections.min


fun main() {
    println("Tuning in...!")
    lateinit var currentState: State
    runBlocking(Dispatchers.IO) {
        File("example.txt").readLines().asFlow()
            /*
            runCommand(
                command = arrayOf("nrsc5", "-l 1", "-o output_985.wav", "--dump-aas-files ./aas/", "98.5", "0"),
                maxDuration = 20.minutes,
            )*/
            .flowOn(Dispatchers.IO)
            .onStart {
                currentState = State()

            }
            .onCompletion { currentState.splitFiles() }
            .collect(currentState::handleLine)
    }
}


class State {
    private val recordingStartTime: Instant = Instant.now()
    private val songHistory: MutableList<SongEntry> = mutableListOf()

    // Arbitrary time in the past
    private var titleUpdated: Instant = Instant.now().minus(Duration.ofMinutes(5))
    private var artistUpdated: Instant = Instant.now().minus(Duration.ofMinutes(5))
    private var bitRate: Double = 0.0

    private var title: String = ""
        set(value) {
            if (field != value && value.trim().isNotBlank()) {
                println("New Title: '$value'")
                titleUpdated = Instant.now()
                if (Duration.between(artistUpdated, Instant.now()) < DURATION_BETWEEN_UPDATES) {
                    newSongStarted()
                } else {
                    println("  but the artist hasn't been updated, so continuing normally.")
                }
            }
            field = value
        }

    private var artist: String = ""
        set(value) {
            if (field != value && value.trim().isNotBlank()) {
                println("New Artist: '$value'")
                artistUpdated = Instant.now()
                if (Duration.between(titleUpdated, Instant.now()) < DURATION_BETWEEN_UPDATES) {
                    newSongStarted()
                } else {
                    println("  but the title hasn't been updated, so continuing normally.")
                }
            }
            field = value
        }
    private var fileLot: String = ""

    private class SongEntry(
        val title: String,
        val artist: String,
        val start: Instant
    ) {
        lateinit var end: Instant

        fun isValid() = title.isNotBlank() && artist.isNotBlank() && ::end.isInitialized && Duration.between(
            end,
            Instant.now()
        ) > DURATION_MIN_SONG
    }


    private fun newSongStarted() {
        if (songHistory.isNotEmpty()) {
            println("Finishing up old song: ${songHistory.last().title}")
            songHistory.last().end = min(listOf(artistUpdated, titleUpdated, Instant.now()))
        }
        songHistory.add(
            SongEntry(
                title = title,
                artist = artist,
                start = min(listOf(artistUpdated, titleUpdated, Instant.now()))
            )
        )
    }

    fun handleLine(line: String) {
        //val (time, contents) = line.split(' ', limit = 2)
        //val (hour, min, sec) = time.split(':')
        //val dateTime = LocalDate.now().atTime(hour.toInt(), min.toInt(), sec.toInt())

        if (bitRateRe.containsMatchIn(line)) {
            bitRate = bitRateRe.find(line)!!.groupValues[1].toDouble()
        }
        if (titleRe.containsMatchIn(line)) {
            title = titleRe.find(line)!!.groupValues[1]
        }
        if (artistRe.containsMatchIn(line)) {
            artist = artistRe.find(line)!!.groupValues[1]
        }
        if (fileRe.containsMatchIn(line)) {
            fileLot = fileRe.find(line)!!.groupValues[1]
        }
    }

    fun splitFiles() {
        println("Splitting file")
        // read output_985.wav
        // Drop first and las song (likely a partial)
        songHistory.drop(1).dropLast(1)
        // for each song in the list
        // split the WAV file
        // rename to a folder (artist/song)
        // move the image into the folder


        TODO("Not yet implemented")
    }

    companion object {
        private val DURATION_BETWEEN_UPDATES = Duration.ofMinutes(5)
        private val DURATION_MIN_SONG = Duration.ofMinutes(3)
        private val bitRateRe = """\bAudio bit rate: ([.\d]+) kbps""".toRegex()
        private val titleRe = """\bTitle: (.+)""".toRegex()
        private val artistRe = """\bArtist: (.+)""".toRegex()
        private val fileRe = """\bLOT file:.+ name=(\S+)""".toRegex()
    }
}