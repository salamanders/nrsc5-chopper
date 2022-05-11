package info.benjaminhill.fm.chopper

import com.google.common.collect.HashBiMap
import mu.KLoggable
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
import java.util.*
import java.util.stream.Stream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.streams.asStream

/**
 * Soaks up a file and does FFT magic on it
 * @param minWindowSize the window size regardless of overlap, expanded to power of 2
 * @param overlap how much less to hop forward for each window
 */
class AudioFile(
    private val audioFile: File,
    minWindowSize: Duration = Duration.ofSeconds(1),
    private val overlap: Double = 0.25,
    private val useFade: Boolean = true,
) {
    private val format: AudioFormat
    private val windowSampleSize: Int

    init {
        require(overlap in 0.0..1.0) { "Overlap not in 0.0..1.0: $overlap" }
        getFileAudioInputStream().use {
            format = it.format
        }
        windowSampleSize = findNextPowerOf2(durationToSampleCount(minWindowSize))
    }

    /**
     * @param precalculatedPianoKeys if the final values are already known
     */
    inner class Frame(
        val startAtSample: Int,
        precalculatedPianoKeys:DoubleArray? = null,
    ) {
        val ts:Duration = sampleCountToDuration(startAtSample)
        private val fft: Array<Complex> by lazy {
            val window = waveform.copyOfRange(
                startAtSample, startAtSample + windowSampleSize
            )
            if (useFade) {
                val fader = hann(window.size)
                for (idx in window.indices) {
                    window[idx] *= fader[idx]
                }
            }
            FastFourierTransformer(DftNormalization.STANDARD).transform(window, TransformType.FORWARD)
        }

        /**
         * Converts from the result of an FFT to a more usable map<frequency, amplitude>
         */
        private val frequenciesToAmps: SortedMap<Double, Double> by lazy {
            fft.take(fft.size / 2).mapIndexed { idx, complex ->
                    // phase = atan2(complex.imaginary, complex.real)
                    val freq = idx * format.sampleRate.toDouble() / fft.size
                    val amp = hypot(complex.real, complex.imaginary)
                    freq to amp
                }.toMap().toSortedMap()
        }

        /**
         * Array of amplitudes, not normalized (normalization should be over the entire song, if at all)
         */
        val pianoKeys: DoubleArray by lazy {
            if(precalculatedPianoKeys!=null) {
                precalculatedPianoKeys
            } else {
                val result = DoubleArray(PIANO_KEYS_TO_FREQ.size + 2)
                frequenciesToAmps.forEach { (freq, amp) ->
                    val key = hzToPianoKey(freq).coerceIn(result.indices)
                    result[key] += amp
                }
                result
            }
        }
    }

    /**
     * Each frame is a chunk of the audio
     */
    val frames: List<Frame> by lazy {
        val windowStepSize = (windowSampleSize * (1.0 - overlap)).toInt()
        (0 until waveform.size - windowSampleSize step windowStepSize).map { startSample ->
            Frame(startSample)
        }.sortedBy { it.startAtSample }.also {
            logger.info { "windowSampleSize: $windowSampleSize, overlap: $overlap, windowStepSize: $windowStepSize generated ${it.size} frames" }
        }
    }

    /**
     * Normalized across all frames (so it requires the whole file to be processed)
     */
    private val normalizedFrames: List<Frame> by lazy {
        var allAmps:Stream<Double> = Stream.of()
        frames.forEach { frame->
            allAmps = Stream.concat(allAmps, frame.pianoKeys.asSequence().asStream())
        }
        val stats = allAmps.toStats()
        logger.debug { "Stats: $stats" }
        frames.map { oldFrame->
            Frame(
                startAtSample = oldFrame.startAtSample,
                precalculatedPianoKeys = oldFrame.pianoKeys.map { oldAmp ->
                    (oldAmp - stats.min)/(stats.max - stats.min)
                }.toDoubleArray()
            )
        }
    }

    private fun getFileAudioInputStream() = when (audioFile.extension.lowercase()) {
        AudioFileFormat.Type.AU.extension, AudioFileFormat.Type.WAVE.extension, AudioFileFormat.Type.AIFC.extension, AudioFileFormat.Type.AIFF.extension, AudioFileFormat.Type.SND.extension -> {
            AudioSystem.getAudioInputStream(audioFile)
        }
        "pcm" -> AudioInputStream(
            audioFile.inputStream(), AUDIO_FORMAT_CD, (audioFile.length() / /* frameSize */ 4)
        )
        else -> error("Unsupported audio file type: `${audioFile.extension.lowercase()}`")
    }

    /**
     * The entire waveform of the audio file
     * Load directly if supported.
     * pcm assumes "CD" defaults of AUDIO_FORMAT_CD
     * Average together multiple channels into a mono signal
     * Array length = number of samples
     * Use an array instead of a List<Double> because even though List has subList, we need to multiply by the hanning window anyway.
     */
    val waveform: DoubleArray by lazy {
        getFileAudioInputStream().use { ais ->
            logger.debug { "format.frameSize:${format.frameSize}" }
            logger.debug { "format.channels:${format.channels}" }
            logger.debug { "format.frameRate:${format.frameRate}" }
            logger.debug { "format.sampleRate:${format.sampleRate}" }
            logger.debug { "format.sampleSizeInBits:${format.sampleSizeInBits}" }
            val sampleSize = format.sampleSizeInBits / 8

            check((format.sampleSizeInBits / 8) * format.channels == format.frameSize) {
                "Something is off with our understanding of samples, frames, and channels:" + "sampleSizeInBits:${format.sampleSizeInBits}, channels:${format.channels}, frameSize:${format.frameSize}"
            }
            val bb = ByteBuffer.allocate(sampleSize).let {
                if (format.isBigEndian) {
                    it
                } else {
                    it.order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            ais.readAllBytes().asSequence().chunked(format.frameSize).map { frame ->
                    frame.chunked(sampleSize).map { sample ->
                        bb.clear()
                        sample.forEach { bb.put(it) }
                        bb.getShort(0)
                    }.average()
                }.toList().toDoubleArray()
        }
    }

    private fun durationToSampleCount(duration: Duration) =
        ((duration.toNanos() / 1_000_000_000.0) * format.sampleRate.toDouble()).toInt()

    private fun sampleCountToDuration(samples: Int): Duration =
        Duration.ofNanos(((samples / format.sampleRate.toDouble()) * 1_000_000_000.0).toLong())

    internal fun toBufferedImage(): BufferedImage = Timer.log("fftsToImage") {
        val bi1 = BufferedImage(frames.size, frames.first().pianoKeys.size, BufferedImage.TYPE_INT_ARGB)
        normalizedFrames.forEachIndexed { x, nframe ->
           nframe.pianoKeys.forEachIndexed { yinv, amp ->
                val y = nframe.pianoKeys.size - yinv - 1
                amp.toFloat().let {
                    bi1.setRGB(x, y, Color(it, it, it).rgb)
                }
            }
        }
        bi1
    }

    companion object : Any(), KLoggable {
        override val logger = logger()
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

        fun pianoKeyToHz(key: Int) = 1.059463.pow(key - 49.0) * 440.0
        fun hzToPianoKey(hz: Double) = (12 * log2(hz / 440.0) + 49).toInt()
        private val PIANO_KEYS_EXTENDED = (1..108)
        private val PIANO_KEYS_TO_FREQ = HashBiMap.create(PIANO_KEYS_EXTENDED.associateWith { pianoKeyToHz(it) })
    }
}














