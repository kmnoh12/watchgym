package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetIdentityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(
        sessionId = "background-session", routineId = "shoulders", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false, setId = "set-a", attemptId = "attempt-a"),
            PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false, setId = "set-b", attemptId = "attempt-b")),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap(),
    )
    private fun nativeCount(session: String, count: Int?) {
        val fields = MutableList(24) { "" }
        fields[0] = "10"; fields[1] = count?.toString().orEmpty(); fields[2] = count?.toString().orEmpty()
        fields[11] = "0.9"; fields[12] = "test"; fields[13] = "WRIST_IMU_CAUTION"
        fields[14] = "generic-wrist"; fields[16] = "C_PERSONALIZED_AUTO_COUNT_BETA"
        val manifest = WireEnvelope(messageType = WireMessageType.CAPTURE_MANIFEST, sessionId = session,
            source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0, payload = mapOf(
                WirePayloadKeys.CAPTURE_ID to "count-capture", WirePayloadKeys.EXERCISE_ID to "external_rotation",
                WirePayloadKeys.EXERCISE_NAME to "외회전", WirePayloadKeys.SET_INDEX to "0"))
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit()
            .putString("analysis:count-capture", fields.joinToString("|"))
            .putString(CaptureInbox.manifestKey("count-capture"), WireCodec.encode(manifest)).commit()
    }
    private fun activity(saved: WorkoutSnapshot): Pair<MainActivity, Any> {
        val store = SessionStore(app); store.saveActive(saved)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, store)
        val active = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)!!
        MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.set(activity, active)
        return activity to active
    }
    private fun actions(activity: MainActivity) = MainActivity::class.java.getDeclaredField("workoutActions")
        .apply { isAccessible = true }.get(activity) as dev.kyungmin.kcoach.phone.ui.WorkoutActions
    @Test fun delayedAnalysisAfterDeletionCannotReplaceTheRemainingSet() {
        val saved = snapshot().copy(plannedSets = snapshot().plannedSets.map { it.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH) })
        val (activity, _) = activity(saved)
        actions(activity).onRemoveSet(0, 0)
        nativeCount(saved.sessionId, 6); FinishedWorkoutStore(app).reconcile(saved.sessionId)
        assertEquals(10, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.single().performedReps)
    }
    @Test fun firstAttemptAnalysisCannotReplaceARepeatedSet() {
        val saved = snapshot().copy(plannedSets = snapshot().plannedSets.map { it.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH) })
        val (activity, _) = activity(saved); actions(activity).onToggleSet(0, 0)
        val store = SessionStore(app); val undone = store.loadActive().getOrThrow()!!
        store.saveActive(undone.copy(plannedSets = undone.plannedSets.map { it.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH) }))
        nativeCount(saved.sessionId, 6); FinishedWorkoutStore(app).reconcile(saved.sessionId)
        assertEquals(10, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.first().performedReps)
    }
    private fun identifiedNative(session: String, id: String, attempt: String, index: Int, count: Int) {
        nativeCount(session, count)
        val prefs = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
        val manifest = WireCodec.decode(prefs.getString(CaptureInbox.manifestKey("count-capture"), null)!!)
        prefs.edit().putString(CaptureInbox.manifestKey("count-capture"), WireCodec.encode(manifest.copy(setId = id,
            payload = manifest.payload + mapOf(WirePayloadKeys.ATTEMPT_ID to attempt, WirePayloadKeys.SET_INDEX to index.toString())))).commit()
    }
    @Test fun stableIdMatchesASetAfterItsDisplayIndexChanges() {
        val saved = snapshot().copy(plannedSets = snapshot().plannedSets.mapIndexed { i, set ->
            set.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH,
                setId = "set-$i", attemptId = "attempt-$i", identityRequired = true)
        })
        val (activity, _) = activity(saved); actions(activity).onRemoveSet(0, 0)
        identifiedNative(saved.sessionId, "set-1", "attempt-1", 1, 6)
        FinishedWorkoutStore(app).reconcile(saved.sessionId)
        val remaining = SessionStore(app).loadActive().getOrThrow()!!.plannedSets.single()
        assertEquals("set-1", remaining.setId); assertEquals(0, remaining.setIndex); assertEquals(6, remaining.performedReps)
        identifiedNative(saved.sessionId, "set-0", "attempt-0", 0, 3); FinishedWorkoutStore(app).reconcile(saved.sessionId)
        assertEquals(6, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.single().performedReps)
    }
    @Test fun onlyTheNewAttemptCanUpdateARepeatedSet() {
        val saved = snapshot().copy(plannedSets = snapshot().plannedSets.mapIndexed { i, set ->
            set.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH, setId = "set-$i", attemptId = "old-$i", identityRequired = true)
        })
        val (activity, _) = activity(saved); actions(activity).onToggleSet(0, 0)
        val store = SessionStore(app); val undone = store.loadActive().getOrThrow()!!
        val attempt = undone.plannedSets.first().attemptId
        assertNotEquals("old-0", attempt)
        store.saveActive(undone.copy(plannedSets = undone.plannedSets.map { it.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH) }))
        identifiedNative(saved.sessionId, "set-0", "old-0", 0, 6); FinishedWorkoutStore(app).reconcile(saved.sessionId)
        assertEquals(10, store.loadActive().getOrThrow()!!.plannedSets.first().performedReps)
        identifiedNative(saved.sessionId, "set-0", attempt, 0, 6); FinishedWorkoutStore(app).reconcile(saved.sessionId)
        assertEquals(6, store.loadActive().getOrThrow()!!.plannedSets.first().performedReps)
    }
    @Test fun staleCompletionIsAcknowledgedAsDiscardedAndCannotStartRest() {
        val saved = snapshot().copy(plannedSets = snapshot().plannedSets.mapIndexed { i, set -> set.copy(setId = "set-$i", attemptId = "new-$i", identityRequired = true) })
        SessionStore(app).saveActive(saved)
        val commands = mutableListOf<Pair<String, String>>()
        val controller = PhoneSessionController(SessionStore(app)) { path, payload -> commands += path to payload }
        val payload = WearSync.liveState(saved.sessionId, "external_rotation", "rotation", 0, 10, 10, 5.0, 0,
            "SET_COMPLETE", false, completionId = "receipt", setId = "set-0", attemptId = "old-0")
        assertTrue(controller.receiveCompletion(payload, 1000).getOrThrow() is SetCompletionResult.Rejected)
        assertEquals(WorkoutPhase.WORKING, SessionStore(app).loadActive().getOrThrow()!!.phase)
        assertEquals(WearSync.PATH_SET_ACK, commands[0].first)
        assertEquals("old-0", WearSync.parse(commands[0].second)[6])
        assertEquals("SYNC", WearSync.parse(commands[1].second)[1])
    }
    @Test fun repeatedExerciseInstancesCannotCollideByIndex() {
        val saved = snapshot().copy(exerciseIndex = 1, plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, true, performedReps = 4, repSource = SetRepSource.WATCH, setId = "one", attemptId = "one-attempt", identityRequired = true),
            PlannedSetSnapshot(1, "external_rotation", 0, 10, 5.0, false, setId = "two", attemptId = "two-attempt", identityRequired = true)), performedReps = mapOf("external_rotation" to 4))
        val completed = WorkoutSessionReducer.completeSet(saved, SetCompletionEvent(saved.sessionId, "external_rotation", 0, 7, 1000, 0, setId = "two", attemptId = "two-attempt")) as SetCompletionResult.Applied
        assertEquals(11, completed.snapshot.performedReps["external_rotation"])
        assertEquals(4, completed.snapshot.plannedSets[0].performedReps)
        assertEquals(7, completed.snapshot.plannedSets[1].performedReps)
    }

    @Test fun deletingAndRestoringAnEarlierRowKeepsTheMeasuringSetSelected() {
        val saved = snapshot().copy(setIndex = 1, plannedSets = snapshot().plannedSets.mapIndexed { i, set ->
            set.copy(setId = "set-$i", attemptId = "attempt-$i", identityRequired = true)
        })
        val (activity, _) = activity(saved); actions(activity).onRemoveSet(0, 0)
        val removed = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(0, removed.setIndex)
        assertEquals("set-1", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[13])
        actions(activity).onUndoRemoveSet()
        val restored = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(1, restored.setIndex)
        assertEquals("set-1", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[13])
        assertEquals("attempt-1", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[14])
    }

    @Test fun concurrentNativeResultRestoresTheAuthoritativeLayoutInsteadOfAttachingToAnotherRow() {
        val saved = snapshot().copy(plannedSets = snapshot().plannedSets.mapIndexed { i, set ->
            set.copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH,
                setId = "set-$i", attemptId = "attempt-$i", identityRequired = true)
        })
        val (activity, active) = activity(saved)
        val routine = active.javaClass.getDeclaredField("routine").apply { isAccessible = true }.get(active) as WorkoutRoutine
        java.util.Collections.swap(routine.exercises[0].sets, 0, 1)
        val concurrent = SessionStore(app); val current = concurrent.loadActive().getOrThrow()!!
        assertTrue(concurrent.saveActive(current.copy(plannedSets = current.plannedSets.mapIndexed { i, set ->
            if (i == 0) set.copy(performedReps = 6, repSource = SetRepSource.PHONE_ANALYSIS) else set
        })))
        val persist = MainActivity::class.java.getDeclaredMethod("persistActiveWorkout", active.javaClass).apply { isAccessible = true }
        assertEquals(false, persist.invoke(activity, active))
        assertEquals(listOf("set-0", "set-1"), routine.exercises[0].sets.map { it.setId })
        assertEquals(listOf(6, 10), routine.exercises[0].sets.map { it.performedReps })
    }
    @Test fun leavingAnUnfinishedMeasurementChangesItsAttemptAndKeepsPause() {
        val first = snapshot().plannedSets.first().copy(setId = "first", attemptId = "first-attempt", identityRequired = true)
        val second = first.copy(exerciseIndex = 1, setId = "second", attemptId = "second-attempt")
        val saved = snapshot().copy(plannedSets = listOf(first, second), paused = true, phase = WorkoutPhase.PAUSED)
        val (activity, active) = activity(saved)
        val show = MainActivity::class.java.getDeclaredMethod("showWorkoutExercise", active.javaClass, Int::class.javaPrimitiveType).apply { isAccessible = true }
        try { assertEquals(true, show.invoke(activity, active, 1)) }
        catch (error: java.lang.reflect.InvocationTargetException) {
            // This isolated Activity has no UI shell; verify the durable transition before rendering.
            if (error.cause !is kotlin.UninitializedPropertyAccessException) throw error
        }
        val switched = SessionStore(app).loadActive().getOrThrow()!!
        assertNotEquals("first-attempt", switched.plannedSets[0].attemptId)
        assertEquals("second-attempt", switched.plannedSets[1].attemptId)
        assertEquals(WorkoutPhase.PAUSED, switched.phase)
        assertTrue(switched.paused)
    }

    @Test fun identicalExercisesKeepIndependentMeasuringPositionsWhenNavigating() {
        val sets = (0 until 2).flatMap { ei -> (0 until 2).map { si ->
            PlannedSetSnapshot(ei, "external_rotation", si, 10, 5.0, false,
                setId = "set-$ei-$si", attemptId = "attempt-$ei-$si", identityRequired = true)
        } }
        val (activity, active) = activity(snapshot().copy(plannedSets = sets, setIndex = 1))
        val show = MainActivity::class.java.getDeclaredMethod("showWorkoutExercise", active.javaClass, Int::class.javaPrimitiveType).apply { isAccessible = true }
        fun navigate(index: Int) {
            try { show.invoke(activity, active, index) }
            catch (error: java.lang.reflect.InvocationTargetException) {
                if (error.cause !is kotlin.UninitializedPropertyAccessException) throw error
            }
        }
        navigate(1)
        assertEquals(0, SessionStore(app).loadActive().getOrThrow()!!.setIndex)
        navigate(0)
        assertEquals(1, SessionStore(app).loadActive().getOrThrow()!!.setIndex)
    }

}
