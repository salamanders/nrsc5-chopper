package info.benjaminhill.fm.chopper

import com.google.common.collect.HashBiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.commons.math4.complex.Complex
import org.apache.commons.math4.transform.DftNormalization
import org.apache.commons.math4.transform.FastFourierTransformer
import org.apache.commons.math4.transform.TransformType
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

private const val SAMPLE_RATE_CD = 44100f

private val AUDIO_FORMAT_CD = AudioFormat(
    /* encoding = */ AudioFormat.Encoding.PCM_SIGNED,
    /* sampleRate = */ SAMPLE_RATE_CD,
    /* sampleSizeInBits = */ 16,
    /* channels = */ 2,
    /* frameSize = */ 4,
    /* frameRate = */ SAMPLE_RATE_CD,
    /* bigEndian = */ false
)

internal fun pianoKeyToHz(key: Int) = 1.059463.pow(key - 49.0) * 440.0
internal fun hzToPianoKey(hz: Double) = (12 * log2(hz / 440.0) + 49).toInt()

internal val PIANO_KEYS_EXTENDED = (1..108)
internal val PIANO_KEYS_TO_FREQ = HashBiMap.create(PIANO_KEYS_EXTENDED.associateWith { pianoKeyToHz(it) })
private fun fftToPianoKeys(frequenciesToAmps: Map<Double, Double>): DoubleArray {
    val result = DoubleArray(PIANO_KEYS_TO_FREQ.size + 2)
    frequenciesToAmps.forEach { (freq, amp) ->
        val key = hzToPianoKey(freq).coerceIn(result.indices)
        result[key] += amp
    }
    return result.toUnitRange()
}


/**
 * Load directly if supported.
 * pcm assumes "CD" defaults of AUDIO_FORMAT_CD
 * Average together multiple channels
 * Array length = number of samples
 * Use an array instead of a List<Double> because even though List has subList, we need to multiply by the hanning window anyway.
 */
fun File.readWaveform(): DoubleArray = Timer.log("readWaveform") {
    runBlocking(Dispatchers.IO) {

        when (this@readWaveform.extension.lowercase()) {
            AudioFileFormat.Type.AU.extension, AudioFileFormat.Type.WAVE.extension, AudioFileFormat.Type.AIFC.extension, AudioFileFormat.Type.AIFF.extension, AudioFileFormat.Type.SND.extension -> {
                AudioSystem.getAudioInputStream(this@readWaveform)
            }
            "pcm" -> AudioInputStream(
                this@readWaveform.inputStream(), AUDIO_FORMAT_CD, (this@readWaveform.length() / /* frameSize */ 4)
            )
            else -> error("Unsupported audio file type: `${this@readWaveform.extension.lowercase()}`")
        }.use { ais ->
            logger.debug { "format.frameSize:${ais.format.frameSize}" }
            logger.debug { "format.channels:${ais.format.channels}" }
            logger.debug { "format.frameRate:${ais.format.frameRate}" }
            logger.debug { "format.sampleRate:${ais.format.sampleRate}" }
            logger.debug { "format.sampleSizeInBits:${ais.format.sampleSizeInBits}" }
            val sampleSize = ais.format.sampleSizeInBits / 8

            check((ais.format.sampleSizeInBits / 8) * ais.format.channels == ais.format.frameSize) {
                "Something is off with our understanding of samples, frames, and channels:" + "sampleSizeInBits:${ais.format.sampleSizeInBits}, channels:${ais.format.channels}, frameSize:${ais.format.frameSize}"
            }
            val bb = ByteBuffer.allocate(sampleSize).let {
                if (ais.format.isBigEndian) {
                    it
                } else {
                    it.order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            ais.readAllBytes().asSequence().chunked(ais.format.frameSize)
                .map { frame ->
                    frame.chunked(sampleSize).map { sample ->
                        bb.clear()
                        sample.forEach { bb.put(it) }
                        bb.getShort(0)
                    }.average()
                }.toList().toDoubleArray()
        }
    }
}


/**
 * converts a window of a waveform to an FFT complex result
 */
fun waveformWindowToFft(
    waveform: DoubleArray,
    startAtSample: Int,
    sampleSize: Int,
    useFade: Boolean = true,
): Array<Complex> = Timer.log("waveformWindowToFft") {
    val window = waveform.copyOfRange(
        startAtSample, startAtSample + findNextPowerOf2(sampleSize)
    )
    if (useFade) {
        val fader = hann(window.size)
        for (idx in window.indices) {
            window[idx] *= fader[idx]
        }
    }
    val fft = FastFourierTransformer(DftNormalization.STANDARD)
    fft.transform(window, TransformType.FORWARD)
}

/**
 * Converts from the result of an FFT to a more usable map<frequency, amplitude>
 */
private fun complexToFreqAmp(fftResult: Array<Complex>, samplesPerSecond: Double): Map<Double, Double> =
    Timer.log("complexToFreqAmp") {
        fftResult.take(fftResult.size / 2)
            .mapIndexed { idx, complex ->
                // phase = atan2(complex.imaginary, complex.real)
                val freq = idx * samplesPerSecond / fftResult.size
                val amp = hypot(complex.real, complex.imaginary)
                freq to amp
            }.toMap()
    }

private fun Duration.toSampleCount(samplesPerSecond: Double) =
    ((this.toNanos() / 1_000_000_000.0) * samplesPerSecond).toInt()

private fun Int.samplesToDuration(samplesPerSecond: Double) =
    Duration.ofNanos(((this / samplesPerSecond) * 1_000_000_000.0).toLong())

/**
 * @param minWindowSize the window size regardless of overlap, expanded to power of 2
 * @param overlap how much less to hop forward for each window
 */
fun waveformToFfts(
    waveform: DoubleArray,
    samplesPerSecond: Double = SAMPLE_RATE_CD.toDouble(),
    minWindowSize: Duration = Duration.ofSeconds(1),
    overlap: Double = 0.25,
    useFade: Boolean = true,
): Map<Duration, DoubleArray> = Timer.log("waveformToFfts") {
    require(overlap in 0.0..1.0) { "Overlap not in 0.0..1.0: $overlap" }
    val windowSampleSize = findNextPowerOf2(minWindowSize.toSampleCount(samplesPerSecond))
    val windowStepSize = (windowSampleSize * (1.0 - overlap)).toInt()
    (0 until waveform.size - windowSampleSize step windowStepSize).associate { startSample ->
        val fftComplex = waveformWindowToFft(
            waveform = waveform,
            startAtSample = startSample,
            sampleSize = windowSampleSize,
            useFade = useFade,
        )
        val freqsToAmps = complexToFreqAmp(fftComplex, samplesPerSecond)
        startSample.samplesToDuration(samplesPerSecond) to fftToPianoKeys(freqsToAmps)
    }
}

internal fun fftsToImage(ffts: Map<Duration, DoubleArray>, outputImageFile: File) = Timer.log("fftsToImage") {
    require("png".equals(outputImageFile.extension, ignoreCase = true))
    val bi1 = BufferedImage(ffts.size, ffts.values.first().size, BufferedImage.TYPE_INT_ARGB)
    ffts.toSortedMap().entries.forEachIndexed { x, fft ->
        val (_, amps) = fft
        amps.forEachIndexed { yinv, amp ->
            val y = amps.size - yinv - 1
            amp.toFloat().let {
                bi1.setRGB(x, y, Color(it, it, it).rgb)
            }
        }
    }
    ImageIO.write(bi1, "png", outputImageFile)
}



