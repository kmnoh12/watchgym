package dev.kyungmin.kcoach.core

import kotlin.test.*

class UnilateralSetTest {
    private fun initial(id: String = "one_arm_dumbbell_row") = WorkoutSnapshot(sessionId = "s", routineId = "r",
        exerciseIndex = 0, setIndex = 0, plannedSets = listOf(
            PlannedSetSnapshot(0, id, 0, 10, 20.0, false, restSeconds = 60, setId = "set", attemptId = "a", identityRequired = true),
            PlannedSetSnapshot(0, id, 1, 10, 20.0, false, setId = "next", attemptId = "b", identityRequired = true)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())
    private fun first(s: WorkoutSnapshot = initial(), source: SetRepSource = SetRepSource.WATCH) =
        assertIs<SetCompletionResult.Applied>(WorkoutSessionReducer.completeSet(s,
            SetCompletionEvent("s", s.plannedSets.first().exerciseId, 0, 12, 61_000, 1, source, "set", "a"))).snapshot
    private fun action(s: WorkoutSnapshot, action: UnilateralAction, count: Int? = null) =
        UnilateralSetReducer.apply(s, "s", "set", "a", action, 5_000, count)

    @Test fun `automatic and manual first completion hold rest without advancing or crediting full set`() {
        for (source in listOf(SetRepSource.WATCH, SetRepSource.MANUAL)) {
            val s = first(source = source)
            assertEquals(UnilateralStage.AWAITING_OTHER_SIDE, s.plannedSets[0].unilateral?.stage)
            assertEquals(source, s.plannedSets[0].unilateral?.watchRepSource)
            assertFalse(s.plannedSets[0].completed)
            assertEquals(0, s.setIndex); assertEquals(0, s.restEndAtMs); assertTrue(s.performedReps.isEmpty())
            assertNull(WorkoutSessionReducer.advanceAfterRest(s, 100_000))
            assertEquals(s, WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(s)))
        }
    }
    @Test fun `both arms require explicit start and explicit completion then start exactly one rest`() {
        val first = first()
        assertIs<SetCompletionResult.Rejected>(action(first, UnilateralAction.COMPLETE_OTHER_SIDE))
        val other = assertIs<SetCompletionResult.Applied>(action(first, UnilateralAction.START_OTHER_SIDE)).snapshot
        assertEquals(0, other.restEndAtMs)
        assertIs<SetCompletionResult.Duplicate>(WorkoutSessionReducer.completeSet(other,
            SetCompletionEvent("s", "one_arm_dumbbell_row", 0, 12, 90_000, 2, SetRepSource.WATCH, "set", "a")))
        val done = assertIs<SetCompletionResult.Applied>(action(other, UnilateralAction.COMPLETE_OTHER_SIDE, 9)).snapshot
        assertTrue(done.plannedSets[0].completed)
        assertEquals(9, done.plannedSets[0].unilateral?.otherReps)
        assertEquals(12, done.plannedSets[0].performedReps)
        assertEquals(UnilateralStage.BOTH_COMPLETE, done.plannedSets[0].unilateral?.stage)
        assertEquals(65_000, done.restEndAtMs); assertEquals(1, done.setIndex)
        assertIs<SetCompletionResult.Duplicate>(action(done, UnilateralAction.COMPLETE_OTHER_SIDE))
        assertEquals(done, WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(done)))
    }
    @Test fun `watch arm only finalizes without inventing opposite reps`() {
        val done = assertIs<SetCompletionResult.Applied>(action(first(), UnilateralAction.FINISH_WATCH_SIDE_ONLY)).snapshot
        assertEquals(UnilateralStage.WATCH_SIDE_ONLY, done.plannedSets[0].unilateral?.stage)
        assertNull(done.plannedSets[0].unilateral?.otherReps)
        assertEquals(WorkoutPhase.RESTING, done.phase)
    }
    @Test fun `stale identity paused set wrong order and invalid count cannot mutate progress`() {
        assertIs<SetCompletionResult.Rejected>(action(initial(), UnilateralAction.START_OTHER_SIDE))
        assertIs<SetCompletionResult.Rejected>(action(first().copy(paused = true), UnilateralAction.START_OTHER_SIDE))
        assertIs<SetCompletionResult.Rejected>(UnilateralSetReducer.apply(first(), "s", "set", "old", UnilateralAction.START_OTHER_SIDE, 1))
        assertIs<SetCompletionResult.Rejected>(action(first(), UnilateralAction.START_OTHER_SIDE, -1))
    }
    @Test fun `bilateral completion keeps existing automatic rest behavior`() {
        val s = first(initial("cable_seated_row"))
        assertNull(s.plannedSets[0].unilateral)
        assertTrue(s.plannedSets[0].completed); assertEquals(WorkoutPhase.RESTING, s.phase)
    }
    @Test fun `late analysis updates first arm without creating opposite arm evidence`() {
        val s = WorkoutSessionReducer.confirmReps(first(), "one_arm_dumbbell_row", 0, 11, "set", "a")
        assertEquals(11, s.plannedSets[0].unilateral?.watchReps)
        assertNull(s.plannedSets[0].performedReps); assertTrue(s.performedReps.isEmpty())
        val manual = first(source = SetRepSource.MANUAL)
        assertEquals(manual, WorkoutSessionReducer.confirmReps(manual, "one_arm_dumbbell_row", 0, 8, "set", "a"))
    }
}
