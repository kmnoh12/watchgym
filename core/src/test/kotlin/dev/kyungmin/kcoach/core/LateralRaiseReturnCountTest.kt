package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lateral raise starts with the arm down, so the cue and the count belong to the return. The cycle-axis detector used to
 * report the first lift and then the same rep's return, so 10 reps read 11 and the first cue fired as the arm went up.
 */
class LateralRaiseReturnCountTest {
    private val stepNs = 20_000_000L

    @Test fun everyRepIsReportedOnTheReturnAndTheFirstLiftIsSilent() {
        val liftPeaks = mutableListOf<Long>()
        val returnPeaks = mutableListOf<Long>()
        val events = replay("dumbbell_lateral_raise", reps = 6, liftPeaks, returnPeaks)
        assertEquals(6, events.size)
        events.forEach { event ->
            val nearest = (liftPeaks.map { it to "lift" } + returnPeaks.map { it to "return" }).minBy { abs(it.first - event.endTimestampNs) }
            assertEquals("return", nearest.second, "rep at ${event.endTimestampNs / 1_000_000} ms is closest to a ${nearest.second}")
        }
        assertTrue(events.first().endTimestampNs > liftPeaks.first() + 1_000_000_000L, "the first cue must come after the lift")
    }

    @Test fun otherCycleAxisExercisesStillReportTheirFirstPeak() {
        val none = mutableListOf<Long>()
        val events = replay("cable_seated_row", reps = 6, none, none)
        assertEquals(6, events.size)
    }

    private fun replay(exerciseId: String, reps: Int, liftPeaks: MutableList<Long>, returnPeaks: MutableList<Long>): List<ProfiledMotionRepEvent> {
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve(exerciseId))
        val events = mutableListOf<ProfiledMotionRepEvent>()
        var t = 1_000_000_000L
        fun feed(gyroX: Float, accelX: Float = 0f) {
            detector.add(SensorSample(t, accelX, 0f, 9.80665f, gyroX, 0f, 0f, 3))?.let(events::add)
            t += stepNs
        }
        repeat(50) { feed(0f) }
        detector.beginCounting()
        repeat(reps) {
            val liftStart = t
            for (i in 0 until 60) { val phase = i / 60.0; feed((1.6 * sin(PI * phase)).toFloat(), (1.5 * sin(2 * PI * phase)).toFloat()) }
            liftPeaks += liftStart + 30 * stepNs
            repeat(10) { feed(0f) }
            val returnStart = t
            for (i in 0 until 90) { val phase = i / 90.0; feed((-1.0 * sin(PI * phase)).toFloat(), (-1.0 * sin(2 * PI * phase)).toFloat()) }
            returnPeaks += returnStart + 45 * stepNs
            repeat(15) { feed(0f) }
        }
        repeat(100) { feed(0f) }
        events += detector.finishAll()
        return events
    }
}
