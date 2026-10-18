package dev.kyungmin.kcoach.core

/**
 * After the target rep count is reached, only a rep that follows the set's own tempo may be added.
 * A far shorter gap than the recent median (re-rack twist, hand drop, shuffling) is refused, so it is neither
 * counted nor allowed to extend the set-end timer. Reps up to the target are never examined.
 * The history lives in memory: with fewer than [MIN_INTERVALS] intervals (e.g. after a restart) everything is accepted.
 */
class PostTargetTempoGuard {
    private val endsNs = ArrayList<Long>()

    fun reset() = endsNs.clear()

    /** [repsBefore] is the count before this event; the event is beyond the target when it is already >= [targetReps]. */
    fun accept(endNs: Long, repsBefore: Int, targetReps: Int): Boolean {
        val last = endsNs.lastOrNull()
        if (targetReps > 0 && repsBefore >= targetReps && last != null && endsNs.size > MIN_INTERVALS) {
            val intervals = endsNs.zipWithNext { a, b -> b - a }.takeLast(WINDOW)
            val median = intervals.sorted().let { it[it.size / 2] }
            if (endNs - last < (median * MIN_FRACTION).toLong()) return false
        }
        endsNs += endNs
        return true
    }

    companion object {
        const val MIN_INTERVALS = 3
        const val WINDOW = 5
        const val MIN_FRACTION = 0.5
    }
}
