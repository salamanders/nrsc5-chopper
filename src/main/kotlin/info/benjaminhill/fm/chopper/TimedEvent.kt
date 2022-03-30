package info.benjaminhill.fm.chopper

import java.time.Duration
import java.time.Instant


typealias TimedEvent<T> = Pair<Instant, T>

val <T> TimedEvent<T>.ts:Instant
    get() = this.first

val <T> TimedEvent<T>.state:T
    get() = this.second

/**
 * Longest contiguous duration
 * @param states Event stream of a new state taking over.
 */
fun <T : Any> maxContiguousDurations(states: List<TimedEvent<T>>): Map<T, Duration> {
    val best = mutableMapOf<T, Duration>()
    states.distinctBy { it.state }.forEach{
        best[it.state] = Duration.ZERO
    }
    var currentState:T? = null
    var currentDuration :Duration = Duration.ZERO
    states.dropLast(1).zipWithNext().forEach { (event0, event1): Pair<TimedEvent<T>, TimedEvent<T>> ->
        val (ts0, state0) = event0
        val (ts1, _) = event1
        if(currentState != state0) {
            currentState = state0
            currentDuration = Duration.ZERO
        }
        currentDuration += Duration.between(ts0, ts1)
        if(currentDuration > best[currentState]) {
            best[currentState!!] = currentDuration
        }
    }
    return best
}