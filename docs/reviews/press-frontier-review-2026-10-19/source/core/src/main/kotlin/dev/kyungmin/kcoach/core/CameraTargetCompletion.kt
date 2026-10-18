package dev.kyungmin.kcoach.core

/** Capture control only. A camera proposal still needs user review before becoming a label. */
class CameraTargetCompletion {
    var motionEndCameraNs: Long? = null
        private set
    private var lastNs = -1L

    /** Latch the written frame completing rep ten; later walking/occlusion cannot restart the timer. */
    fun observe(cameraNs: Long, reps: Int, eligible: Boolean): Long? {
        if (cameraNs <= lastNs || cameraNs < 0L) return null
        lastNs = cameraNs
        if (motionEndCameraNs == null && eligible && reps >= CameraCaptureEnd.TARGET_REPS)
            motionEndCameraNs = cameraNs
        return motionEndCameraNs?.takeIf { cameraNs - it >= 3_000_000_000L }
    }

    fun remainingSeconds(cameraNs: Long): Int? = motionEndCameraNs?.let {
        ((3_000_000_000L - (cameraNs - it)).coerceAtLeast(0L) + 999_999_999L).div(1_000_000_000L).toInt()
    }
}
