package info.benjaminhill.fm.chopper

import java.time.Duration
import java.time.Instant

object Timer {
    private data class TimerLogEntry(
        val name: String,
        val duration: Duration,
    )

    private val timerLog = mutableListOf<TimerLogEntry>()

    fun <T> log(name: String, block: () -> T): T {
        val mark = Instant.now()
        val result = block()
        timerLog.add(TimerLogEntry(name, Duration.between(mark, Instant.now())))
        return result
    }

    fun report() {
        println("Timer log size: ${timerLog.size}")
        println(listOf("name", "total ms", "avg ms", "times").joinToString("\t"))
        timerLog.groupBy { it.name }.forEach { (name, entries) ->
            println(
                listOf(
                    name,
                    entries.sumOf { it.duration.toMillis() },
                    entries.map { it.duration.toMillis() }.average().toUInt(),
                    entries.size
                ).joinToString("\t")
            )
        }
    }
}