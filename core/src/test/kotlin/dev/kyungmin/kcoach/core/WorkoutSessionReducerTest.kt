package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkoutSessionReducerTest {
    private fun snapshot() = WorkoutSnapshot(
        sessionId = "session-1",
        routineId = "shoulders",
        exerciseIndex = 0,
        setIndex = 0,
        plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false),
            PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false),
        ),
        performedReps = emptyMap(),
        phase = WorkoutPhase.WORKING,
        restEndAtMs = 0L,
        pausedRestRemainingMs = 0L,
        paused = false,
        startedAtMs = 1L,
        pausedSinceMs = 0L,
        totalPausedMs = 0L,
        lastSequence = 4L,
        lastCompletedSetKey = null,
        captureStates = emptyMap(),
    )

    private val event = SetCompletionEvent(
        sessionId = "session-1",
        exerciseId = "external_rotation",
        setIndex = 0,
        reps = 12,
        restEndAtMs = 60_000L,
        sequence = 5L,
    )

    @Test
    fun `completion atomically advances reps phase and durable idempotency key`() {
        val applied = assertIs<SetCompletionResult.Applied>(
            WorkoutSessionReducer.completeSet(snapshot(), event),
        )

        assertEquals(1, applied.snapshot.setIndex)
        assertEquals(12, applied.snapshot.performedReps["external_rotation"])
        assertEquals(true, applied.snapshot.plannedSets.first().completed)
        assertEquals(10, applied.snapshot.plannedSets.first().targetReps)
        assertEquals(12, applied.snapshot.plannedSets.first().performedReps)
        assertEquals(WorkoutPhase.RESTING, applied.snapshot.phase)
        assertEquals(60_000L, applied.snapshot.restEndAtMs)
        assertEquals(event.completionKey, applied.snapshot.lastCompletedSetKey)
    }

    @Test
    fun `duplicate completion never increments reps twice`() {
        val once = assertIs<SetCompletionResult.Applied>(
            WorkoutSessionReducer.completeSet(snapshot(), event),
        )
        val duplicate = assertIs<SetCompletionResult.Duplicate>(
            WorkoutSessionReducer.completeSet(once.snapshot, event.copy(sequence = 6L)),
        )

        assertEquals(12, duplicate.snapshot.performedReps["external_rotation"])
        assertEquals(1, duplicate.snapshot.plannedSets.count { it.completed })
    }

    @Test
    fun `wrong identity and invalid index preserve the session`() {
        val wrongSession = assertIs<SetCompletionResult.Rejected>(
            WorkoutSessionReducer.completeSet(snapshot(), event.copy(sessionId = "other")),
        )
        val wrongExercise = assertIs<SetCompletionResult.Rejected>(
            WorkoutSessionReducer.completeSet(snapshot(), event.copy(exerciseId = "leg_press")),
        )
        val wrongSet = assertIs<SetCompletionResult.Rejected>(
            WorkoutSessionReducer.completeSet(snapshot(), event.copy(setIndex = 9)),
        )

        assertEquals("unknown_session", wrongSession.reason)
        assertEquals("wrong_exercise", wrongExercise.reason)
        assertEquals("invalid_set_index", wrongSet.reason)
        assertEquals(snapshot(), wrongSet.snapshot)
    }

    @Test
    fun `a paused rest can be skipped but never ends by itself`() {
        val pausedRest = snapshot().copy(phase = WorkoutPhase.PAUSED, paused = true, pausedRestRemainingMs = 40_000L,
            pausedSinceMs = 10_000L, totalPausedMs = 5_000L)
        assertEquals(null, WorkoutSessionReducer.advanceAfterRest(pausedRest, 99_000L))
        val skipped = WorkoutSessionReducer.advanceAfterRest(pausedRest, 25_000L, skip = true)!!
        assertEquals(WorkoutPhase.WORKING, skipped.phase)
        assertEquals(false, skipped.paused)
        assertEquals(0L, skipped.pausedRestRemainingMs)
        assertEquals(0L, skipped.pausedSinceMs)
        assertEquals(5_000L + 15_000L, skipped.totalPausedMs)
        // A workout paused during a set (no rest left) is not a rest and cannot be skipped.
        assertEquals(null, WorkoutSessionReducer.advanceAfterRest(pausedRest.copy(pausedRestRemainingMs = 0L), 25_000L, skip = true))
    }
    @Test fun completionMatchesTheAdvertisedPendingAttemptAcrossAFinishedCursor() {
        val base = snapshot()
        val finished = base.plannedSets.first().copy(completed = true, performedReps = 10)
        val next = finished.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", completed = false, performedReps = null)
        val pending = base.copy(plannedSets = listOf(finished, next))
        val applied = assertIs<SetCompletionResult.Applied>(WorkoutSessionReducer.completeSet(pending,
            event.copy(exerciseId = next.exerciseId)))
        assertEquals(1, applied.snapshot.exerciseIndex)
        assertEquals(true, applied.snapshot.plannedSets.last().completed)
        val outOfOrder = assertIs<SetCompletionResult.Rejected>(WorkoutSessionReducer.completeSet(base,
            event.copy(setIndex = 1)))
        assertEquals("out_of_order_set", outOfOrder.reason)
    }

}

class ManualRepEditPrecedenceTest {
    private fun completed(reps: Int, source: SetRepSource): WorkoutSnapshot {
        val base = WorkoutSnapshot(
            sessionId = "s", routineId = "r", exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(PlannedSetSnapshot(0, "flat_bench", 0, 10, 60.0, false)),
            performedReps = mapOf("flat_bench" to reps), phase = WorkoutPhase.RESTING, restEndAtMs = 1L,
            pausedRestRemainingMs = 0L, paused = false, startedAtMs = 1L, pausedSinceMs = 0L,
            totalPausedMs = 0L, lastSequence = 1L, lastCompletedSetKey = "s:flat_bench:0:",
            captureStates = emptyMap(),
        )
        return base.copy(plannedSets = base.plannedSets.map {
            it.copy(performedReps = reps, repSource = source, completed = true)
        })
    }

    @Test
    fun aLatePhoneReanalysisMayReplaceAWatchEstimateButNeverAnExplicitManualCount() {
        val watch = completed(5, SetRepSource.WATCH)
        val replaced = WorkoutSessionReducer.confirmReps(watch, "flat_bench", 0, 4)
        org.junit.jupiter.api.Assertions.assertEquals(4, replaced.plannedSets.first().performedReps)
        org.junit.jupiter.api.Assertions.assertEquals(SetRepSource.PHONE_ANALYSIS, replaced.plannedSets.first().repSource)

        val corrected = watch.copy(plannedSets = watch.plannedSets.map {
            it.copy(performedReps = 12, repSource = SetRepSource.MANUAL)
        }, performedReps = mapOf("flat_bench" to 12))
        val kept = WorkoutSessionReducer.confirmReps(corrected, "flat_bench", 0, 3)
        org.junit.jupiter.api.Assertions.assertEquals(12, kept.plannedSets.first().performedReps)
        org.junit.jupiter.api.Assertions.assertEquals(SetRepSource.MANUAL, kept.plannedSets.first().repSource)
        org.junit.jupiter.api.Assertions.assertEquals(12, kept.performedReps["flat_bench"])
    }
}
