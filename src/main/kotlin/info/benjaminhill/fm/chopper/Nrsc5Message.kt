package info.benjaminhill.fm.chopper

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import mu.KLoggable
import java.io.Serializable
import java.time.Instant

/** Every update that we might use is captured in timestamped copies of the state */
internal data class Nrsc5Message(
    val type: Type, val value: String
) : Serializable {

    companion object : Any(), KLoggable {
        override val logger = logger()

        private val IGNORE_LINES = setOf(
            " XHDR:",
            " MER:",
            " BER:",
            "SIG Service:",
            "Data component:",
            "Audio component:",
            "Data service: public, type: Navigation",
            "Data service: public, type: Traffic",
            "Data service: public, type: Service Maintenance",
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
            "Unexpected block type:",
        )

        internal fun Flow<Pair<Instant, String>>.toHDMessages(): Flow<Pair<Instant, Nrsc5Message>> =
            transform { (instant, line) ->
                if (IGNORE_LINES.any { line.contains(it) } ||
                    line.endsWith(" Synchronized")
                ) {
                    return@transform
                }
                Type.values().firstOrNull { it.matches(line) }?.let { type ->
                    emit(instant to Nrsc5Message(type, type.value(line)))
                } ?: logger.warn { "Unexpected line, add to ignore: `$line`" }
            }
    }

    enum class Type(
        private val regexp: Regex,
        private val template: (MatchResult?) -> String = { it!!.groupValues[1] }
    ) {
        TITLE(regexp = """\bTitle: (.+)""".toRegex()),
        ARTIST(regexp = """\bArtist: (.+)""".toRegex()),
        FILE(
            regexp = """\bLOT file:.+\blot=(\d+).+\bname=(\S+)""".toRegex(),
            template = { result: MatchResult? -> result!!.let { "${it.groupValues[1]}_${it.groupValues[2]}" } }
        ),
        BITRATE(regexp = """\bAudio bit rate: ([0-9]+)""".toRegex());

        fun matches(line: String) = regexp.containsMatchIn(line)

        fun value(line: String) = template(regexp.find(line))
    }

    /** Current (cumulative) state of play, based on running latest message */
    internal data class State(
        val title: String = "",
        val artist: String = "",
        val file: String = "",
        val bitrate: Long = 0,
        val changeType:Type = Type.TITLE,
    ) {
        fun getValue(type: Type): String = when (type) {
            Type.TITLE -> title
            Type.ARTIST -> artist
            Type.FILE -> file
            Type.BITRATE -> bitrate.toString()
        }

        fun updatedCopy(type: Type, value: String) = when (type) {
            Type.TITLE -> copy(title = value, changeType = type)
            Type.ARTIST -> copy(artist = value, changeType = type)
            Type.FILE -> copy(file = value, changeType = type)
            Type.BITRATE -> copy(bitrate = value.toLong(), changeType = type)
        }
    }
}


