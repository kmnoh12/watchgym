package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic motion only. It proves the signal/routing defect and the new detector's
 * shape; it is not a Watch8 accuracy claim. Real bench/pushdown captures remain the
 * acceptance test.
 */
class TranslationPressRepDetectorTest {
    private fun press(
        reps: Int,
        periodS: Double,
        strokeM: Double,
        wristGyroPeak: Double,
        warmupS: Double = 1.0,
        quietS: Double = 2.0,
        startAtBottom: Boolean = false,
    ): List<SensorSample> {
        val hz = 50.0
        val dt = 1.0 / hz
        val out = ArrayList<SensorSample>()
        val total = warmupS + reps * periodS + quietS
        val omega = 2.0 * PI / periodS
        var n = 0
        while (n * dt <= total) {
            val t = n * dt
            val moving = t >= warmupS && t < warmupS + reps * periodS
            val phase = if (!moving) 0.0 else 2.0 * PI * ((t - warmupS) / periodS)
            val offset = if (startAtBottom) PI else 0.0
            val acceleration = if (!moving) 0.0 else strokeM / 2.0 * omega * omega * cos(phase + offset)
            val gyro = if (!moving) 0.0 else wristGyroPeak * sin(phase + offset)
            out += SensorSample(
                timestampNs = (n * dt * 1e9).toLong() + 1_000_000_000L,
                accelerationX = (0.01 * sin(3.0 * t)).toFloat(),
                accelerationY = (0.02 * sin(37.0 * t)).toFloat(),
                accelerationZ = (9.80665 - acceleration).toFloat(),
                gyroscopeX = gyro.toFloat(),
                gyroscopeY = (0.01 * cos(5.0 * t)).toFloat(),
                gyroscopeZ = 0f,
                sensorAccuracy = 3,
            )
            n++
        }
        return out
    }

    private fun count(exerciseId: String, samples: List<SensorSample>): Int {
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve(exerciseId))
        detector.beginCounting()
        var count = samples.count { detector.add(it) != null }
        count += detector.finishAll().size
        return count
    }

    @Test fun benchCountsEveryRepAtNormalAndSlowTempos() {
        for (period in listOf(1.5, 2.5, 4.0)) {
            assertEquals(10, count("flat_bench", press(10, period, 0.30, 0.10)), "period=$period")
        }
    }

    @Test fun smithInclineAndDumbbellBenchUseTheSameTranslationCycle() {
        assertEquals(10, count("smith_incline_bench_press", press(10, 2.5, 0.28, 0.08)))
        // A 30-degree Smith incline carries about 0.87 of the bar travel vertically.
        assertEquals(10, count("smith_incline_bench_press", press(10, 2.5, 0.22, 0.08)))
        assertEquals(10, count("incline_bench", press(10, 2.5, 0.28, 0.08)))
        assertEquals(10, count("dumbbell_bench", press(10, 2.5, 0.30, 0.12)))
    }

    @Test fun aFiveCentimetreWristBobIsNotARepetition() {
        assertEquals(0, count("flat_bench", press(10, 1.0, 0.05, 1.2)))
        assertEquals(0, count("cable_pushdown", press(10, 1.0, 0.05, 1.2)))
    }

    @Test fun fixedWristPushdownCountsOnTranslationWhileOldPressFamilyMissedIt() {
        val samples = press(10, 2.5, 0.45, 0.10)
        assertEquals(10, count("cable_pushdown", samples))
        val current = ExerciseTrackingProfiles.resolve("cable_pushdown")
        val old = current.copy(
            family = MotionFamily.PRESS_RETURN,
            id = MotionFamily.PRESS_RETURN.sharedAlgorithmId,
            signal = TrackingSignal.COMBINED_MOTION,
            minimumFeatureRate = 0.12, minimumLobeDisplacement = 0.07,
            minimumLobeMs = 110, maximumLobeMs = 1_800,
        )
        val detector = ProfiledMotionRepDetector(old)
        detector.beginCounting()
        assertEquals(0, samples.count { detector.add(it) != null } + detector.finishAll().size)
    }

    @Test fun aSetStartingAtTheBottomStillCountsTheFirstRepetition() {
        assertEquals(10, count("flat_bench", press(10, 2.5, 0.30, 0.10, startAtBottom = true)))
    }

    @Test fun anUnfinishedReturnIsNotFabricatedAtSetEnd() {
        val samples = press(1, 2.5, 0.30, 0.10, quietS = 0.0).let { all ->
            val end = all.first().timestampNs + 1_000_000_000L + 1_250_000_000L
            all.filter { it.timestampNs <= end }
        }
        assertEquals(0, count("flat_bench", samples))
    }

    @Test fun translationProfileKeepsItsOwnAlgorithmIdAndBounds() {
        val profile = ExerciseTrackingProfiles.resolve("flat_bench")
        assertEquals(MotionFamily.TRANSLATION_PRESS_RETURN, profile.family)
        assertEquals(MotionFamily.TRANSLATION_PRESS_RETURN.sharedAlgorithmId, profile.id)
        assertTrue(profile.maximumLobeMs >= 4_000L)
        assertTrue(TranslationPressRepDetector.MIN_TOTAL_DISPLACEMENT >= 0.20)
    }
}
