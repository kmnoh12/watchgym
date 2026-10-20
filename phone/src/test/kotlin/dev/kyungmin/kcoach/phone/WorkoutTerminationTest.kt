package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutTerminationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(sessionId = "task-removed", routineId = "routine", exerciseIndex = 0, setIndex = 1,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, true, performedReps = 12, repSource = SetRepSource.WATCH),
            PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false)), performedReps = mapOf("external_rotation" to 12),
        phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
        startedAtMs = 1000, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())

    @Test fun removingTaskDiscardsCompletedTwelveAndStopsService() {
        val store = SessionStore(app); assertTrue(store.saveActive(snapshot()))
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        // Android always delivers a start request after onCreate; stops are valid only against it.
        controller.get().onStartCommand(Intent(), 0, 1)
        controller.get().onTaskRemoved(Intent())
        assertNull(store.loadActive().getOrThrow())
        assertTrue(store.isSessionClosed("task-removed"))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
        assertNoHistory()
        assertFalse(WorkoutTermination(app).pending())
        controller.destroy()
    }
    @Test fun ordinaryServiceDestructionKeepsRecoverableSession() {
        SessionStore(app).saveActive(snapshot())
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.destroy()
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).isEmpty())
    }
    @Test fun confirmedStopIsIdempotentAndDoesNotFinishAnActivity() {
        SessionStore(app).saveActive(snapshot())
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        assertTrue(WorkoutTermination(activity).stop(saveCompleted = false).isSuccess)
        assertTrue(WorkoutTermination(activity).stop(saveCompleted = false).isSuccess)
        assertFalse(activity.isFinishing)
        assertNoHistory()
    }
    @Test fun journalRecoversBeforeRestoringAClosedSession() {
        SessionStore(app).saveActive(snapshot())
        app.getSharedPreferences("workout-termination", 0).edit().putString("session", "task-removed").putBoolean("saveCompleted", false).commit()
        assertTrue(WorkoutTermination(app).recover().isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertNoHistory()
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
    }
    @Test fun discardDoesNotDependOnFinishedStoreAndCannotBeRevivedByStaleWriter() {
        val stale = SessionStore(app)
        assertTrue(stale.saveActive(snapshot()))
        app.getSharedPreferences("finished-workouts", 0).edit().putString("session:unrelated", "broken-json").commit()
        assertTrue(WorkoutTermination(app).stop(saveCompleted = false).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertFalse(WorkoutTermination(app).pending())
        assertFalse(stale.saveActive(snapshot()))
        assertFalse(SessionStore(app).saveActive(snapshot()))
        assertNull(WorkoutTimeStore(app).read("task-removed"))
        assertNoHistory()
    }

    @Test fun normalCompletionStillSavesCompletedSets() {
        val completed = snapshot().copy(plannedSets = snapshot().plannedSets.take(1))
        assertTrue(SessionStore(app).saveActive(completed))
        FinishedWorkoutStore(app).finish(completed, "운동", 1)
        assertTrue(SessionStore(app).clearActive(completed.sessionId))
        assertEquals(12, FinishedWorkoutStore(app).evidenceForSessions(setOf(completed.sessionId)).single().snapshot.plannedSets.single().performedReps)
        assertEquals(1, WorkoutLogStore(app).load().size)
        assertEquals(1, ExerciseHistoryStore(app).load().recent("external_rotation").size)
    }

    @Test fun lateAnalysisReconciliationAndFinishedReplayCannotCreateDiscardedHistory() {
        SessionStore(app).saveActive(snapshot())
        assertTrue(WorkoutTermination(app).stop(saveCompleted = false).isSuccess)
        val manifest = WireEnvelope(messageType = WireMessageType.CAPTURE_MANIFEST,
            sessionId = "task-removed", source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0,
            payload = mapOf(WirePayloadKeys.CAPTURE_ID to "late-discarded", WirePayloadKeys.EXERCISE_ID to "external_rotation", WirePayloadKeys.SET_INDEX to "0"))
        val encoded = MutableList(24) { "" }.apply {
            this[0] = "12"; this[1] = "13"; this[2] = "13"; this[11] = "0.9"
        }.joinToString("|")
        app.getSharedPreferences("wear-sync", 0).edit()
            .putString(CaptureInbox.manifestKey("late-discarded"), WireCodec.encode(manifest)).commit()
        SetAnalysisStore(app).persistAnalysis(manifest, encoded)
        val sent = mutableListOf<String>()
        val controller = PhoneSessionController(SessionStore(app)) { path, _ -> sent += path }
        val completion = WearSync.liveState("task-removed", "external_rotation", "외회전", 0, 12, 10, 5.0,
            100, "SET_COMPLETE", false, completionId = "late-completion")
        assertNull(controller.receiveCompletion(completion, 70_000).getOrThrow())
        assertEquals(listOf(WearSync.PATH_SET_ACK), sent)
        FinishedWorkoutStore(app).reconcile("task-removed")
        FinishedWorkoutStore(app).replay()
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertNoHistory()
    }

    @Test fun saveCompletedStopPreservesTwelveAndIsIdempotent() {
        SessionStore(app).saveActive(snapshot())
        assertTrue(WorkoutTermination(app).stop(saveCompleted = true, nowMs = 61_000).isSuccess)
        assertTrue(WorkoutTermination(app).stop(saveCompleted = true).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        val saved = FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).single()
        assertEquals(12, saved.snapshot.plannedSets.first().performedReps)
        assertFalse(saved.snapshot.plannedSets.last().completed)
        assertEquals(1, saved.minutes)
        assertEquals(1, WorkoutLogStore(app).load().size)
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
    }

    @Test fun saveCompletedStopWithNoCompletedSetsCreatesNoHistory() {
        SessionStore(app).saveActive(snapshot().copy(plannedSets = snapshot().plannedSets.takeLast(1), performedReps = emptyMap()))
        assertTrue(WorkoutTermination(app).stop(saveCompleted = true).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertNull(WorkoutTimeStore(app).read("task-removed"))
        assertNoHistory()
    }

    @Test fun failedSaveKeepsSnapshotAndChosenPolicyForRecovery() {
        SessionStore(app).saveActive(snapshot())
        val prefs = app.getSharedPreferences("finished-workouts", 0)
        prefs.edit().putString("session:task-removed", "broken-json").commit()
        assertTrue(WorkoutTermination(app).stop(saveCompleted = true).isFailure)
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(WorkoutTermination(app).pending())
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
        prefs.edit().remove("session:task-removed").commit()
        assertTrue(WorkoutTermination(app).recover(61_000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertEquals(12, FinishedWorkoutStore(app).allEvidence().single().snapshot.plannedSets.first().performedReps)
    }

    @Test fun discardedWorkoutIgnoresSavedActivityTabAndReturnsHome() {
        SessionStore(app).saveActive(snapshot())
        WorkoutTermination(app).stop(saveCompleted = false).getOrThrow()
        val state = Bundle().apply { putString("visibleRootTab", "STATS") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(state)
        try {
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(controller.get())
            assertNull(field("activeWorkout"))
            assertEquals("HOME", field("currentTab").toString())
            assertEquals("ROOT", field("currentScreen").toString())
        } finally { controller.destroy() }
    }

    private fun assertNoHistory() {
        assertTrue(FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).isEmpty())
        assertTrue(WorkoutLogStore(app).load().isEmpty())
        assertTrue(ExerciseHistoryStore(app).load().recent("external_rotation").isEmpty())
        assertTrue(FinishedWorkoutStore(app).recordHits("task-removed").isEmpty())
    }
}
