package dev.kyungmin.kcoach.core

/** User-requested inactivity policy, not a validated exercise classifier. Times are monotonic. */
class SetEndGate(private val silenceNs: Long = 3_000_000_000L) {
    private var lastRepNs: Long? = null
    private var closed = false
    fun reset() { lastRepNs = null; closed = false }
    fun rep(nowNs: Long): Boolean {
        if (closed) return false
        lastRepNs = nowNs
        return true
    }
    /**
     * A qualified departure begun before the deadline gets one bounded chance to return.
     * [lastMotionNs] is the latest sample that still looked like working motion even when the
     * detector rejected the repetition. A slow grind that the counter misses therefore keeps
     * the set open; complete stillness still ends it after the same wait.
     */
    fun shouldEnd(nowNs: Long, candidateStartNs: Long?, maximumRepNs: Long,
        minimumWaitNs: Long = silenceNs, lastMotionNs: Long? = null): Boolean {
        if (closed) return true
        val last = lastRepNs ?: return false
        val anchor = maxOf(last, lastMotionNs ?: 0L)
        val deadline = anchor + minimumWaitNs
        if (nowNs < deadline) return false
        if (candidateStartNs != null && candidateStartNs > anchor && candidateStartNs < deadline &&
            nowNs - candidateStartNs < maximumRepNs) return false
        closed = true
        return true
    }

    companion object {
        /** Before the target is reached a pause is usually a hard set catching its breath, so the set stays open this long. */
        const val PAUSE_BEFORE_TARGET_NS = 7_000_000_000L

        /** Landing exactly on the planned reps: the lifter is done, so a short stillness closes the set. */
        const val TARGET_HIT_WAIT_NS = 1_200_000_000L

        /**
         * How long without a rep ends the set: the longer mid-set pause until [targetReps] is reached,
         * a short one on exactly the target, and [baseWaitNs] once the lifter keeps going past it.
         */
        fun waitNs(reps: Int, targetReps: Int, baseWaitNs: Long): Long = when {
            targetReps <= 0 -> baseWaitNs
            reps < targetReps -> maxOf(baseWaitNs, PAUSE_BEFORE_TARGET_NS)
            reps == targetReps -> TARGET_HIT_WAIT_NS
            else -> baseWaitNs
        }
    }
}
