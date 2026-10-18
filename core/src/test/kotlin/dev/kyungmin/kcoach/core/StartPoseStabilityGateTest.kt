package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartPoseStabilityGateTest {
    private val config = StartPoseGateConfig(
        stableDurationMs = 2_000L,
        minCandidateSamples = 3,
        maxGyroMagnitude = 0.5,
        maxGravityMagnitudeError = 0.5,
        maxOrientationDrift = 0.20,
        maxSampleGapMs = 750L,
    )

    @Test
    fun requiresAContinuousStableCandidateForTwoSeconds() {
        val gate = StartPoseStabilityGate(config)
        var timestamp = 1_000_000_000L
        timestamp = arm(gate, timestamp)
        var observation = gate.observe(sample(timestamp, gyro = 0.05f))
        assertFalse(observation.ready)

        repeat(20) {
            timestamp += 100_000_000L
            observation = gate.observe(sample(timestamp, gyro = 0.05f))
        }

        assertTrue(observation.candidate)
        assertTrue(observation.stable)
        assertTrue(observation.stableDurationMs >= 2_000L)
        assertTrue(observation.ready)
    }

    @Test
    fun invalidSampleResetsTheStabilityTimer() {
        val gate = StartPoseStabilityGate(config)
        var timestamp = 1_000_000_000L
        timestamp = arm(gate, timestamp)
        gate.observe(sample(timestamp, gyro = 0.05f))
        repeat(18) {
            timestamp += 100_000_000L
            gate.observe(sample(timestamp, gyro = 0.05f))
        }

        timestamp += 100_000_000L
        val invalid = gate.observe(sample(timestamp, gyro = 1.0f))
        assertFalse(invalid.ready)
        assertFalse(invalid.stable)

        timestamp += 100_000_000L
        val afterBreak = gate.observe(sample(timestamp, gyro = 0.05f))
        assertFalse(afterBreak.ready)
        assertTrue(afterBreak.stableDurationMs < 2_000L)
    }

    @Test
    fun sampleGapAndOrientationChangeCannotCarryOldStability() {
        val gate = StartPoseStabilityGate(config)
        var timestamp = 1_000_000_000L
        timestamp = arm(gate, timestamp)
        gate.observe(sample(timestamp, gyro = 0.05f))
        repeat(10) {
            timestamp += 100_000_000L
            gate.observe(sample(timestamp, gyro = 0.05f))
        }

        timestamp += 800_000_000L
        val afterGap = gate.observe(sample(timestamp, gyro = 0.05f))
        assertFalse(afterGap.ready)
        assertTrue(afterGap.stableDurationMs == 0L)

        timestamp += 100_000_000L
        val changedOrientation = gate.observe(sample(timestamp, gyro = 0.05f, ax = 0f, az = 9.8f))
        assertFalse(changedOrientation.ready)
        assertTrue(changedOrientation.stableDurationMs < 2_000L)
    }

    @Test
    fun `a stationary wrist does not start until the user moves into position`() {
        val gate = StartPoseStabilityGate(config)
        var timestamp = 1_000_000_000L
        var observation = gate.observe(sample(timestamp, gyro = 0.05f))
        repeat(30) {
            timestamp += 100_000_000L
            observation = gate.observe(sample(timestamp, gyro = 0.05f))
        }
        assertFalse(observation.armed)
        assertFalse(observation.ready)

        timestamp = arm(gate, timestamp)
        repeat(21) {
            timestamp += 100_000_000L
            observation = gate.observe(sample(timestamp, gyro = 0.05f))
        }
        assertTrue(observation.armed)
        assertTrue(observation.ready)
    }

    @Test
    fun aWorkoutStartNeedsTwoAndAHalfStillSecondsAndAnyMovementRestartsTheCount() {
        val gate = StartPoseStabilityGate(config.copy(stableDurationMs = StartPoseGateConfig.WORKOUT_HOLD_MS, requireSetupMotion = false))
        var timestamp = 1_000_000_000L
        var observation = gate.observe(sample(timestamp, gyro = 0.05f))
        repeat(23) { timestamp += 100_000_000L; observation = gate.observe(sample(timestamp, gyro = 0.05f)) }
        assertFalse(observation.ready, "2.3 s of stillness is not enough")
        timestamp += 100_000_000L; observation = gate.observe(sample(timestamp, gyro = 1.5f))
        assertFalse(observation.stable, "a shake restarts the hold")
        repeat(23) { timestamp += 100_000_000L; observation = gate.observe(sample(timestamp, gyro = 0.05f)) }
        assertFalse(observation.ready, "2.3 s after the shake is still not enough")
        repeat(6) { timestamp += 100_000_000L; observation = gate.observe(sample(timestamp, gyro = 0.05f)) }
        assertTrue(observation.ready)
    }

    private fun arm(gate: StartPoseStabilityGate, startTimestamp: Long): Long {
        var timestamp = startTimestamp
        repeat(2) {
            timestamp += 50_000_000L
            gate.observe(sample(timestamp, gyro = 1.2f))
        }
        return timestamp + 50_000_000L
    }

    private fun sample(
        timestamp: Long,
        gyro: Float,
        ax: Float = 9.8f,
        az: Float = 0f,
    ) = SensorSample(
        timestampNs = timestamp,
        accelerationX = ax,
        accelerationY = 0f,
        accelerationZ = az,
        gyroscopeX = gyro,
        gyroscopeY = 0f,
        gyroscopeZ = 0f,
    )
}
