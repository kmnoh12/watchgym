package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredCursorTest {
    private fun snapshot(exerciseIndex: Int, setIndex: Int) = WorkoutSnapshot(
        sessionId = "session", routineId = "routine", exerciseIndex = exerciseIndex, setIndex = setIndex,
        plannedSets = listOf(
            PlannedSetSnapshot(0, "dumbbell_curl", 0, 8, 5.0, false),
            PlannedSetSnapshot(0, "dumbbell_curl", 1, 8, 5.0, false),
            PlannedSetSnapshot(1, "lat_pull_down", 0, 10, 40.0, false),
        ),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0,
        pausedRestRemainingMs = 0, paused = false, startedAtMs = 1, pausedSinceMs = 0,
        totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap(),
    )
    private fun routine() = WorkoutRoutine("routine", "운동", "등", "오늘", mutableListOf(
        RoutineExercise(WorkoutData.catalog.first { it.id == "dumbbell_curl" }, mutableListOf(PlannedSet(5.0, 8), PlannedSet(5.0, 8))),
        RoutineExercise(WorkoutData.catalog.first { it.id == "lat_pull_down" }, mutableListOf(PlannedSet(40.0, 10))),
    ))

    @Test fun aCursorPastTheLastExerciseOrSetIsClampedInsteadOfIndexingOutOfBounds() {
        assertEquals(1 to 0, restoredCursor(snapshot(9, 9), routine()))
        assertEquals(0 to 1, restoredCursor(snapshot(0, 99), routine()))
    }

    @Test fun anEmptyRebuiltRoutineReturnsAZeroCursor() {
        assertEquals(0 to 0, restoredCursor(snapshot(4, 4), WorkoutRoutine("routine", "운동", "등", "오늘", mutableListOf())))
    }
}
