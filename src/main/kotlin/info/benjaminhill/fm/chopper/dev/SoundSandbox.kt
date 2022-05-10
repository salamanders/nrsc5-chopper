package info.benjaminhill.fm.chopper.dev

import java.io.DataOutputStream
import java.io.FileOutputStream
import javax.sound.sampled.*
import kotlin.math.sin

object SoundSandbox {
    /**
     * Number of samples per second 44100 is standard, this is configurable
     */
    private const val SAMPLE_RATE = 44100

    /**
     * This isn't configurable, for now. Do not change.
     */
    private const val BITS_PER_SAMPLE = 16

    /**
     * File length in seconds. This is configurable.
     */
    private const val FILE_LENGTH = 1

    private const val CHANNELS = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val samples =
            Array(CHANNELS) { DoubleArray(SAMPLE_RATE * FILE_LENGTH) }
        //Will be casted to short but used double to keep preserve quality until the end

        // ===== Generate Samples Here =====
        val a = 440.0
        val d = 293.7
        for (i in samples[0].indices) {
            samples[0][i] = getSampleAtFrequency(a, i, (0xFFF).toDouble()) //A tone
        }
        for (i in samples[0].indices) {
            samples[1][i] = getSampleAtFrequency(d, i, (0xFFF).toDouble()) //D tone
        }
        playSamples(samples)
        saveSamples(samples, "examples/multi.wav", false)
    }

    /**
     * Returns an amplitude value between -1 and 1 based on the parameters
     *
     * @param frequency
     * frequency in hz
     * @param sampleNum
     * number of samples into the wave
     * @return sample value
     */
    private fun getSampleAtFrequency(frequency: Double, sampleNum: Int): Double {
        return sin(frequency * (2 * Math.PI) * sampleNum / SAMPLE_RATE)
    }

    /**
     * Returns an amplitude value between `-amplitude` and `amplitude` based on the parameters
     *
     * @param frequency
     * frequency in hz
     * @param sampleNum
     * number of samples into the wave
     * @param amplitude
     * number to scale the amplitude by
     * @return sample value
     */
    private fun getSampleAtFrequency(frequency: Double, sampleNum: Int, amplitude: Double): Double {
        return amplitude * getSampleAtFrequency(frequency, sampleNum)
    }

    /**
     * Play samples through the speakers
     *
     * @param samples
     * samples to play
     */
    private fun playSamples(samples: Array<DoubleArray>) {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS_PER_SAMPLE, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        var dataLine: SourceDataLine? = null
        try {
            dataLine = AudioSystem.getLine(info) as SourceDataLine
            dataLine.open(format)
        } catch (e: LineUnavailableException) {
            e.printStackTrace()
        }
        if (dataLine != null) {
            dataLine.start()
            val sampleBytes = convertSamplesToBytes(samples, true)
            var pos = 0
            while (pos < sampleBytes.size) {
                pos += dataLine.write(sampleBytes, pos, dataLine.bufferSize.coerceAtMost(sampleBytes.size - pos))
            }
            dataLine.drain()
            dataLine.close()
        }
    }

    /**
     * Same samples to a wav file
     *
     * @param samples samples to write to the wav file
     * @param outfile file to save to
     */
    private fun saveSamples(samples: Array<DoubleArray>, outfile: String, mono: Boolean) {
        val byteSamples = convertSamplesToBytes(samples, mono)
        val numChannels: Short = if (mono) {
            1
        } else {
            samples.size.toShort()
        }
        try {
            val outStream = DataOutputStream(FileOutputStream(outfile))

            // write the wav file per the wav file format
            outStream.writeBytes("RIFF") // 00 - RIFF
            outStream.write(intToByteArray(36 + byteSamples.size), 0, 4) // 04 - how big is the rest of this file?
            outStream.writeBytes("WAVE") // 08 - WAVE
            outStream.writeBytes("fmt ") // 12 - fmt
            outStream.write(intToByteArray(16), 0, 4) // 16 - size of this chunk
            outStream.write(
                shortToByteArray(1.toShort()),
                0,
                2
            ) // 20 - what is the audio format? 1 for PCM = Pulse Code Modulation
            outStream.write(shortToByteArray(numChannels), 0, 2) // 22 - mono or stereo? 1 or 2? (or 5 or ???)
            outStream.write(intToByteArray(SAMPLE_RATE), 0, 4) // 24 - samples per second (numbers per second)
            outStream.write(
                intToByteArray(BITS_PER_SAMPLE / 8 * SAMPLE_RATE * numChannels),
                0,
                4
            ) // 28 - bytes per second
            outStream.write(
                shortToByteArray((BITS_PER_SAMPLE / 8 * numChannels).toShort()),
                0,
                2
            ) // 32 - # of bytes in one sample, for all channels
            outStream.write(
                shortToByteArray(BITS_PER_SAMPLE.toShort()),
                0,
                2
            ) // 34 - how many bits in a sample(number)? usually 16 or 24
            outStream.writeBytes("data") // 36 - data
            outStream.write(intToByteArray(byteSamples.size), 0, 4) // 40 - how big is this data chunk
            outStream.write(byteSamples) // 44 - the actual data itself - just a long string of numbers
            outStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun convertSamplesToBytes(samples: Array<DoubleArray>, mono: Boolean): ByteArray {
        val sampleBytes: ByteArray
        if (mono) {
            sampleBytes = ByteArray(samples[0].size * 2)

            // splits up the shorts into separate bytes for the audio stream
            // Adds the channels together in this step as well so they both play at the same time
            var i = 0
            while (i < sampleBytes.size) {
                val it = i / 2
                var sample = 0.0
                for (doubles in samples) {
                    sample += doubles[it]
                }
                val sampleS = shortToByteArray(sample.toInt().toShort())
                sampleBytes[i] = sampleS[0]
                i++
                sampleBytes[i] = sampleS[1]
                i++
            }
        } else {
            sampleBytes = ByteArray(samples.size * samples[0].size * 2)
            var i = 0
            while (i < sampleBytes.size) {
                val it = i / (2 * samples.size)
                for (doubles in samples) {
                    val sample = shortToByteArray(doubles[it].toInt().toShort())
                    sampleBytes[i] = sample[0]
                    i++
                    sampleBytes[i] = sample[1]
                    i++
                }
            }
        }
        return sampleBytes
    }

    /**
     * @param i
     * data to convert
     * @return unsigned little endian bytes that make up i
     */
    private fun intToByteArray(i: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (i and 0x00FF).toByte()
        b[1] = (i shr 8 and 0x000000FF).toByte()
        b[2] = (i shr 16 and 0x000000FF).toByte()
        b[3] = (i shr 24 and 0x000000FF).toByte()
        return b
    }

    /**
     * @param data
     * short to convert
     * @return unsigned little endian bytes that make up data
     */
    private fun shortToByteArray(data: Short): ByteArray {
        return byteArrayOf((data.toInt() and 0xff).toByte(), (data.toInt() ushr 8 and 0xff).toByte())
    }
}