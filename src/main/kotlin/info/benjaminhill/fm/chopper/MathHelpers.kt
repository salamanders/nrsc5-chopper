package info.benjaminhill.fm.chopper

import java.util.*
import java.util.stream.Stream
import kotlin.math.cos
import kotlin.math.log
import kotlin.math.sqrt


/**
 * Compute power of two greater than or equal to the input
 */
fun findNextPowerOf2(input: Int): Int {
    var n = input
    // decrement `n` (to handle the case when `n` itself is a power of 2)
    n -= 1
    // calculate the position of the last set bit of `n`
    val lg = log(n.toDouble(), 2.0).toInt()
    // next power of two will have a bit set at position `lg+1`.
    return 1 shl lg + 1
}

/**
 * Squashes the array into 0.0 .. 1.0
 */
fun DoubleArray.toUnitRange(): DoubleArray {
    if (this.isEmpty()) {
        return doubleArrayOf()
    }
    val min = this.minOrNull()!!
    val max = this.maxOrNull()!!
    val delta = max - min
    return if (delta > 0) {
        DoubleArray(this.size) { idx ->
            (this[idx] - min) / delta
        }
    } else {
        DoubleArray(this.size) {
            0.5
        }
    }
}

// fast-ish
fun DoubleArray.dist(otherArray: DoubleArray): Double {
    require(this.isNotEmpty())
    require(this.size == otherArray.size)

    return sqrt(
        this.asSequence().zip(otherArray.asSequence()) { a, b ->
            (a - b) * (a - b)
        }.sum()
    )
}

fun Stream<Double>.toStats(): DoubleSummaryStatistics =
    this.collect(
        ::DoubleSummaryStatistics,
        { obj: DoubleSummaryStatistics, value: Double ->
            obj.accept(
                value
            )
        },
        { obj: DoubleSummaryStatistics, other: DoubleSummaryStatistics ->
            obj.combine(
                other
            )
        })

private val hannCache: MutableMap<Int, DoubleArray> = mutableMapOf()

/**
 * Hanning window of size N
 * @see [MATLAB reference](https://www.mathworks.com/help/signal/ref/hann.html)
 * https://github.com/mileshenrichs/QuiFFT/blob/4f2cbe797b8dc7f8094caaa41f4551015c90495b/src/main/java/org/quifft/params/WindowFunctionGenerator.java#L77
 */
internal fun hann(size: Int): DoubleArray = hannCache.computeIfAbsent(size) {
    DoubleArray(size) { n ->
        0.5 * (1 - cos(2 * Math.PI * (n / (size - 1.0))))
    }
}
