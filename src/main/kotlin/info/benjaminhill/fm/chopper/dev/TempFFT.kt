package info.benjaminhill.fm.chopper.dev


/*

import info.benjaminhill.utils.r
import org.quifft.QuiFFT
import org.quifft.audioread.PCMFile
import org.quifft.audioread.PCMReader
import org.quifft.output.FFTResult
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import kotlin.time.ExperimentalTime
@OptIn(ExperimentalTime::class)
fun main() {



    val baselineFft = QuiFFT(baseline).fullFFT()
    val baselineAmplitudes: Array<DoubleArray> = baselineFft.getAmped()
    val windowSize = 10 // baselineFft.fftFrames.indexOfFirst { it.frameStartMs > 5_000 }

    // Faster if I could do https://dsp.stackexchange.com/questions/736/how-do-i-implement-cross-correlation-to-prove-two-audio-files-are-similar
    dreams.forEach { other ->
        val otherFft = QuiFFT(other).fullFFT()
        val otherAmplitudes = otherFft.getAmped()
        val otherIdx = otherAmplitudes.size / 2

        // Where does other best line up with baseline?
        val (bestBaselineIdx, _) = (0 until baselineAmplitudes.size - windowSize).map { baselineIdx ->
            // Optimization -- scan the middle third?
            baselineIdx to compareWindows(baselineAmplitudes, baselineIdx, otherAmplitudes, otherIdx, windowSize)
        }.minByOrNull { it.second }!!

        val otherMs = otherFft.fftFrames[otherIdx].frameStartMs
        val bestBaselineMs = baselineFft.fftFrames[bestBaselineIdx].frameStartMs

        println("Baseline:${baseline.name} frame:$bestBaselineIdx ms:$bestBaselineMs = ${other.name} frame:$otherIdx ms:$otherMs")
        println(
            "${baseline.name}\t${other.name}\t${
                Duration.ofMillis((bestBaselineMs - otherMs).toLong()).toFractionalSeconds().r
            }sec"
        )
    }

    val baseWav: IntArray = PCMReader(baseline).waveform



    val offsetWav: IntArray = PCMReader(movingSongFile).waveform

    val bestDiff = bestDiffs.first()
    val bestDiffSec = bestDiff.toExactSeconds()
    println("match without offset: ${baseWav.relativeMatch(offsetWav)}")
    println("match w offset: ${baseWav.relativeMatch(offsetWav, otherShift=(44100 * bestDiffSec).toInt())}")
    println("match w offset 2: ${baseWav.relativeMatch(offsetWav, otherShift=(-1* 44100 * bestDiffSec).toInt() )}")
}

private fun saveImage(
    amps: Array<DoubleArray>, outputImageFile: File
) {

}

fun FFTResult.getAmped(): Array<DoubleArray> = fftFrames.map { frame ->
    frame.bins.filter { bin -> MUSIC_RANGE.contains(bin.frequency.toInt()) }
        .map { bin -> NORMALIZE_AMPLITUDE(bin.amplitude) }.toDoubleArray()
}.toTypedArray()

/** How well do two smaller windows match up?
fun compareWindows(
    baselineAmplitudes: Array<DoubleArray>,
    baselineIdx: Int,
    otherAmplitudes: Array<DoubleArray>,
    otherIdx: Int,
    windowSize: Int
): Double =
    (0 until windowSize).sumOf { i ->
        baselineAmplitudes[baselineIdx + i].diff(otherAmplitudes[otherIdx + i])
    }



fun IntArray.relativeMatch(other: IntArray, otherShift: Int = 0): Double {
    return (0 until this.size).filter { idx ->
        idx - otherShift in other.indices
    }.map { idx ->
        (this[idx] - other[idx - otherShift]) * (this[idx] - other[idx - otherShift])
    }.average()
}

*/