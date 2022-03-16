import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import javax.sound.sampled.AudioSystem


fun encodeAudioFileToMp3(source: File, destination: File, offsetFromStart: Duration? = null, length: Duration? = null) {
    require(source.canRead()) { "Unable to read ${source.canonicalPath}" }
    val audioInputStream = AudioSystem.getAudioInputStream(source.inputStream().buffered())

    val audioFormat = audioInputStream.format
    val bytesPerSecond = audioFormat.frameRate * audioFormat.frameSize

    if (offsetFromStart != null) {
        val seconds = offsetFromStart.toMillis() / 1000.0
        val bytes = bytesPerSecond * seconds
        audioInputStream.skip(bytes.toLong())
        println("- Skipped ${seconds}s, $bytes bytes")
    }

    val maxBytes = if (length != null) {
        val seconds = length.toMillis() / 1000.0
        (bytesPerSecond * seconds).also {
            println("- Only reading duration ${seconds}s, $bytesPerSecond bytes")
        }
    } else {
        source.length()
    }.toInt()

    val encoder = LameEncoder(audioFormat)
    val mp3 = ByteArrayOutputStream()
    val inputBuffer = ByteArray(encoder.pcmBufferSize)
    val outputBuffer = ByteArray(encoder.pcmBufferSize)

    var bytesRead: Int = 0
    var bytesWritten: Int
    while (bytesRead <= maxBytes && 0 < audioInputStream.read(inputBuffer).also { bytesRead = it }) {
        bytesWritten = encoder.encodeBuffer(inputBuffer, 0, bytesRead, outputBuffer)
        mp3.write(outputBuffer, 0, bytesWritten)
    }
    destination.writeBytes(mp3.toByteArray())
}

