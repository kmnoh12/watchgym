package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stability check for a deliberately selected preparation task, optionally constrained to an expected direction.
 *
 * A gravity target can distinguish some palm-up/down variants in a known wearing configuration.
 * It cannot prove elbow position or rotation about gravity. Without a target this is only a stability check,
 * and must not be installed as a universal exercise posture gate.
 */
data class StartPoseGateConfig(
    val stableDurationMs: Long = 2_000L,
    val minCandidateSamples: Int = 3,
    val maxGyroMagnitude: Double = 0.90,
    val maxGravityMagnitudeError: Double = 3.50,
    val maxOrientationDrift: Double = 0.32,
    val maxSampleGapMs: Long = 750L,
    val requireSetupMotion: Boolean = true,
    val minSetupMotionGyro: Double = 1.00,
    val minSetupMotionSamples: Int = 2,
    val expectedGravity: StartWristGravityTarget? = null,
    val maxAccelerationAgeMs: Long = 100L,
) {
    companion object {
        /** A workout set waits for the wrist to hold still this long before the start buzz and counting. */
        const val WORKOUT_HOLD_MS = 2_500L

        fun forProfile(profile: ExerciseTrackingProfile): StartPoseGateConfig = when (profile.signal) {
            TrackingSignal.FOREARM_ROTATION -> StartPoseGateConfig(
                maxGyroMagnitude = 0.75,
                minSetupMotionGyro = 0.90,
            )
            else -> StartPoseGateConfig()
        }
    }
}

data class StartPoseGateObservation(
    val armed: Boolean,
    val candidate: Boolean,
    val stable: Boolean,
    val stableDurationMs: Long,
    val ready: Boolean,
)

class StartPoseStabilityGate(
    private val config: StartPoseGateConfig = StartPoseGateConfig(),
) {
    private var stableSinceNs = 0L
    private var lastTimestampNs = 0L
    private var candidateSamples = 0
    private var referenceX = 0.0
    private var referenceY = 0.0
    private var referenceZ = 0.0
    private var hasReference = false
    private var armed = !config.requireSetupMotion
    private var setupMotionSamples = 0

    fun reset() {
        stableSinceNs = 0L
        lastTimestampNs = 0L
        candidateSamples = 0
        hasReference = false
        armed = !config.requireSetupMotion
        setupMotionSamples = 0
    }

    fun observe(sample: SensorSample, accelerationTimestampNs: Long? = sample.timestampNs,
        accelerationAccuracy: Int? = sample.sensorAccuracy): StartPoseGateObservation {
        val timestampNs = sample.timestampNs
        if (timestampNs <= 0L || (lastTimestampNs > 0L && timestampNs <= lastTimestampNs)) {
            reset()
            return observation(candidate = false, stable = false, stableDurationMs = 0L)
        }
        if (lastTimestampNs > 0L && timestampNs - lastTimestampNs > config.maxSampleGapMs * 1_000_000L) {
            reset()
        }
        lastTimestampNs = timestampNs

        if (accelerationTimestampNs == null || accelerationTimestampNs <= 0L ||
            accelerationTimestampNs > timestampNs || timestampNs - accelerationTimestampNs > config.maxAccelerationAgeMs * 1_000_000L ||
            sample.sensorAccuracy == 0 || accelerationAccuracy == 0 ||
            config.expectedGravity != null && (sample.sensorAccuracy !in 1..3 || accelerationAccuracy !in 1..3)) {
            resetStability(timestampNs)
            return observation(candidate = false, stable = false, stableDurationMs = 0L)
        }

        val ax = sample.accelerationX.toDouble()
        val ay = sample.accelerationY.toDouble()
        val az = sample.accelerationZ.toDouble()
        val gravityMagnitude = sqrt(ax * ax + ay * ay + az * az)
        val gyroMagnitude = sqrt(
            sample.gyroscopeX.toDouble() * sample.gyroscopeX +
                sample.gyroscopeY.toDouble() * sample.gyroscopeY +
                sample.gyroscopeZ.toDouble() * sample.gyroscopeZ,
        )
        if (!armed) {
            setupMotionSamples = if (gyroMagnitude.isFinite() && gyroMagnitude >= config.minSetupMotionGyro) {
                setupMotionSamples + 1
            } else {
                0
            }
            if (setupMotionSamples >= config.minSetupMotionSamples) {
                armed = true
                resetStability(timestampNs)
            }
            return observation(candidate = false, stable = false, stableDurationMs = 0L)
        }
        val candidate = gravityMagnitude.isFinite() &&
            abs(gravityMagnitude - STANDARD_GRAVITY) <= config.maxGravityMagnitudeError &&
            gyroMagnitude.isFinite() && gyroMagnitude <= config.maxGyroMagnitude
        if (!candidate || gravityMagnitude <= 0.001) {
            resetStability(timestampNs)
            return observation(candidate = false, stable = false, stableDurationMs = 0L)
        }

        val nx = ax / gravityMagnitude
        val ny = ay / gravityMagnitude
        val nz = az / gravityMagnitude
        if (config.expectedGravity?.matches(nx, ny, nz) == false) {
            resetStability(timestampNs)
            return observation(candidate = false, stable = false, stableDurationMs = 0L)
        }
        if (!hasReference) {
            referenceX = nx
            referenceY = ny
            referenceZ = nz
            hasReference = true
        }
        val drift = sqrt(
            (nx - referenceX) * (nx - referenceX) +
                (ny - referenceY) * (ny - referenceY) +
                (nz - referenceZ) * (nz - referenceZ),
        )
        val stable = drift <= config.maxOrientationDrift
        if (!stable) {
            resetStability(timestampNs)
            referenceX = nx
            referenceY = ny
            referenceZ = nz
            hasReference = true
            return observation(candidate = true, stable = false, stableDurationMs = 0L)
        }

        if (stableSinceNs == 0L) stableSinceNs = timestampNs
        candidateSamples += 1
        val stableDurationMs = ((timestampNs - stableSinceNs) / 1_000_000L).coerceAtLeast(0L)
        return observation(
            candidate = true,
            stable = true,
            stableDurationMs = stableDurationMs,
        )
    }

    private fun resetStability(timestampNs: Long) {
        // An invalid sample or orientation break starts no stability interval.
        // The next valid candidate must establish a fresh reference and hold it
        // continuously for the full configured duration.
        stableSinceNs = 0L
        candidateSamples = 0
        hasReference = false
    }

    private fun observation(candidate: Boolean, stable: Boolean, stableDurationMs: Long): StartPoseGateObservation =
        StartPoseGateObservation(
            armed = armed,
            candidate = candidate,
            stable = stable,
            stableDurationMs = stableDurationMs,
            ready = stable && candidateSamples >= config.minCandidateSamples && stableDurationMs >= config.stableDurationMs,
        )

    private companion object {
        const val STANDARD_GRAVITY = 9.80665
    }
}
