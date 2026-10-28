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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutPresentationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun plan() = WorkoutSnapshot(
        sessionId = "presentation-session", routineId = "free", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false,
            exerciseName = "외회전", setId = "presentation-set", attemptId = "presentation-attempt", identityRequired = true)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap(),
    )

    @Test fun reopeningTheWorkoutScreenDoesNotSaveOrReplaceAnUnacknowledgedCommand() {
        SessionStore(app).saveActive(plan())
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("SET", "presentation-session", "external_rotation", 0, 10))
        val prefs = app.getSharedPreferences("workout-session", Context.MODE_PRIVATE)
        val revision = prefs.getLong("revision", 0)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            activity.pause().stop().start().resume()
            assertEquals("Rendering must not replace the desired watch command", pending, outbox.pending())
            assertEquals("Rendering must not commit another session revision", revision, prefs.getLong("revision", 0))
            assertEquals(plan(), SessionStore(app).loadActive().getOrThrow())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun finishingAWeightEditPersistsItAndSendsTheSameSetIdentity() {
        SessionStore(app).saveActive(plan())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(activity.get()) as WorkoutActions
            actions.onEditValue(0, 0, SetField.WEIGHT, "7.5", false)
            actions.onEditFinished()
            val saved = SessionStore(app).loadActive().getOrThrow()!!.plannedSets.single()
            assertEquals(7.5, saved.weightKg!!, 0.0)
            val fields = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SET", fields[1])
            assertEquals("presentation-session", fields[2])
            assertEquals("7.5", fields[7])
            assertEquals("presentation-set", fields[13])
            assertEquals("presentation-attempt", fields[14])
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun skippingRestUsesTheSameNextExerciseRuleAsTheBackgroundTimer() {
        val first = plan().plannedSets.single()
        val resting = plan().copy(phase = WorkoutPhase.RESTING, restEndAtMs = System.currentTimeMillis() + 90_000,
            plannedSets = listOf(
                first.copy(completed = true, performedReps = 6),
                first.copy(exerciseIndex = 1, exerciseId = "flat_bench", completed = true, setId = "done-set", attemptId = "done-attempt"),
                first.copy(exerciseIndex = 2, exerciseId = "lat_pull_down", exerciseName = "랫풀다운", setId = "next-set", attemptId = "next-attempt")))
        SessionStore(app).saveActive(resting)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(activity.get()) as WorkoutActions
            actions.onSkipRest()
            val saved = SessionStore(app).loadActive().getOrThrow()!!
            assertEquals(2, saved.exerciseIndex)
            assertEquals(WorkoutPhase.WORKING, saved.phase)
            assertEquals(0L, saved.restEndAtMs)
            val fields = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SET", fields[1])
            assertEquals("lat_pull_down", fields[3])
            assertEquals("next-set", fields[13])
            assertEquals("next-attempt", fields[14])
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun resumingAfterChangingExerciseSendsTheSelectedSetToTheWatch() {
        val first = plan().plannedSets.single()
        SessionStore(app).saveActive(plan().copy(plannedSets = listOf(first,
            first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫풀다운",
                setId = "next-set", attemptId = "next-attempt"))))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(activity.get()) as WorkoutActions
            actions.onTogglePause()
            actions.onPageSettled(1)
            actions.onTogglePause()
            val saved = SessionStore(app).loadActive().getOrThrow()!!
            assertFalse(saved.paused)
            assertEquals(1, saved.exerciseIndex)
            val fields = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SET", fields[1])
            assertEquals("lat_pull_down", fields[3])
            assertEquals("next-set", fields[13])
            assertEquals("next-attempt", fields[14])
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun restingHighlightUsesCompletedSetIdentityInsteadOfNextExecutionCursor() {
        val completed = plan().plannedSets.single().copy(completed = true, performedReps = 10)
        val next = completed.copy(setIndex = 1, completed = false, performedReps = null,
            setId = "next-set", attemptId = "next-attempt")
        SessionStore(app).saveActive(plan().copy(setIndex = 1, phase = WorkoutPhase.RESTING,
            restEndAtMs = System.currentTimeMillis() + 90_000,
            plannedSets = listOf(completed, next),
            lastCompletedSetKey = SetIdentity.completionKey(plan().sessionId, completed)))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val instance = activity.get()
            val workout = MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.get(instance)!!
            val build = MainActivity::class.java.getDeclaredMethod("buildWorkoutModel", workout.javaClass).apply { isAccessible = true }
            val resting = build.invoke(instance, workout) as dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
            assertEquals(1, resting.exercises.single().currentSetIndex)
            assertEquals(0 to 0, resting.restingSet)
            MainActivity::class.java.getDeclaredField("activeRestEndAtMs").apply { isAccessible = true }.setLong(instance, 0L)
            val working = build.invoke(instance, workout) as dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
            assertNull(working.restingSet)
            assertEquals(1, working.exercises.single().currentSetIndex)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun browsingExercisesDuringRestKeepsTimerExecutionAndWatchCommand() = checkRestBrowsing(false)

    @Test fun browsingExercisesDuringPausedRestKeepsRemainingTimeAndExecution() = checkRestBrowsing(true)

    private fun checkRestBrowsing(pause: Boolean) {
        val first = plan().plannedSets.single().copy(completed = true, performedReps = 10)
        val resting = plan().copy(phase = WorkoutPhase.RESTING,
            restEndAtMs = System.currentTimeMillis() + 90_000,
            plannedSets = listOf(first,
                first.copy(setIndex = 1, completed = false, performedReps = null,
                    setId = "pending-set", attemptId = "pending-attempt"),
                first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫풀다운",
                    completed = false, performedReps = null, setId = "other-set", attemptId = "other-attempt")),
            lastCompletedSetKey = SetIdentity.completionKey(plan().sessionId, first))
        SessionStore(app).saveActive(resting)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val instance = activity.get()
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(instance) as WorkoutActions
            if (pause) actions.onTogglePause()
            val before = SessionStore(app).loadActive().getOrThrow()!!
            val pending = PhoneCommandOutbox(app).pending()
            val workout = MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.get(instance)!!
            val build = MainActivity::class.java.getDeclaredMethod("buildWorkoutModel", workout.javaClass).apply { isAccessible = true }
            for (index in listOf(1, 0, 1)) {
                actions.onPageSettled(index)
                assertEquals("Browsing must not change the durable workout", before, SessionStore(app).loadActive().getOrThrow())
                assertEquals("Browsing must not start a watch set", pending, PhoneCommandOutbox(app).pending())
                val model = build.invoke(instance, workout) as dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
                assertEquals(index, model.exerciseIndex)
                assertEquals(before.restEndAtMs, model.restEndAtMs)
                assertEquals(before.pausedRestRemainingMs, model.pausedRestRemainingMs)
            }
            actions.onPageSettled(0)
            actions.onEditValue(0, 0, SetField.REPS, "8", false)
            actions.onEditFinished()
            actions.onPageSettled(1)
            actions.onEditValue(1, 0, SetField.WEIGHT, "12.5", false)
            actions.onEditFinished()
            val edited = SessionStore(app).loadActive().getOrThrow()!!
            assertEquals(12.5, edited.plannedSets.last().weightKg!!, 0.0)
            assertEquals(8, edited.plannedSets.first().performedReps)
            assertEquals(before.phase, edited.phase)
            assertEquals(before.restEndAtMs, edited.restEndAtMs)
            assertEquals(before.pausedRestRemainingMs, edited.pausedRestRemainingMs)
            assertEquals(0, edited.exerciseIndex)
            assertEquals(pending, PhoneCommandOutbox(app).pending())
            if (pause) actions.onTogglePause()
            actions.onSkipRest()
            val started = SessionStore(app).loadActive().getOrThrow()!!
            assertEquals(0, started.exerciseIndex)
            assertEquals(1, started.setIndex)
            assertEquals(WorkoutPhase.WORKING, started.phase)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun calibrationCannotReplaceAnActiveWorkoutCommand() {
        SessionStore(app).saveActive(plan())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val before = PhoneCommandOutbox(app).pending()
            MainActivity::class.java.getDeclaredMethod("startOrStopCalibration").apply { isAccessible = true }.invoke(activity.get())
            assertNull(MainActivity::class.java.getDeclaredField("calibrationSessionId").apply { isAccessible = true }.get(activity.get()))
            assertEquals(before, PhoneCommandOutbox(app).pending())
            assertEquals(plan().sessionId, SessionStore(app).loadActive().getOrThrow()!!.sessionId)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun sensorViewRemainsReachableWhenNoWorkoutIsRunning() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            MainActivity::class.java.getDeclaredMethod("showSensorView").apply { isAccessible = true }.invoke(activity.get())
            assertEquals(ProbeActivity::class.java.name,
                org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity.component!!.className)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun sensorViewCannotCompeteWithAnActiveWorkout() {
        SessionStore(app).saveActive(plan())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            MainActivity::class.java.getDeclaredMethod("showSensorView").apply { isAccessible = true }.invoke(activity.get())
            assertNull(org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

}
