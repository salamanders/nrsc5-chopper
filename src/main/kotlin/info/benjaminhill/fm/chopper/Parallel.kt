package info.benjaminhill.fm.chopper

import com.aparapi.Kernel
import com.aparapi.Range
import com.aparapi.device.Device
import com.aparapi.internal.kernel.KernelManager
import kotlin.math.hypot
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


@OptIn(ExperimentalTime::class)
fun main() {
    val inA = FloatArray(1024 * 1024 * 200) {
        Math.random().toFloat()
    }
    val inB = FloatArray(1024 * 1024 * 200) {
        Math.random().toFloat()
    }
    assert(inA.size == inB.size)

    val dist: Float
    val duration = measureTime {
        dist = hypotArray(inA, inB, false)
    }
    println("Dist: $dist")
    println("${duration.inWholeMilliseconds}ms")

    val report = StringBuilder()
    KernelManager.instance().let { km ->
        km.reportDeviceUsage(report, true)
        km.reportProfilingSummary(report)
    }
    println(report.toString())

}

fun hypotArray(inA: FloatArray, inB: FloatArray, useParallel: Boolean): Float {
    assert(inA.size == inB.size)

    return if (useParallel) {
        val result = FloatArray(inA.size)
        object : Kernel() {
            override fun run() {
                val i: Int = globalId
                result[i] = kotlin.math.hypot(inA[i], inB[i]) / result.size
            }
        }.execute(Range.create(result.size))
        result.sum()
    } else {
        inA.asSequence()
            .zip(inB.asSequence())
            .fold(0f) { acc, next ->
                acc + hypot(next.first, next.second) / inA.size
            }
    }
}
