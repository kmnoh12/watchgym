package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Motion families describe the observable wrist cycle, not whole-body form. */
enum class MotionFamily {
    FLEXION_EXTENSION,
    PRESS_RETURN,
    VERTICAL_PRESS_RETURN,
    PULL_RETURN,
    MULTI_AXIS_RETURN,
    FOREARM_ROTATION,
    UNOBSERVABLE_MANUAL;

    val sharedAlgorithmId: String
        get() = when (this) {
            FLEXION_EXTENSION -> "flexion-extension-pose-v4"
            PRESS_RETURN -> "press-return-family-v4"
            VERTICAL_PRESS_RETURN -> "vertical-press-return-family-v1"
            PULL_RETURN -> "pull-return-family-v4"
            MULTI_AXIS_RETURN -> "multi-axis-return-family-v4"
            FOREARM_ROTATION -> "forearm-rotation-family-v4"
            UNOBSERVABLE_MANUAL -> "manual-required-v3"
        }

    /** Accepted only to replay an explicitly frozen historical detector. */
    val legacyAlgorithmId: String?
        get() = when (this) {
            PRESS_RETURN, PULL_RETURN, MULTI_AXIS_RETURN, FOREARM_ROTATION -> sharedAlgorithmId.removeSuffix("v4") + "v3"
            else -> null
        }

    val displayLabel: String
        get() = when (this) {
            FLEXION_EXTENSION -> "굴곡-신전 공통 측정"
            PRESS_RETURN -> "프레스-복귀 공통 측정"
            VERTICAL_PRESS_RETURN -> "상하 프레스-복귀 측정"
            PULL_RETURN -> "당김-복귀 공통 측정"
            MULTI_AXIS_RETURN -> "다축 왕복 공통 측정"
            FOREARM_ROTATION -> "전완 회전 공통 측정"
            UNOBSERVABLE_MANUAL -> "수동 측정"
        }
}

data class ProfiledMotionRepEvent(
    val startTimestampNs: Long,
    val turnaroundTimestampNs: Long,
    val endTimestampNs: Long,
    val durationMs: Long,
    val peakMotionRate: Double,
    val motionDisplacement: Double,
    val trajectorySpread: Double,
    val confidence: Double,
)

/**
 * Deterministic, profile-driven repetition detector for non-rotation exercises.
 *
 * Flexion/extension uses departure from and return to the starting wrist tilt.
 * Other families retain their existing signed-signal cycle detector below.
 * Each exercise supplies a motion family; that family supplies shared thresholds. The detector only
 * claims a rep after two valid, opposite-direction lobes (out and back) and can
 * require multi-axis trajectory spread for arc/fly movements. It does not claim
 * joint position, shoulder fixation, load travel, or strict form.
 */
class ProfiledMotionRepDetector(
    private val profile: ExerciseTrackingProfile,
) {
    companion object {
        private const val GRAVITY_FILTER_ALPHA = 0.10
        private const val MAX_SAMPLE_GAP_MS = 450L
    }

    private val poseCounter = if (profile.family == MotionFamily.FLEXION_EXTENSION) ReturnToPoseRepDetector(profile.minimumRepMs) else null
    private val verticalCounter = if (profile.family == MotionFamily.VERTICAL_PRESS_RETURN) VerticalPressRepDetector(profile) else null
    private val legacyLobes = profile.id.substringBefore('+') == profile.family.legacyAlgorithmId

    private var lastTimestampNs = 0L
    private var gravityInitialized = false
    private val gravity = DoubleArray(3)
    private var lockedAxis = -1
    private var direction = 0
    private var lobeStartNs = 0L
    private var lobeDisplacement = 0.0
    private var lobePeakRate = 0.0
    private var lobeMainEnergy = 0.0
    private var lobeSecondaryEnergy = 0.0
    private var pendingStartNs = 0L
    private var pendingTurnaroundNs = 0L
    private var pendingPeakRate = 0.0
    private var pendingDisplacement = 0.0
    private var pendingSpread = 0.0
    private var pendingDirection = 0
    private var lastCommittedEndNs = Long.MIN_VALUE
    private var refractoryUntilNs = 0L

    // Pose counting previously allowed long top holds without a maximum duration.
    // Bound set-end grace at 12s, preserving slow returns; this policy still needs Watch8 validation.
    val maximumCycleNs: Long get() = verticalCounter?.maximumCycleNs ?: if (poseCounter != null) 12_000_000_000L else profile.maximumRepMs * 1_000_000L

    val candidateStartNs: Long? get() = verticalCounter?.candidateStartNs ?: poseCounter?.candidateStartNs ?: pendingStartNs.takeIf { it > 0L }
        ?: lobeStartNs.takeIf { it > 0L && lobeDisplacement >= profile.minimumLobeDisplacement }

    fun add(sample: SensorSample): ProfiledMotionRepEvent? {
        verticalCounter?.let { return it.add(sample) }
        poseCounter?.let { return it.add(sample) }
        if (sample.sensorAccuracy == 0) { resetAll(); lastTimestampNs = 0L; return null }
        val timestampNs = sample.timestampNs
        if (lastTimestampNs > 0L && timestampNs <= lastTimestampNs) return null
        val dtMs = if (lastTimestampNs > 0L) {
            ((timestampNs - lastTimestampNs) / 1_000_000L).coerceAtLeast(0L)
        } else {
            0L
        }
        lastTimestampNs = timestampNs
        if (dtMs > MAX_SAMPLE_GAP_MS) resetAll()

        val vector = featureVector(sample)
        val candidateAxis = vector.indices.maxByOrNull { abs(vector[it]) } ?: return null
        if (lockedAxis < 0 && abs(vector[candidateAxis]) >= profile.minimumFeatureRate) {
            lockedAxis = candidateAxis
        }
        if (lockedAxis < 0) return null

        val projected = vector[lockedAxis]
        val magnitude = abs(projected)
        if (timestampNs < refractoryUntilNs) return null

        if (magnitude < profile.minimumFeatureRate) {
            return if (direction != 0) closeLobe(timestampNs) else null
        }

        val nextDirection = if (projected >= 0.0) 1 else -1
        if (direction == 0) {
            startLobe(timestampNs, nextDirection, magnitude)
            return null
        }

        if (nextDirection != direction) {
            val completed = closeLobe(timestampNs)
            if (lockedAxis < 0) lockedAxis = candidateAxis
            startLobe(timestampNs, nextDirection, magnitude)
            return completed
        }

        if (dtMs in 1..MAX_SAMPLE_GAP_MS) {
            lobeDisplacement += magnitude * dtMs / 1_000.0
            lobeMainEnergy += magnitude * dtMs / 1_000.0
            lobeSecondaryEnergy += secondaryMagnitude(vector) * dtMs / 1_000.0
        }
        lobePeakRate = max(lobePeakRate, magnitude)
        return null
    }

    fun finish(): ProfiledMotionRepEvent? {
        if (verticalCounter != null) return null
        if (poseCounter != null) return null // A partial return never becomes a rep when the set ends.
        if (!legacyLobes) { resetAll(); return null } // Only observed quiet/reversal closes a new-engine lobe.
        if (direction == 0 || lastTimestampNs == 0L) return null
        val result = closeLobe(lastTimestampNs)
        resetActiveLobe()
        return result
    }

    /**
     * Ends the stationary preparation window without throwing away the gravity
     * estimate learned from the user's real starting pose.
     */
    fun beginCounting() {
        verticalCounter?.beginCounting()
        poseCounter?.reset()
        resetActiveLobe()
        pendingStartNs = 0L
        pendingTurnaroundNs = 0L
        pendingPeakRate = 0.0
        pendingDisplacement = 0.0
        pendingSpread = 0.0
        pendingDirection = 0
        lastCommittedEndNs = Long.MIN_VALUE
        refractoryUntilNs = 0L
        lockedAxis = -1
        lastTimestampNs = 0L
    }

    private fun featureVector(sample: SensorSample): DoubleArray {
        val acceleration = doubleArrayOf(
            sample.accelerationX.toDouble(),
            sample.accelerationY.toDouble(),
            sample.accelerationZ.toDouble(),
        )
        if (!gravityInitialized) {
            acceleration.copyInto(gravity)
            gravityInitialized = true
            return doubleArrayOf(0.0, 0.0, 0.0)
        }
        for (index in acceleration.indices) {
            gravity[index] = gravity[index] * (1.0 - GRAVITY_FILTER_ALPHA) +
                acceleration[index] * GRAVITY_FILTER_ALPHA
        }
        val dynamic = DoubleArray(3) { index -> acceleration[index] - gravity[index] }
        val gyro = doubleArrayOf(
            sample.gyroscopeX.toDouble(),
            sample.gyroscopeY.toDouble(),
            sample.gyroscopeZ.toDouble(),
        )
        return when (profile.signal) {
            TrackingSignal.ROTATION_ENERGY -> gyro
            TrackingSignal.DYNAMIC_ACCELERATION -> dynamic
            TrackingSignal.COMBINED_MOTION -> DoubleArray(3) { index ->
                gyro[index] + dynamic[index] * 0.12
            }
            TrackingSignal.FOREARM_ROTATION -> gyro
            TrackingSignal.UNOBSERVABLE_FROM_WRIST -> doubleArrayOf(0.0, 0.0, 0.0)
        }
    }

    private fun secondaryMagnitude(vector: DoubleArray): Double = sqrt(
        vector.indices
            .filter { it != lockedAxis }
            .sumOf { vector[it] * vector[it] },
    )

    private fun startLobe(timestampNs: Long, nextDirection: Int, magnitude: Double) {
        direction = nextDirection
        lobeStartNs = timestampNs
        lobeDisplacement = 0.0
        lobePeakRate = magnitude
        lobeMainEnergy = 0.0
        lobeSecondaryEnergy = 0.0
    }

    private fun closeLobe(endTimestampNs: Long): ProfiledMotionRepEvent? {
        if (direction == 0 || lobeStartNs == 0L) return null
        val durationMs = ((endTimestampNs - lobeStartNs) / 1_000_000L).coerceAtLeast(0L)
        val spread = lobeSecondaryEnergy / lobeMainEnergy.coerceAtLeast(1e-6)
        val valid = durationMs in profile.minimumLobeMs..profile.maximumLobeMs &&
            lobeDisplacement >= profile.minimumLobeDisplacement &&
            spread >= profile.minimumTrajectorySpread
        if (!valid) {
            resetAll()
            return null
        }

        val result = if (pendingStartNs == 0L || !legacyLobes && pendingDirection == direction) {
            pendingStartNs = lobeStartNs
            pendingTurnaroundNs = endTimestampNs
            pendingPeakRate = lobePeakRate
            pendingDisplacement = lobeDisplacement
            pendingSpread = spread
            pendingDirection = direction
            null
        } else {
            val repDurationMs = ((endTimestampNs - pendingStartNs) / 1_000_000L).coerceAtLeast(0L)
            val separated = lastCommittedEndNs == Long.MIN_VALUE ||
                (pendingStartNs - lastCommittedEndNs) / 1_000_000L >= profile.minimumSeparationMs
            val repValid = repDurationMs in profile.minimumRepMs..profile.maximumRepMs && separated
            val event = if (repValid) {
                ProfiledMotionRepEvent(
                    startTimestampNs = pendingStartNs,
                    turnaroundTimestampNs = pendingTurnaroundNs,
                    endTimestampNs = endTimestampNs,
                    durationMs = repDurationMs,
                    peakMotionRate = max(pendingPeakRate, lobePeakRate),
                    motionDisplacement = pendingDisplacement + lobeDisplacement,
                    trajectorySpread = (pendingSpread + spread) / 2.0,
                    confidence = confidence(repDurationMs, pendingDisplacement + lobeDisplacement, spread),
                )
            } else {
                null
            }
            if (event != null) {
                lastCommittedEndNs = endTimestampNs
                refractoryUntilNs = endTimestampNs + profile.minimumSeparationMs * 1_000_000L
            }
            pendingStartNs = 0L
            pendingTurnaroundNs = 0L
            pendingPeakRate = 0.0
            pendingDisplacement = 0.0
            pendingSpread = 0.0
            pendingDirection = 0
            event
        }
        resetActiveLobe()
        return result
    }

    private fun confidence(durationMs: Long, displacement: Double, spread: Double): Double {
        val targetDuration = (profile.minimumRepMs + profile.maximumRepMs) / 2.0
        val durationScore = (1.0 - abs(durationMs - targetDuration) / targetDuration).coerceIn(0.0, 1.0)
        val displacementScore = ((displacement / (profile.minimumLobeDisplacement * 2.0)) - 1.0)
            .coerceIn(0.0, 1.0)
        val spreadScore = if (profile.minimumTrajectorySpread <= 0.0) 1.0 else
            (spread / profile.minimumTrajectorySpread).coerceIn(0.0, 1.0)
        return (0.50 + durationScore * 0.20 + displacementScore * 0.20 + spreadScore * 0.10)
            .coerceIn(0.0, 1.0)
    }

    private fun resetActiveLobe() {
        direction = 0
        lobeStartNs = 0L
        lobeDisplacement = 0.0
        lobePeakRate = 0.0
        lobeMainEnergy = 0.0
        lobeSecondaryEnergy = 0.0
    }

    private fun resetAll() {
        resetActiveLobe()
        pendingStartNs = 0L
        pendingTurnaroundNs = 0L
        pendingPeakRate = 0.0
        pendingDisplacement = 0.0
        pendingSpread = 0.0
        pendingDirection = 0
        lockedAxis = -1
        refractoryUntilNs = 0L
    }
}
