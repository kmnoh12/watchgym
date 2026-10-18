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
    /** A qualified departure begun before the deadline gets one bounded chance to return. */
    fun shouldEnd(nowNs: Long, candidateStartNs: Long?, maximumRepNs: Long,
        minimumWaitNs: Long = silenceNs): Boolean {
        if (closed) return true
        val last = lastRepNs ?: return false
        val deadline = last + maxOf(silenceNs, minimumWaitNs)
        if (nowNs < deadline) return false
        if (candidateStartNs != null && candidateStartNs > last && candidateStartNs < deadline &&
            nowNs - candidateStartNs < maximumRepNs) return false
        closed = true
        return true
    }

    companion object {
        /** Before the target is reached a pause is usually a hard set catching its breath, so the set stays open this long. */
        const val PAUSE_BEFORE_TARGET_NS = 7_000_000_000L

        /** How long without a rep ends the set: the longer mid-set pause until [targetReps] is reached, then [baseWaitNs] as before. */
        fun waitNs(reps: Int, targetReps: Int, baseWaitNs: Long): Long =
            if (reps < targetReps) maxOf(baseWaitNs, PAUSE_BEFORE_TARGET_NS) else baseWaitNs
    }
}
