package dev.kyungmin.kcoach.core

import kotlin.test.*
import org.junit.jupiter.api.Test

class SetEndGateTest {
    private fun ns(ms: Long) = ms * 1_000_000
    @Test fun tenThenUnrelatedLateMovementStaysTen() {
        val gate = SetEndGate(); var count = 0
        repeat(10) { if (gate.rep(ns(it * 2000L))) count++ }
        assertFalse(gate.shouldEnd(ns(20_999), null, ns(6000)))
        assertTrue(gate.shouldEnd(ns(21_000), null, ns(6000)))
        if (gate.rep(ns(22_000))) count++
        assertEquals(10, count)
    }
    @Test fun continuousTwelveIsNotCappedAtTen() {
        val gate = SetEndGate(); var count = 0
        repeat(12) {
            assertFalse(gate.shouldEnd(ns(it * 2000L), null, ns(6000)))
            if (gate.rep(ns(it * 2000L))) count++
        }
        assertEquals(12, count)
        assertTrue(gate.shouldEnd(ns(25_000), null, ns(6000)))
    }
    @Test fun qualifiedSlowReturnGetsBoundedTime() {
        val gate = SetEndGate(); gate.rep(ns(1000))
        assertFalse(gate.shouldEnd(ns(4000), ns(2000), ns(6000)))
        assertFalse(gate.shouldEnd(ns(6500), ns(2000), ns(6000)))
        assertTrue(gate.rep(ns(7000)))
        assertTrue(gate.shouldEnd(ns(10_000), null, ns(6000)))
    }
    @Test fun partialHoldCannotKeepASetAliveForever() {
        val gate = SetEndGate(); gate.rep(ns(1000))
        assertTrue(gate.shouldEnd(ns(8000), ns(2000), ns(6000)))
    }
    @Test fun lateDepartureDoesNotReopenSetAndNoRepDoesNotAutoFinish() {
        val gate = SetEndGate()
        assertFalse(gate.shouldEnd(ns(99999), null, ns(6000)))
        gate.rep(ns(1000))
        assertTrue(gate.shouldEnd(ns(4200), ns(4100), ns(6000)))
        gate.reset(); assertTrue(gate.rep(ns(5000)))
    }
    private fun replay(samples: List<SensorSample>): Int {
        val profile = ExerciseTrackingProfiles.resolve("barbell_biceps_curl")
        val detector = ProfiledMotionRepDetector(profile)
        detector.beginCounting()
        val gate = SetEndGate()
        var count = 0
        for (sample in samples) {
            if (gate.shouldEnd(sample.timestampNs, detector.candidateStartNs, detector.maximumCycleNs)) break
            val rep = detector.add(sample)
            if (rep != null && gate.rep(rep.endTimestampNs)) count++
        }
        return count
    }
    @Test fun actualDetectorTenThenLateWristCycleIsClosedBeforeItCanCount() {
        val ten = CurlCycleRegressionTest.curlSamples(10, 1.0, 1.0)
        val end = ten.last().timestampNs
        val quiet = (1..160).map { SensorSample(end + it * 20_000_000L, 0f, 0f, 9.8f, 0f, 0f, 0f) }
        val later = CurlCycleRegressionTest.curlSamples(1, 1.0, 1.0).map {
            it.copy(timestampNs = it.timestampNs + quiet.last().timestampNs)
        }
        assertEquals(10, replay(ten + quiet + later))
    }
    @Test fun actualDetectorContinuousTwelveIncludingSlowReturns() {
        assertEquals(12, replay(CurlCycleRegressionTest.curlSamples(12, 1.0, 1.0)))
        assertEquals(12, replay(CurlCycleRegressionTest.curlSamples(12, 1.0, 3.0)))
        assertEquals(12, replay(CurlCycleRegressionTest.curlSamples(12, 1.0, 3.0, topHoldSeconds = 2.5)))
    }

    @Test fun rejectedRepetitionsStillKeepTheSetOpenWhileTheWristIsWorking() {
        val gate = SetEndGate(); gate.rep(ns(1000))
        // The detector missed every rep in this window, but real motion continued.
        assertFalse(gate.shouldEnd(ns(9000), null, ns(6000), SetEndGate.waitNs(3, 12, ns(3000)), ns(8500)))
        assertFalse(gate.shouldEnd(ns(12_000), null, ns(6000), SetEndGate.waitNs(3, 12, ns(3000)), ns(11_500)))
        // Motion stops at 14 s; the same seven-second pause still ends the set afterwards.
        assertFalse(gate.shouldEnd(ns(20_900), null, ns(6000), SetEndGate.waitNs(3, 12, ns(3000)), ns(14_000)))
        assertTrue(gate.shouldEnd(ns(21_000), null, ns(6000), SetEndGate.waitNs(3, 12, ns(3000)), ns(14_000)))
    }

    @Test fun aStaleMotionTimestampCannotKeepAnAbandonedSetOpen() {
        val gate = SetEndGate(); gate.rep(ns(1000))
        assertTrue(gate.shouldEnd(ns(9000), null, ns(6000), SetEndGate.waitNs(3, 12, ns(3000)), ns(1200)))
        assertTrue(gate.shouldEnd(ns(9000), null, ns(6000), SetEndGate.waitNs(3, 12, ns(3000)), null))
    }

    @Test fun aPauseBeforeTheTargetWaitsSevenSecondsAndAfterItThreeAsBefore() {
        val base = 3_000_000_000L
        assertEquals(7_000_000_000L, SetEndGate.waitNs(reps = 7, targetReps = 20, baseWaitNs = base))
        assertEquals(SetEndGate.TARGET_HIT_WAIT_NS, SetEndGate.waitNs(reps = 20, targetReps = 20, baseWaitNs = base))
        assertEquals(base, SetEndGate.waitNs(reps = 21, targetReps = 20, baseWaitNs = base))
        assertEquals(base, SetEndGate.waitNs(reps = 5, targetReps = 0, baseWaitNs = base))
        // A learned wait longer than seven seconds is never shortened.
        assertEquals(9_000_000_000L, SetEndGate.waitNs(reps = 3, targetReps = 20, baseWaitNs = 9_000_000_000L))
        val gate = SetEndGate()
        gate.rep(ns(1000))
        assertFalse(gate.shouldEnd(ns(1000) + 6_900_000_000L, null, 4_000_000_000L, SetEndGate.waitNs(7, 20, base)))
        assertTrue(gate.shouldEnd(ns(1000) + 7_000_000_000L, null, 4_000_000_000L, SetEndGate.waitNs(7, 20, base)))
    }
}

/** The set-end silence is measured from the last working motion, so a still wrist must not keep refreshing it. */
class StillWristMotionTest {
    private fun detector() = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("machine_shoulder_press"))

    private fun still(detector: ProfiledMotionRepDetector, fromNs: Long, seconds: Int, offset: Double, seed: Int): Long {
        val random = kotlin.random.Random(seed)
        var t = fromNs
        repeat(seconds * 50) {
            fun noise(scale: Double) = (random.nextDouble() - 0.5) * 2 * scale
            detector.add(SensorSample(t, noise(0.1).toFloat(), noise(0.1).toFloat(), (9.80665 + offset + noise(0.12)).toFloat(),
                noise(0.05).toFloat(), noise(0.05).toFloat(), noise(0.05).toFloat(), 3))
            t += 20_000_000L
        }
        return t
    }

    @org.junit.jupiter.api.Test fun aMotionlessWristWithAnAccelerometerOffsetDoesNotLookLikeMovement() {
        for ((offset, seed) in listOf(0.0 to 1, 0.12 to 2, 0.2 to 3, 0.22 to 4)) {
            val det = detector()
            val end = still(det, 1_000_000_000L, 8, offset, seed)
            assertTrue(det.lastMotionNs < end - 6_000_000_000L,
                "offset $offset: last motion ${det.lastMotionNs / 1_000_000} ms but the wrist was still until ${end / 1_000_000} ms")
        }
    }

    @org.junit.jupiter.api.Test fun realMovementStillCountsAsMotion() {
        val det = detector()
        var t = 1_000_000_000L
        val start = still(det, t, 3, 0.2, 5)
        t = start
        repeat(100) { i ->
            det.add(SensorSample(t, 0f, (2.0 * kotlin.math.sin(i / 4.0)).toFloat(), 9.80665f + (2.0 * kotlin.math.sin(i / 4.0)).toFloat(), 0f, 0f, 0f, 3))
            t += 20_000_000L
        }
        assertTrue(det.lastMotionNs > t - 200_000_000L, "movement must keep the set open")
    }

    @org.junit.jupiter.api.Test fun gyroMovementStillCountsAsMotionWithoutAnyAccelerationChange() {
        val det = detector()
        var t = still(det, 1_000_000_000L, 3, 0.2, 6)
        repeat(50) { det.add(SensorSample(t, 0f, 0f, 9.80665f + 0.2f, 0.6f, 0f, 0f, 3)); t += 20_000_000L }
        assertTrue(det.lastMotionNs > t - 100_000_000L)
    }

    private fun assertTrue(condition: Boolean, message: String = "") = kotlin.test.assertTrue(condition, message)
}
