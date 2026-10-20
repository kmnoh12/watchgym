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
    private fun seed(completed: Boolean = false): WorkoutSnapshot {
        val snapshot = WorkoutSnapshot(sessionId = "edit-session", routineId = "edit-routine",
            exerciseIndex = 0, setIndex = 0, startedAtMs = 1,
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
            paused = false, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap(),
            plannedSets = listOf(PlannedSetSnapshot(0, "barbell_curl", 0, 10, 20.0,
                completed = completed, exerciseName = "컬", setId = "set-1", attemptId = "attempt-1",
                identityRequired = true, performedReps = if (completed) 8 else null,
                repSource = if (completed) SetRepSource.MANUAL else null)),
            performedReps = mapOf("barbell_curl" to if (completed) 8 else 0))
        check(SessionStore(app).saveActive(snapshot))
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
            assertTrue(ShadowToast.getTextOfLatestToast().contains("되돌렸어요"))
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
            assertTrue(ShadowToast.getTextOfLatestToast().contains("되돌렸어요"))
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
            assertTrue(ShadowToast.getTextOfLatestToast().contains("되돌렸어요"))
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
}
