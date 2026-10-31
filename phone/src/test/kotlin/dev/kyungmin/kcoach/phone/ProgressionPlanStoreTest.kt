package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressionPlanStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        listOf("progression-plans", "exercise-history", "progression-settings", "progression-increments", "finished-workouts").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }
    private fun routine() = WorkoutRoutine("r", "루틴", "", "", mutableListOf(RoutineExercise(
        ExerciseDefinition("bench", "벤치", "", "가슴", "바벨", pattern = "chest_press"),
        mutableListOf(PlannedSet(20.0, 15, setType = SetType.WARMUP, setId = "warm"), PlannedSet(70.0, 8, setId = "work")))))
    private fun completed(routine: WorkoutRoutine, id: String, start: Long = 1000) = WorkoutSnapshot(
        sessionId = id, routineId = routine.id, exerciseIndex = 0, setIndex = 0,
        plannedSets = routine.exercises.flatMapIndexed { ei, item -> item.sets.mapIndexed { si, set ->
            PlannedSetSnapshot(ei, item.exercise.id, si, set.targetReps, set.weightKg, true,
                setType = set.setType.name, performedReps = set.targetReps, repSource = SetRepSource.MANUAL,
                equipment = item.exercise.equipment, pattern = item.exercise.pattern, setId = "$id-$si", attemptId = "a")
        } }, performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = start, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())
    private fun record(r: WorkoutRoutine, id: String = "session", day: Long = 1): WorkoutSnapshot {
        val store = ProgressionPlanStore(context)
        store.prefill(r).getOrThrow()
        val snapshot = completed(r, id, day * 1000)
        store.register(id, r, snapshot.plannedSets.map { it.setId }).getOrThrow()
        store.logs(snapshot, day).forEach { ExerciseHistoryStore(context).add(it) }
        return snapshot
    }
    @Test fun repeatedOpenAndStoreRecreationNeverApplyTwiceAndManualEditsWin() {
        val r = routine()
        record(r)
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(9, r.exercises[0].sets[1].targetReps)
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(9, r.exercises[0].sets[1].targetReps)
        r.exercises[0].sets[1].targetReps = 4
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(4, r.exercises[0].sets[1].targetReps)
        assertEquals(15, r.exercises[0].sets[0].targetReps)
    }
    @Test fun settingsChangesDoNotOverwriteManualTargetsWithinTheSameEvidence() {
        val r = routine()
        record(r)
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        r.exercises[0].sets[1].targetReps = 4
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        ProgressionSettingsStore(context).save(ProgressionSettings(speed = ProgressionSpeed.FAST)).getOrThrow()
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(4, r.exercises[0].sets[1].targetReps)
        record(r, "next-session", 2)
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(5, r.exercises[0].sets[1].targetReps)
    }
    @Test fun correctionToSameFinishedSessionPreservesManualNextTarget() {
        val r = routine()
        record(r)
        val store = ProgressionPlanStore(context)
        store.prefill(r).getOrThrow()
        r.exercises[0].sets[1].targetReps = 4
        store.prefill(r).getOrThrow()
        val history = ExerciseHistoryStore(context)
        val old = history.load().all.single()
        history.add(old.copy(sets = old.sets.map { it.copy(reps = it.reps + 1) }))
        store.prefill(r).getOrThrow()
        assertEquals(4, r.exercises[0].sets[1].targetReps)
    }
    @Test fun sensorConfidenceCannotBlockAnExplicitManualConfirmation() {
        val r = routine()
        val snapshot = record(r)
        val set = snapshot.plannedSets[1]
        val analysis = SetAnalysisUi(set.exerciseId, "벤치", 1, 8, 8, 8, 0.2,
            repConfidence = 0.2, setId = set.setId, attemptId = set.attemptId)
        val sensor = snapshot.copy(plannedSets = snapshot.plannedSets.map { it.copy(repSource = SetRepSource.PHONE_ANALYSIS) })
        val store = ProgressionPlanStore(context)
        assertFalse(store.logs(sensor, 1, mapOf(analysis.key to analysis)).single().sets[1].progressionEligible)
        assertTrue(store.logs(snapshot, 1, mapOf(analysis.key to analysis)).single().sets[1].progressionEligible)
    }
    @Test fun firstDayAndSwitchOffPreserveInputs() {
        val r = routine()
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(8, r.exercises[0].sets[1].targetReps)
        record(r)
        ProgressionSettingsStore(context).save(ProgressionSettings(false)).getOrThrow()
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(8, r.exercises[0].sets[1].targetReps)
    }
    @Test fun settingsAndExerciseSpecificIncrementSurviveNewStore() {
        assertEquals(ProgressionSettings(), ProgressionSettingsStore(context).load().getOrThrow())
        ProgressionSettingsStore(context).save(ProgressionSettings(false, ProgressionSpeed.SLOW)).getOrThrow()
        assertEquals(ProgressionSettings(false, ProgressionSpeed.SLOW), ProgressionSettingsStore(context).load().getOrThrow())
        val increments = ProgressionIncrementStore(context)
        assertEquals(IncrementCandidate(10.0, false), increments.candidate("machine", "머신", listOf(20.0, 30.0, 40.0)).getOrThrow())
        increments.save("machine", 5.0).getOrThrow()
        assertEquals(IncrementCandidate(5.0, false, confirmed = true), ProgressionIncrementStore(context).candidate("machine", "머신").getOrThrow())
        assertEquals(IncrementCandidate(5.0, false), increments.candidate("other", "머신").getOrThrow())
        assertTrue(increments.save("machine", -1.0).isFailure)
    }
    @Test fun projectionPreservesTargetsWarmupAndMissingUnfinishedPositions() {
        val r = routine()
        val s = record(r)
        val changed = s.copy(plannedSets = s.plannedSets.mapIndexed { i, set ->
            if (i == 1) set.copy(performedReps = null, completed = false) else set
        })
        val rows = ProgressionPlanStore(context).logs(changed, 1).single().sets
        assertEquals(2, rows.size)
        assertTrue(rows[0].isWarmup)
        assertEquals(15, rows[0].targetReps)
        assertFalse(rows[1].repsEntered)
        assertFalse(rows[1].completed)
        assertEquals(8, rows[1].targetReps)
    }
    @Test fun changedLayoutStartsFreshAndSameExerciseAtTwoPositionsIsPreserved() {
        val r = routine()
        record(r)
        r.exercises[0].sets.reverse()
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(8, r.exercises[0].sets[0].targetReps)
        val history = ExerciseHistoryStore(context)
        val existing = history.load().all.single()
        history.add(existing.copy(exerciseIndex = 1))
        assertEquals(2, history.load().all.size)
    }
    @Test fun finishedStoreProjectsTheCompleteShapeAndManualCorrectionHasPriority() {
        val r = routine()
        val s = record(r)
        val changed = s.copy(plannedSets = s.plannedSets.map { if (it.setIndex == 1) it.copy(performedReps = 7) else it })
        FinishedWorkoutStore(context).finish(changed, "루틴", 1)
        assertEquals(7, ExerciseHistoryStore(context).load().last("bench")!!.sets[1].reps)
        ProgressionPlanStore(context).prefill(r).getOrThrow()
        assertEquals(8, r.exercises[0].sets[1].targetReps)
    }

    @Test fun reorderedSessionDoesNotInheritUpperBoundsByArrayIndex() {
        val r = routine()
        record(r)
        r.exercises[0].sets.reverse()
        val snapshot = completed(r, "reordered", 2000)
        val store = ProgressionPlanStore(context)
        store.register(snapshot.sessionId, r, snapshot.plannedSets.map { it.setId }).getOrThrow()
        val sets = store.logs(snapshot, 2).single().sets
        assertEquals(10, sets[0].upperReps)
        assertEquals(15, sets[1].upperReps)
        assertFalse(sets[0].isWarmup)
        assertTrue(sets[1].isWarmup)
    }

    @Test fun finishedRecordComparisonsRetainTheBestBeyondTheProgressionWindow() {
        val r = routine()
        r.exercises[0].sets.removeAt(0)
        val store = FinishedWorkoutStore(context)
        for (day in 1L..23L) {
            r.exercises[0].sets[0].weightKg = when (day) { 1L -> 100.0; 23L -> 80.0; else -> 70.0 }
            store.finish(completed(r, "s$day", day * 1000), "루틴", 1)
        }
        assertEquals(20, ExerciseHistoryStore(context).load().all.size)
        assertEquals(100.0, store.historyForRecords().heaviestSet("bench")!!.second.weightKg)
        assertTrue(store.recordHits("s23").isEmpty())
    }
}
