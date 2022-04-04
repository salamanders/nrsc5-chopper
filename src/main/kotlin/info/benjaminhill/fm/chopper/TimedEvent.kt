package info.benjaminhill.fm.chopper

import java.time.Duration
import java.time.Instant

/**
 * A timed event is a pair of "stuff (state) that happened at time (ts)"
 * The idea is to keep a collection (ArrayDeque?) of these to record when things first happened.
 */
data class InstantState<T>(
    val ts: Instant,
    val state: T,
)

data class DurationState<T>(
    val duration: Duration,
    val state: T,
)

fun <T> List<InstantState<T>>.toDurations(
    contiguous: Boolean = false,
    maxTtl: Duration
) = if (contiguous) {
    maxContiguousDurations(maxTtl)
} else {
    sumDurations(maxTtl)
}

private fun <T> List<InstantState<T>>.sumDurations(
    maxTtl: Duration,
): List<DurationState<T>> =
    this.asSequence().zipWithNext().map { (is0, is1) ->
        DurationState(
            state = is0.state,
            duration = Duration.between(is0.ts, is1.ts).coerceAtMost(maxTtl),
        )
    }.groupingBy { it.state }
        .fold(Duration.ZERO) { acc, elt ->
            acc + elt.duration
        }.map {
            DurationState(
                duration = it.value,
                state = it.key
            )
        }.toList()


/**
 * Longest contiguous duration
 */
private fun <T> List<InstantState<T>>.maxContiguousDurations(
    maxTtl: Duration,
): List<DurationState<T>> {
    val maxContiguous = mutableMapOf<T, Duration>()
    this.distinctBy { it.state }.forEach {
        maxContiguous[it.state] = Duration.ZERO
    }
    var currentState: T? = null
    var currentDuration: Duration = Duration.ZERO
    this.zipWithNext().forEach { (event0, event1): Pair<InstantState<T>, InstantState<T>> ->
        val (ts0, state0) = event0
        val (ts1, _) = event1
        if (currentState != state0) {
            currentState = state0
            currentDuration = Duration.ZERO
        }
        currentDuration += Duration.between(ts0, ts1).coerceAtMost(maxTtl)
        if (currentDuration > maxContiguous[currentState]) {
            maxContiguous[currentState!!] = currentDuration
        }
    }
    return maxContiguous.map {
        DurationState(
            duration = it.value,
            state = it.key
        )
    }
}



