package info.benjaminhill.fm.chopper

import java.time.Duration
import java.time.Instant

/**
 * Add up "on screen" time from a list of times-to-state
 * Skips last state, which is good - that is where we might trim up to.
 */
fun <T:Any> sumDurations(states:List<Pair<Instant, T>>): Map<T, Duration> {
    val durations = mutableMapOf<T, Duration>()
    states.zipWithNext { (ts0, state0), (ts1, _) ->
        val count = durations.getOrDefault(state0, Duration.ZERO)
        durations[state0] = count + Duration.between(ts0, ts1)
    }
    return durations
}
