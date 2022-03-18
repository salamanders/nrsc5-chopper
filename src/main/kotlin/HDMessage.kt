import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transform
import mu.KLoggable
import java.io.Serializable
import java.time.Instant

/** Every update that we might use is captured in timestamped copies of the state */
internal data class HDMessage(
    val type: Type,
    val value: String,
    val ts: Instant = Instant.now()
) : Serializable {
    enum class Type {
        TITLE, ARTIST, FILE
    }

    companion object : Any(), KLoggable {
        override val logger = logger()
        private val titleRe = """\bTitle: (.+)""".toRegex()
        private val artistRe = """\bArtist: (.+)""".toRegex()
        private val fileRe = """\bLOT file:.+\blot=(\d+).+\bname=(\S+)""".toRegex()

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

        private val IGNORE_FILES = setOf(
            "52275_SLKUFX\$\$010006.jpg",
            "52276_SLKUFX\$\$020006.jpg",
        )

        /** Immediately ignore as much as possible */
        internal fun Flow<String>.dropExtras(): Flow<String> = filter { line ->
            IGNORE_LINES.none { line.contains(it) } && !line.endsWith(" Synchronized")
        }

        internal fun Flow<String>.toHDMessages(): Flow<HDMessage> = transform { line ->
            when {
                titleRe.containsMatchIn(line) -> emit(HDMessage(Type.TITLE, titleRe.find(line)!!.groupValues[1]))
                artistRe.containsMatchIn(line) -> emit(HDMessage(Type.ARTIST, artistRe.find(line)!!.groupValues[1]))
                fileRe.containsMatchIn(line) -> {
                    if (IGNORE_FILES.none { line.contains(it) }) {
                        emit(
                            HDMessage(
                                Type.FILE,
                                fileRe.find(line)!!.let { "${it.groupValues[1]}_${it.groupValues[2]}" })
                        )
                    }
                }
                else -> logger.warn { "Unexpected line, add to ignore: `$line`" }
            }
        }
    }
}

