package info.benjaminhill.fm.chopper

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.InvalidArgumentException
import com.xenomachina.argparser.default
import java.io.File
import java.time.Duration

class ChopperArgs(parser: ArgParser) {
    val maxDuration: Duration by parser.storing(
        "-d", "--duration",
        help = "max duration (in days, default limitless)"
    ) { Duration.ofHours(this.toLong()) }
        .default(Duration.ofDays(999999))

    val output: File by parser.storing(
        "-o", "--output",
        help = "output folder location (default: ./output"
    ) { File(this) }.default(File("./output"))
        .addValidator {
            value.mkdirs()
            if (!value.exists() || !value.canWrite())
                throw InvalidArgumentException("Unable to write to `${value.canonicalPath}`")
        }

    val temp: File by parser.storing(
        "-t", "--temp",
        help = "temp file location (default: ./temp)"
    ) { File(this) }.default(File("./temp"))
        .addValidator {
            value.mkdirs()
            if (!value.exists() || !value.canWrite()) {
                throw InvalidArgumentException("Unable to write to `${value.canonicalPath}`")
            }
            value.listFiles()?.filterNot { it.isDirectory }
                ?.filter { it.extension in listOf("jpeg", "jpg")}
                ?.forEach { it.delete() }
        }

    val frequency: Double by parser.positional(
        "FREQUENCY",
        help = "station frequency (default=98.5)",
    ) { toDouble() }.default(98.5)

    val channel: Int by parser.positional(
        "CHANNEL",
        help = "station channel 0..3 (default=0)"
    ) { toInt() }.default(0)
        .addValidator {
            if (value !in 0..3)
                throw InvalidArgumentException("channel must be in 0..3")
        }
}