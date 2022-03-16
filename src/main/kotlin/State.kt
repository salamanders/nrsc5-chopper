import info.benjaminhill.utils.LogInfrequently
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.scan
import mu.KLoggable
import java.io.Serializable
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Every update that we might use is captured in timestamped copies of the state */
internal data class State(
    val title: String? = null,
    val artist: String? = null,
    val file: String? = null,
) : Serializable {
    val ts: Instant = Instant.now()

    companion object : Any(), KLoggable {
        override val logger = logger()
        private val titleRe = """\bTitle: (.+)""".toRegex()
        private val artistRe = """\bArtist: (.+)""".toRegex()
        private val fileRe = """\bLOT file:.+ name=(\S+)""".toRegex()

        private val logInfrequently = LogInfrequently(
            delay = 30.seconds
        )

        private val IGNORE_LINES = arrayOf(
            " XHDR:",
            " MER:",
            " BER:",
            "SIG Service:",
            "Data component:",
            "Audio component:",
            "Data service: public, type: Navigation",
            "Data service: public, type: Traffic",
            "Data service: public, type: Service Maintenance",
            "Audio bit rate:",
            "PLL not locked!",
            "zero-copy buffers",
            "Audio program",
            "FCC facility ID",
            "Disabled direct sampling mode",
            "Exact sample rate is",
            "Found Rafael Micro R820T tuner",
            "Message:",
            "Runtime:",
            "Slogan:",
            "Station location:",
            "Station name:",
        )

        /** Immediately ignore as much as possible */
        internal fun Flow<String>.dropExtras(): Flow<String> = filter { line ->
            logInfrequently.hit()
            IGNORE_LINES.none { line.contains(it) } && !line.endsWith(" Synchronized")
        }

        internal fun Flow<String>.toState(): Flow<State> = scan(State()) { acc, line ->
            when {
                titleRe.containsMatchIn(line) -> acc.copy(title = titleRe.find(line)!!.groupValues[1])
                artistRe.containsMatchIn(line) -> acc.copy(artist = artistRe.find(line)!!.groupValues[1])
                fileRe.containsMatchIn(line) -> acc.copy(file = fileRe.find(line)!!.groupValues[1])
                else -> acc.also { println("Unexpected line, add to ignore: `$line`") }
            }
        }.distinctUntilChanged()
    }
}

