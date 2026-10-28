package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.ui.SetField
import dev.kyungmin.kcoach.phone.ui.WorkoutActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutEditSafetyTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private fun saveSnapshot(snapshot: WorkoutSnapshot): Boolean {
        val store = SessionStore(app)
        store.loadActive().getOrThrow()
        return store.saveActive(snapshot)
    }
    private fun seed(completed: Boolean = false): WorkoutSnapshot {
        val snapshot = WorkoutSnapshot(sessionId = "edit-session", routineId = "edit-routine",
            exerciseIndex = 0, setIndex = 0, startedAtMs = 1,
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
            paused = false, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap(),
            plannedSets = listOf(PlannedSetSnapshot(0, "barbell_curl", 0, 10, 20.0,
                completed = completed, exerciseName = "컬", bodyCategory = "기타", equipment = "기타", setId = "set-1", attemptId = "attempt-1",
                identityRequired = true, performedReps = if (completed) 8 else null,
                repSource = if (completed) SetRepSource.MANUAL else null)),
            performedReps = mapOf("barbell_curl" to if (completed) 8 else 0))
        check(saveSnapshot(snapshot))
        return snapshot
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun routine(activity: MainActivity) = field(field(activity, "activeWorkout")!!, "routine") as WorkoutRoutine
    private fun actions(activity: MainActivity) = field(activity, "workoutActions") as WorkoutActions

    @Test fun invalidWeightInputsNeverCrashOrChangeState() {
        val before = seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            for (value in listOf("-1", "NaN", "Infinity", "-Infinity", "1e999", "bad")) {
                actions(activity).onEditValue(0, 0, SetField.WEIGHT, value, true)
                actions(activity).onEditFinished()
                assertEquals(20.0, routine(activity).exercises[0].sets[0].weightKg!!, 0.0)
                // The save after an edit may fill blank display metadata with defaults; the sets themselves must not change.
                val after = SessionStore(app).loadActive().getOrThrow()!!
                assertEquals(before.plannedSets.map { Triple(it.weightKg, it.targetReps, it.completed) },
                    after.plannedSets.map { Triple(it.weightKg, it.targetReps, it.completed) })
            }
        } finally { controller.destroy() }
    }

    @Test fun rejectedSaveRollsBackAnEntireEditAndShowsToast() {
        seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            actions(activity).onEditValue(0, 0, SetField.WEIGHT, "25", false)
            actions(activity).onEditValue(0, 0, SetField.REPS, "12", false)
            // Simulate a service commit between Activity observation and save.
            val service = SessionStore(app)
            val current = service.loadActive().getOrThrow()!!
            check(service.saveActive(current.copy(lastSequence = 2)))
            actions(activity).onEditFinished()
            assertEquals(20.0, routine(activity).exercises[0].sets[0].weightKg!!, 0.0)
            assertEquals(10, routine(activity).exercises[0].sets[0].targetReps)
            assertEquals(2L, SessionStore(app).loadActive().getOrThrow()!!.lastSequence)
            assertNull(ShadowToast.getTextOfLatestToast())
        } finally { controller.destroy() }
    }

    @Test fun snapshotValidationFailureOnAddDoesNotEscapeAndRestoresPlan() {
        seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val sets = routine(activity).exercises[0].sets
            // Existing invalid memory is still caught at the snapshot boundary.
            sets[0].weightKg = Double.NaN
            actions(activity).onAddSet(0)
            assertEquals(1, routine(activity).exercises[0].sets.size)
            assertEquals(20.0, SessionStore(app).loadActive().getOrThrow()!!.plannedSets[0].weightKg!!, 0.0)
            assertNull(ShadowToast.getTextOfLatestToast())
        } finally { controller.destroy() }
    }

    @Test fun editFinishedCatchesSnapshotConstructionAndRestoresValidPreEditState() {
        seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            actions(activity).onEditValue(0, 0, SetField.WEIGHT, "25", false)
            routine(activity).exercises[0].sets[0].weightKg = Double.POSITIVE_INFINITY
            actions(activity).onEditFinished()
            assertEquals(20.0, routine(activity).exercises[0].sets[0].weightKg!!, 0.0)
            assertEquals(20.0, SessionStore(app).loadActive().getOrThrow()!!.plannedSets[0].weightKg!!, 0.0)
            assertNull(ShadowToast.getTextOfLatestToast())
        } finally { controller.destroy() }
    }

    @Test fun rejectedUncompletionRestoresIdentityRepsAndAnalysesState() {
        seed(completed = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val before = routine(activity).exercises[0].sets[0].copy()
            app.getSharedPreferences("calibration-captures", Context.MODE_PRIVATE).edit().putString("active", "busy").commit()
            actions(activity).onToggleSet(0, 0)
            assertEquals(before, routine(activity).exercises[0].sets[0])
            assertEquals(mapOf("barbell_curl" to 8), field(field(activity, "activeWorkout")!!, "performedRepsByExercise"))
        } finally { controller.destroy() }
    }
    @Test fun activeAddRemoveAndUndoRenderTheCommittedPlan() {
        seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            actions(activity).onAddSet(0)
            val added = SessionStore(app).loadActive().getOrThrow()!!
            assertEquals(2, routine(activity).exercises[0].sets.size)
            assertEquals(added.plannedSets.map { it.setId }, routine(activity).exercises[0].sets.map { it.setId })
            actions(activity).onRemoveSet(0, 0)
            assertEquals(1, routine(activity).exercises[0].sets.size)
            actions(activity).onUndoRemoveSet()
            assertEquals(added.plannedSets, SessionStore(app).loadActive().getOrThrow()!!.plannedSets)
            assertEquals(added.plannedSets.map { it.setId }, routine(activity).exercises[0].sets.map { it.setId })
        } finally { controller.destroy() }
    }

    @Test fun failedActiveRemoveKeepsUiAndDoesNotOfferAnUndo() {
        seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            actions(activity).onAddSet(0)
            val before = routine(activity).exercises[0].sets.map { it.copy() }
            app.getSharedPreferences("calibration-captures", 0).edit().putString("active", "busy").commit()
            actions(activity).onRemoveSet(0, 0)
            assertEquals(before, routine(activity).exercises[0].sets)
            assertNull(ShadowToast.getTextOfLatestToast())
            actions(activity).onUndoRemoveSet()
            assertEquals(before, routine(activity).exercises[0].sets)
        } finally { controller.destroy() }
    }

    @Test fun addingAnExerciseDuringRestKeepsTheBrowsedPageAndRestDeadline() {
        val seed = seed()
        val first = seed.plannedSets.first()
        val plan = listOf(first.copy(completed = true, performedReps = 8, repSource = SetRepSource.MANUAL),
            first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫풀다운",
                setId = "next-set", attemptId = "next-attempt", completed = false, performedReps = null, repSource = null))
        val restEnd = System.currentTimeMillis() + 90_000
        check(saveSnapshot(seed.copy(phase = WorkoutPhase.RESTING, setIndex = 1,
            restEndAtMs = restEnd, plannedSets = plan,
            lastCompletedSetKey = SetIdentity.completionKey(seed.sessionId, plan.first()))))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            actions(activity).onPageSettled(1)
            val workout = field(activity, "activeWorkout")!!
            assertEquals(1, field(workout, "restingPageIndex"))
            val routine = routine(activity)
            routine.exercises += RoutineExercise(WorkoutData.catalog.first { it.id == "cable_seated_row" },
                mutableListOf(PlannedSet(null, 10)))
            MainActivity::class.java.getDeclaredMethod("commitRunningRoutineEdit", WorkoutRoutine::class.java)
                .apply { isAccessible = true }.invoke(activity, routine)
            assertEquals(1, field(workout, "restingPageIndex"))
            val saved = SessionStore(app).loadActive().getOrThrow()!!
            assertEquals(3, saved.plannedSets.map { it.exerciseIndex }.distinct().size)
            assertEquals(restEnd, saved.restEndAtMs)
            assertEquals(0, saved.exerciseIndex)
            // A service snapshot with an inserted set rebuilds the routine objects but keeps the browsed exercise.
            val inserted = saved.plannedSets.first().copy(setIndex = 1, setId = "inserted", attemptId = "fresh",
                completed = false, performedReps = null, repSource = null)
            val changed = saved.copy(plannedSets = listOf(saved.plannedSets.first(), inserted) + saved.plannedSets.drop(1))
            MainActivity::class.java.getDeclaredMethod("applySnapshotToWorkout", workout.javaClass, WorkoutSnapshot::class.java)
                .apply { isAccessible = true }.invoke(activity, workout, changed)
            assertEquals(1, field(workout, "restingPageIndex"))
            assertEquals(restEnd, field(activity, "activeRestEndAtMs"))
        } finally { controller.destroy() }
    }

    @Test fun rejectedExerciseSwitchRestoresCursorAttemptAndNavigation() {
        val before = seed()
        val first = before.plannedSets.single()
        check(saveSnapshot(before.copy(plannedSets = listOf(first,
            first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫풀다운",
                setId = "next", attemptId = "next-attempt")))))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val workout = field(activity, "activeWorkout")!!
            val original = routine(activity).exercises[0].sets[0].copy()
            app.getSharedPreferences("calibration-captures", 0).edit().putString("active", "busy").commit()
            actions(activity).onPageSettled(1)
            assertEquals(0, field(workout, "exerciseIndex"))
            assertEquals(original, routine(activity).exercises[0].sets[0])
            assertTrue((field(workout, "setIndexByExercise") as Map<*, *>).isEmpty())
            assertEquals(0, SessionStore(app).loadActive().getOrThrow()!!.exerciseIndex)
        } finally { controller.destroy() }
    }

    @Test fun backgroundCompletionDoesNotReplaceTheExerciseLibrary() {
        seed()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val screen = MainActivity::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
            val library = screen.type.enumConstants.single { it.toString() == "LIBRARY" }
            screen.set(activity, library)
            val service = SessionStore(app)
            val before = service.loadActive().getOrThrow()!!
            check(service.saveActive(before.copy(exerciseIndex = 1,
                plannedSets = before.plannedSets.map { it.copy(completed = true, performedReps = 10) })))
            MainActivity::class.java.getDeclaredMethod("refreshActiveSession").apply { isAccessible = true }.invoke(activity)
            assertEquals(library, screen.get(activity))
            assertEquals(1, field(field(activity, "activeWorkout")!!, "exerciseIndex"))
        } finally { controller.destroy() }
    }

    @Test fun undoExerciseRemovalRestoresBrowsedPageWithoutRewindingRest() {
        val before = seed()
        val first = before.plannedSets.single().copy(completed = true, performedReps = 8,
            repSource = SetRepSource.MANUAL)
        val deadline = System.currentTimeMillis() + 90_000
        check(saveSnapshot(before.copy(phase = WorkoutPhase.RESTING, restEndAtMs = deadline,
            lastCompletedSetKey = SetIdentity.completionKey(before.sessionId, first),
            plannedSets = listOf(first, first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down",
                exerciseName = "랫풀다운", setId = "next", attemptId = "next-attempt",
                completed = false, performedReps = null, repSource = null)))))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val workout = field(activity, "activeWorkout")!!
            actions(activity).onPageSettled(1)
            MainActivity::class.java.getDeclaredMethod("removeExerciseWithUndo", WorkoutRoutine::class.java,
                Int::class.javaPrimitiveType, workout.javaClass, kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }.invoke(activity, routine(activity), 1, workout, {})
            assertEquals(1, routine(activity).exercises.size)
            val overlays = field(activity, "overlays") as dev.kyungmin.kcoach.phone.ui.OverlayState
            requireNotNull(overlays.undo).onUndo()
            assertEquals(2, routine(activity).exercises.size)
            assertEquals(1, field(workout, "restingPageIndex"))
            assertEquals(0, field(workout, "exerciseIndex"))
            assertEquals(deadline, SessionStore(app).loadActive().getOrThrow()!!.restEndAtMs)
        } finally { controller.destroy() }
    }
    @Test fun rapidSetEditsNavigationReorderingAndPauseKeepOneCommittedSession() {
        val base = seed()
        val first = base.plannedSets.single()
        check(saveSnapshot(base.copy(plannedSets = listOf(first,
            first.copy(setIndex = 1, setId = "second", attemptId = "second-attempt"),
            first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫풀다운", setId = "pull", attemptId = "pull-attempt"),
            first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫풀다운", setIndex = 1, setId = "pull2", attemptId = "pull2-attempt")))))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val workout = field(activity, "activeWorkout")!!
            val move = MainActivity::class.java.getDeclaredMethod("moveRoutineExercise", WorkoutRoutine::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, workout.javaClass, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val finish = MainActivity::class.java.getDeclaredMethod("finishRoutineMove", WorkoutRoutine::class.java).apply { isAccessible = true }
            repeat(30) { n ->
                actions(activity).onPageSettled(n % 2)
                val current = SessionStore(app).loadActive().getOrThrow()!!
                val index = current.exerciseIndex
                actions(activity).onAddSet(index)
                val added = SessionStore(app).loadActive().getOrThrow()!!
                val last = added.plannedSets.filter { it.exerciseIndex == index }.last()
                actions(activity).onRemoveSet(index, last.setIndex)
                actions(activity).onUndoRemoveSet()
                actions(activity).onRemoveSet(index, last.setIndex)
                actions(activity).onTogglePause()
                actions(activity).onTogglePause()
                val movingRoutine = routine(activity)
                val target = WorkoutSessionReducer.nextPendingSet(SessionStore(app).loadActive().getOrThrow()!!)!!
                move.invoke(activity, movingRoutine, 0, 1, workout, false)
                finish.invoke(activity, movingRoutine)
                val saved = SessionStore(app).loadActive().getOrThrow()!!
                assertEquals(target.setId, WorkoutSessionReducer.nextPendingSet(saved)!!.setId)
                assertEquals(target.attemptId, WorkoutSessionReducer.nextPendingSet(saved)!!.attemptId)
                assertFalse(saved.paused)
                assertEquals(4, saved.plannedSets.size)
                assertEquals(saved.plannedSets.map { it.setId }, routine(activity).exercises.flatMap { e -> e.sets.map { it.setId } })
                assertEquals(saved.plannedSets.size, saved.plannedSets.map { it.setId }.distinct().size)
            }
        } finally { controller.destroy() }
    }

}
