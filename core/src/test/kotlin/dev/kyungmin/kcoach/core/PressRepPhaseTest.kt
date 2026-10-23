package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generic out-and-back detector counts a rep when the return lobe ends. If the first press lobe is
 * lost (counting starts mid-press), the first valid lobe was the return and every later rep used to be
 * paired return-then-press: the count and its vibration fired at the lift and the last return was left over.
 */
class PressRepPhaseTest {
    private class Seg(val rate: Float, val ms: Int)

    private data class Run(val events: List<ProfiledMotionRepEvent>, val returnEndsNs: List<Long>)

    @Test fun aTruncatedFirstPressStillCountsEveryRepOnTheReturn() {
        val segments = mutableListOf(Seg(1.0f, 100), Seg(-1.0f, 600), Seg(0f, 400))
        repeat(9) { segments += listOf(Seg(1.0f, 600), Seg(-1.0f, 600), Seg(0f, 400)) }
        val run = run(segments)
        assertEquals(10, run.events.size)
        assertEveryEventEndsOnAReturn(run)
    }

    @Test fun aCleanSetStillCountsOnTheReturnOnly() {
        val segments = mutableListOf<Seg>()
        repeat(10) { segments += listOf(Seg(1.0f, 600), Seg(-1.0f, 600), Seg(0f, 400)) }
        val run = run(segments)
        assertEquals(10, run.events.size)
        assertEveryEventEndsOnAReturn(run)
    }

    @Test fun aTinyOppositeBlipBeforeTheFirstPressDoesNotShiftTheCountToTheLift() {
        val segments = mutableListOf(Seg(-1.0f, 20), Seg(0f, 300))
        repeat(10) { segments += listOf(Seg(1.0f, 600), Seg(-1.0f, 600), Seg(0f, 400)) }
        val run = run(segments)
        assertEquals(10, run.events.size)
        assertEveryEventEndsOnAReturn(run)
    }

    @Test fun aBriefTwitchBetweenThePressAndItsReturnDoesNotCostTheRep() {
        val segments = mutableListOf<Seg>()
        repeat(10) { segments += listOf(Seg(1.0f, 600), Seg(-1.0f, 40), Seg(1.0f, 40), Seg(-1.0f, 600), Seg(0f, 400)) }
        val run = run(segments)
        assertEquals(10, run.events.size)
        assertEveryEventEndsOnAReturn(run)
    }

    @Test fun aSetThatStartsWithTheDownstrokeLearnsThatDirectionAsItsOut() {
        val segments = mutableListOf<Seg>()
        repeat(6) { segments += listOf(Seg(-1.0f, 600), Seg(1.0f, 600), Seg(0f, 400)) }
        val run = run(segments)
        assertEquals(6, run.events.size)
    }

    private fun assertEveryEventEndsOnAReturn(run: Run) {
        run.events.forEach { event ->
            assertTrue(run.returnEndsNs.any { abs(it - event.endTimestampNs) <= 60_000_000L },
                "rep ended at ${event.endTimestampNs / 1_000_000L} ms, return lobes ended at ${run.returnEndsNs.map { it / 1_000_000L }}")
        }
    }

    private fun run(segments: List<Seg>): Run {
        val detector = ProfiledMotionRepDetector(profile())
        val events = mutableListOf<ProfiledMotionRepEvent>()
        val returnEnds = mutableListOf<Long>()
        var t = 1_000_000_000L
        // A still second lets the gravity estimate settle, like the preparation hold.
        repeat(50) { detector.add(sample(t, 0f)); t += STEP_NS }
        detector.beginCounting()
        for (segment in segments) {
            repeat(segment.ms / 20) {
                detector.add(sample(t, segment.rate))?.let(events::add)
                t += STEP_NS
            }
            if (segment.rate < 0f && segment.ms >= 500) returnEnds += t
        }
        repeat(25) { detector.add(sample(t, 0f))?.let(events::add); t += STEP_NS }
        return Run(events, returnEnds)
    }

    private fun profile() = ExerciseTrackingProfile(
        exerciseId = "test",
        id = "test-profile-v1",
        mode = TrackingMode.WRIST_IMU_CAUTION,
        signal = TrackingSignal.COMBINED_MOTION,
        minimumRepMs = 400L,
        maximumRepMs = 5_000L,
        minimumSeparationMs = 300L,
        thresholdFraction = 0.0,
        minimumRelativeRom = 0.1,
        label = "test",
        capabilityTier = CapabilityTier.A_WRIST_DIRECT_AUTO,
        countMode = CountMode.AUTO,
        family = MotionFamily.PRESS_RETURN,
        minimumFeatureRate = 0.20,
        minimumLobeDisplacement = 0.20,
        minimumLobeMs = 120L,
        maximumLobeMs = 1_800L,
    )

    private fun sample(timestampNs: Long, gyroX: Float) = SensorSample(
        timestampNs = timestampNs,
        accelerationX = 0f,
        accelerationY = 0f,
        accelerationZ = 9.80665f,
        gyroscopeX = gyroX,
        gyroscopeY = 0f,
        gyroscopeZ = 0f,
    )

    private companion object {
        const val STEP_NS = 20_000_000L
    }
}
