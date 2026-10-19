package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class StartWristOrientationTest {
    private fun gate() = StartPoseStabilityGate(StartPoseGateConfig(
        requireSetupMotion = false,
        expectedGravity = StartWristGravityTarget(0.0, 0.0, -1.0, 0.3),
        maxGyroMagnitude = 0.5,
        maxGravityMagnitudeError = 0.5,
    ))
    private fun sample(ms: Long, z: Float = -9.80665f) = SensorSample(ms * 1_000_000L, 0f, 0f, z, 0f, 0f, 0f, sensorAccuracy = 3)

    @Test fun oppositeQuietDirectionNeverPassesAndTheCorrectDirectionNeedsItsOwnFullHold() {
        val gate = gate()
        for (ms in 1000L..5000L step 100L) assertFalse(gate.observe(sample(ms, 9.80665f)).ready)
        assertFalse(gate.observe(sample(5100L)).ready)
        for (ms in 5200L..7000L step 100L) assertFalse(gate.observe(sample(ms)).ready)
        assertTrue(gate.observe(sample(7100L)).ready)
    }

    @Test fun alreadyPreparedWristCanPassWithoutAnArtificialSetupGesture() {
        val gate = gate()
        for (ms in 1000L..2900L step 100L) assertFalse(gate.observe(sample(ms)).ready)
        assertTrue(gate.observe(sample(3000L)).ready)
    }

    @Test fun leavingTheTargetResetsTheWholeContinuousInterval() {
        val gate = gate()
        for (ms in 1000L..2900L step 100L) gate.observe(sample(ms))
        assertFalse(gate.observe(sample(3000L, 9.80665f)).candidate)
        for (ms in 3100L..5000L step 100L) assertFalse(gate.observe(sample(ms)).ready)
        assertTrue(gate.observe(sample(5100L)).ready)
    }

    @Test fun missingStaleFutureAndUnreliableSamplesCannotCompleteAHold() {
        for (case in 0..7) {
            val gate = gate()
            for (ms in 1000L..2900L step 100L) gate.observe(sample(ms))
            val observation = when (case) {
                0 -> gate.observe(sample(3000L), null)
                1 -> gate.observe(sample(3000L), 2_800_000_000L)
                2 -> gate.observe(sample(3000L), 3_001_000_000L)
                3 -> gate.observe(sample(3000L).copy(sensorAccuracy = 0))
                4 -> gate.observe(sample(3000L), 3_000_000_000L, 0)
                5 -> gate.observe(sample(3000L), 3_000_000_000L, null)
                6 -> gate.observe(sample(3000L).copy(sensorAccuracy = null))
                else -> gate.observe(sample(3000L).copy(sensorAccuracy = 4))
            }
            assertFalse(observation.ready)
            assertFalse(observation.candidate)
            for (ms in 3100L..5000L step 100L) assertFalse(gate.observe(sample(ms)).ready)
            assertTrue(gate.observe(sample(5100L)).ready)
        }
    }

    @Test fun timeReversalAndLongGapsCannotCarryAPreviousHold() {
        for (boundary in listOf(2800L, 3700L)) {
            val gate = gate()
            for (ms in 1000L..2900L step 100L) gate.observe(sample(ms))
            assertFalse(gate.observe(sample(boundary)).ready)
            // A valid sample after a gap starts a new hold immediately; a reversed sample is rejected.
            val holdStart = if (boundary > 2900L) boundary else 3800L
            for (ms in 3800L until holdStart + 2000L step 100L) assertFalse(gate.observe(sample(ms)).ready)
            assertTrue(gate.observe(sample(holdStart + 2000L)).ready)
        }
    }

    @Test fun targetsCannotSilentlyAcceptBothOppositeDirectionsOrNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> { StartWristGravityTarget(0.0, 0.0, 1.0, Math.PI / 2) }
        assertFailsWith<IllegalArgumentException> { StartWristGravityTarget(0.0, 0.0, 9.8, 0.3) }
        assertFailsWith<IllegalArgumentException> { StartWristGravityTarget(0.0, 0.0, Double.NaN, 0.3) }
    }

    @Test fun allCatalogExercisesStayCandidatesDeferredOrManualRatherThanAutomaticallyConfirmed() {
        val groups = ExerciseMotionCatalog.explicitBindings.groupingBy { StartWristOrientationPolicy.candidate(it.exerciseId) }.eachCount()
        assertEquals(203, ExerciseMotionCatalog.explicitBindings.size)
        assertEquals(157, groups[StartWristCandidate.DEFERRED])
        assertEquals(43, groups[StartWristCandidate.MANUAL])
        assertEquals(1, groups[StartWristCandidate.HORIZONTAL_PALM_UP])
        assertEquals(1, groups[StartWristCandidate.HORIZONTAL_KNUCKLES_UP])
        assertEquals(1, groups[StartWristCandidate.ARM_DOWN])
        assertEquals("UNCONFIRMED", StartWristOrientationPolicy.uncheckedStartStatus("cable_external_rot"))
        assertEquals("DEFERRED", StartWristOrientationPolicy.uncheckedStartStatus("lat_pull_down"))
        assertEquals("MANUAL", StartWristOrientationPolicy.uncheckedStartStatus("pushup"))
    }
}
