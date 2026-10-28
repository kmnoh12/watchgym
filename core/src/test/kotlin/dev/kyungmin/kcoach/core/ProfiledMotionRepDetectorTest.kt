package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfiledMotionRepDetectorTest {
    @Test fun explicitlyFrozenV3KeepsHistoricalFinishSemanticsForReanalysis() {
        val profile = testProfile(MotionFamily.PULL_RETURN, TrackingSignal.ROTATION_ENERGY)
            .copy(id = requireNotNull(MotionFamily.PULL_RETURN.legacyAlgorithmId))
        val detector = ProfiledMotionRepDetector(profile)
        var t = 1_000_000_000L
        for (rate in listOf(1.25f, -1.25f)) repeat(10) {
            detector.add(sample(t, rate, 0f)); t += 50_000_000L
        }
        assertTrue(detector.finish() != null)
    }
    @Test fun endingWhileReturnIsStillMovingDoesNotInventItsCompletion() {
        val detector = ProfiledMotionRepDetector(testProfile(MotionFamily.PULL_RETURN, TrackingSignal.ROTATION_ENERGY))
        var t = 1_000_000_000L
        var count = 0
        for (rate in listOf(1.25f, -1.25f)) repeat(10) {
            if (detector.add(sample(t, rate, 0f)) != null) count++
            t += 50_000_000L
        }
        assertEquals(0, count)
        assertEquals(null, detector.finish())
    }

    @Test fun twoSameDirectionMovementsSeparatedByAStopAreNotAnOutAndBackRep() {
        val detector = ProfiledMotionRepDetector(testProfile(MotionFamily.PULL_RETURN, TrackingSignal.ROTATION_ENERGY))
        var t = 1_000_000_000L
        var count = 0
        for (rate in listOf(1.25f, 0f, 1.25f, 0f)) repeat(10) {
            if (detector.add(sample(t, rate, 0f)) != null) count++
            t += 50_000_000L
        }
        assertEquals(0, count)
        assertEquals(null, detector.finish())
    }
    @Test fun knownUnreliableSignedLobesCannotCompleteARep() {
        val detector = ProfiledMotionRepDetector(testProfile(MotionFamily.PULL_RETURN, TrackingSignal.ROTATION_ENERGY))
        var t = 1_000_000_000L
        var count = 0
        repeat(3) {
            for (rate in listOf(1.25f, 0f, -1.25f, 0f)) repeat(20) {
                if (detector.add(sample(t, rate, 0f).copy(sensorAccuracy = 0)) != null) count++
                t += 50_000_000L
            }
        }
        assertEquals(0, count)
        assertTrue(detector.finish() == null)
    }
    @Test
    fun countsOnlyCompletedFlexionExtensionCycles() {
        val detector = ProfiledMotionRepDetector(testProfile(MotionFamily.FLEXION_EXTENSION, TrackingSignal.ROTATION_ENERGY))
        val count = CurlCycleRegressionTest.curlSamples(3, 1.0, 1.0).count { detector.add(it) != null }
        assertEquals(3, count)
    }

    @Test
    fun gyroPolarityAlternationWithoutLeavingStartingTiltIsNotACurl() {
        val profile = testProfile(MotionFamily.FLEXION_EXTENSION, TrackingSignal.ROTATION_ENERGY)
        val detector = ProfiledMotionRepDetector(profile)
        var timestamp = 1_000_000_000L
        var events = 0
        repeat(3) {
            repeat(20) {
                if (detector.add(sample(timestamp, 1.25f, 0f)) != null) events += 1
                timestamp += 50_000_000L
            }
            repeat(10) {
                if (detector.add(sample(timestamp, 0f, 0f)) != null) events += 1
                timestamp += 50_000_000L
            }
            repeat(20) {
                if (detector.add(sample(timestamp, -1.25f, 0f)) != null) events += 1
                timestamp += 50_000_000L
            }
            repeat(10) {
                if (detector.add(sample(timestamp, 0f, 0f)) != null) events += 1
                timestamp += 50_000_000L
            }
        }
        if (detector.finish() != null) events += 1
        assertEquals(0, events)
    }

    @Test
    fun multiAxisProfileRequiresObservedAxisSpread() {
        val profile = testProfile(MotionFamily.MULTI_AXIS_RETURN, TrackingSignal.ROTATION_ENERGY)
            .copy(minimumTrajectorySpread = 0.08)
        val detector = ProfiledMotionRepDetector(profile)
        var timestamp = 1_000_000_000L
        var events = 0
        repeat(18) {
            if (detector.add(sample(timestamp, 1.0f, 0.25f)) != null) events += 1
            timestamp += 50_000_000L
        }
        repeat(8) {
            detector.add(sample(timestamp, 0f, 0f))
            timestamp += 50_000_000L
        }
        repeat(18) {
            if (detector.add(sample(timestamp, -1.0f, -0.25f)) != null) events += 1
            timestamp += 50_000_000L
        }
        if (detector.add(sample(timestamp, 0f, 0f)) != null) events += 1
        assertTrue(detector.finish() != null || events == 1)

        val shaking = ProfiledMotionRepDetector(profile)
        timestamp = 1_000_000_000L
        repeat(18) {
            shaking.add(sample(timestamp, 1.0f, 0f))
            timestamp += 50_000_000L
        }
        repeat(8) {
            shaking.add(sample(timestamp, 0f, 0f))
            timestamp += 50_000_000L
        }
        repeat(18) {
            shaking.add(sample(timestamp, -1.0f, 0f))
            timestamp += 50_000_000L
        }
        assertEquals(null, shaking.finish())
    }

    @Test
    fun profileCatalogHasOneExplicitAlgorithmForEveryExerciseAndManualLowerBody() {
        val ids = ExerciseCapabilityMatrix.standardExerciseIds()
        assertEquals(229, ids.size)
        val profiles = ids.map { ExerciseTrackingProfiles.resolve(it) }
        assertEquals(229, profiles.map { it.exerciseId }.toSet().size)
        assertEquals(161, profiles.count { it.family != MotionFamily.UNOBSERVABLE_MANUAL })
        assertEquals(43, profiles.count { it.mode == TrackingMode.MANUAL_REQUIRED })
        assertTrue(profiles.filter { it.mode != TrackingMode.MANUAL_REQUIRED }.all {
            it.id == it.family.sharedAlgorithmId
        })
    }

    private fun testProfile(family: MotionFamily, signal: TrackingSignal) = ExerciseTrackingProfile(
        exerciseId = "test",
        id = "test-profile-v1",
        mode = TrackingMode.WRIST_IMU_CAUTION,
        signal = signal,
        minimumRepMs = 500L,
        maximumRepMs = 5_000L,
        minimumSeparationMs = 400L,
        thresholdFraction = 0.0,
        minimumRelativeRom = 0.1,
        label = "test",
        capabilityTier = CapabilityTier.A_WRIST_DIRECT_AUTO,
        countMode = CountMode.AUTO,
        family = family,
        minimumFeatureRate = 0.20,
        minimumLobeDisplacement = 0.20,
        minimumLobeMs = 120L,
        maximumLobeMs = 1_800L,
    )

    private fun sample(timestampNs: Long, gyroX: Float, gyroY: Float): SensorSample = SensorSample(
        timestampNs = timestampNs,
        accelerationX = 0f,
        accelerationY = 0f,
        accelerationZ = 9.80665f,
        gyroscopeX = gyroX,
        gyroscopeY = gyroY,
        gyroscopeZ = 0f,
    )
}
