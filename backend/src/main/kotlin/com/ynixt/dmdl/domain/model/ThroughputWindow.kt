package com.ynixt.dmdl.domain.model

import java.time.Duration

/**
 * Observed throughput of a monotonically growing byte counter, measured against wall-clock time.
 *
 * The rate is the bytes gained since the newest sample at least one window old, divided by the time from that
 * sample to *now*, so a counter that stops growing decays toward zero even while no new sample arrives.
 * A counter that goes backwards (resume from an older aria2 control file, re-allocation) restarts the window.
 */
class ThroughputWindow(
    private val retention: Duration = Duration.ofSeconds(60),
    private val warmUp: Duration = Duration.ofSeconds(2),
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Set after a regression: the regressed value is not trusted as a baseline until the counter moves again. */
    private var rebaseline = false

    fun record(
        atMillis: Long,
        bytes: Long,
    ) {
        val last = samples.lastOrNull()?.second
        if (last != null && bytes < last) {
            samples.clear()
            rebaseline = true
        } else if (rebaseline) {
            // A freshly restarted aria2 reports 0 until it reads its control file, possibly for several seconds;
            // measuring from that value would turn the whole restored file into a speed spike. The first value
            // that differs becomes the new baseline.
            samples.clear()
            if (bytes != last) rebaseline = false
        }
        samples.addLast(atMillis to bytes)
        // Keep exactly one sample older than the retention so the longest window always has a baseline.
        while (samples.size >= 2 && samples[1].first <= atMillis - retention.toMillis()) samples.removeFirst()
    }

    /** Bytes per second over [window] ending at [nowMillis]; null until [warmUp] of history exists. */
    fun rate(
        nowMillis: Long,
        window: Duration,
    ): Long? {
        val latest = samples.lastOrNull() ?: return null
        if (nowMillis - samples.first().first < warmUp.toMillis()) return null
        val baseline = samples.lastOrNull { it.first <= nowMillis - window.toMillis() } ?: samples.first()
        val span = nowMillis - baseline.first
        if (span <= 0) return null
        return ((latest.second - baseline.second) * 1000 / span).coerceAtLeast(0)
    }

    fun latest(): Long? = samples.lastOrNull()?.second

    fun clear() {
        samples.clear()
        rebaseline = false
    }

    companion object {
        val SPEED_WINDOW: Duration = Duration.ofSeconds(10)
        val AVERAGE_WINDOW: Duration = Duration.ofSeconds(60)
    }
}
