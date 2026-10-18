package dev.kyungmin.kcoach.core

/**
 * Haptic cue delay for vertical presses. The counting event closes while the descent is still ~35% from the bottom,
 * so the cue is held back by a share of the time since the top (ascent-to-turnaround). Counting is not affected.
 * Fitted on one Smith paired capture (10 reps); the fraction is a tunable starting value, not a validated constant.
 */
object RepCueDelay {
    const val FRACTION = 0.40
    const val MIN_MS = 150L
    const val MAX_MS = 600L
    private const val MAX_TURN_TO_EVENT_MS = 5_000.0

    fun delayMs(turnaroundNs: Long, eventEndNs: Long): Long {
        val sinceTopMs = (eventEndNs - turnaroundNs) / 1e6
        if (!sinceTopMs.isFinite() || sinceTopMs <= 0.0 || sinceTopMs > MAX_TURN_TO_EVENT_MS) return 0L
        return (FRACTION * sinceTopMs).toLong().coerceIn(MIN_MS, MAX_MS)
    }
}
