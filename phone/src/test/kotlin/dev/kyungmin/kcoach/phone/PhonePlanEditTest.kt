package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhonePlanEditTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private fun seed(): Pair<SessionStore, WorkoutSnapshot> {
        val store = SessionStore(app)
        val snapshot = WorkoutSnapshot(sessionId = "plan", routineId = "routine", exerciseIndex = 0, setIndex = 1,
            plannedSets = (0..2).map { i -> PlannedSetSnapshot(0, "curl", i, 10, 20.0, false,
                exerciseName = "컬", setId = "set-$i", attemptId = "attempt-$i", identityRequired = true,
                restSeconds = if (i == 0) 45 else 90, restOverrideSeconds = if (i == 0) 45 else null) },
            performedReps = mapOf("curl" to 0), phase = WorkoutPhase.RESTING, restEndAtMs = 100_000,
            pausedRestRemainingMs = 0, paused = false, startedAtMs = 1, pausedSinceMs = 0,
            totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
        check(store.saveActive(snapshot))
        return store to store.loadActive().getOrThrow()!!
    }

    @Test fun addUsesNewIdentityAndSyncsOnlyAfterDurableSave() {
        val (store, before) = seed()
        var sends = 0
        val controller = PhoneSessionController(store) { path, payload ->
            sends++
            assertEquals(WearSync.PATH_COMMAND, path)
            assertEquals(4, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.size)
            assertEquals("SYNC", WearSync.parse(payload)[1])
        }
        val result = controller.editPlan({ before }, PhonePlanEdit.AddSet(0)).getOrThrow()
        assertNull(result.syncFailure)
        val saved = result.snapshot
        assertEquals(1, sends)
        assertEquals(before.plannedSets, saved.plannedSets.take(3))
        assertFalse(before.plannedSets.any { it.setId == saved.plannedSets.last().setId })
        assertEquals(before.restEndAtMs, saved.restEndAtMs)
    }

    @Test fun removeAndUndoPreserveCurrentSetIdentityAndRestRevision() {
        val (store, before) = seed()
        val controller = PhoneSessionController(store) { _, _ -> }
        val removed = controller.editPlan({ before }, PhonePlanEdit.RemoveSet(0, 0)).getOrThrow().snapshot
        assertEquals(0, removed.setIndex)
        assertEquals("set-1", removed.plannedSets[removed.setIndex].setId)
        assertEquals(listOf(0, 1), removed.plannedSets.map { it.setIndex })
        val restored = controller.editPlan({ removed }, PhonePlanEdit.RestoreSet(before.plannedSets[0])).getOrThrow().snapshot
        assertEquals(before.plannedSets, restored.plannedSets)
        assertEquals(before.setIndex, restored.setIndex)
        assertEquals(before.restRev, restored.restRev)
    }

    @Test fun changingNextSetDuringRestReturnsCommittedRestVersion() {
        val (store, before) = seed()
        val result = PhoneSessionController(store) { _, _ -> }.editPlan({ before }, PhonePlanEdit.RemoveSet(0, 1)).getOrThrow()
        assertTrue(result.snapshot.restRev > before.restRev)
        assertEquals(SessionStore(app).loadActive().getOrThrow(), result.snapshot)
    }

    @Test fun presetsRespectOverridesAndNeverMoveTheRunningDeadline() {
        val (store, before) = seed()
        val controller = PhoneSessionController(store) { _, _ -> }
        val exercise = controller.editPlan({ before }, PhonePlanEdit.ExerciseRest(0, 120)).getOrThrow().snapshot
        assertEquals(listOf(45, 120, 120), exercise.plannedSets.map { it.restSeconds })
        val set = controller.editPlan({ exercise }, PhonePlanEdit.SetRest(0, 1, 60)).getOrThrow().snapshot
        assertEquals(60, set.plannedSets[1].restSeconds)
        val reset = controller.editPlan({ set }, PhonePlanEdit.SetRest(0, 1, null)).getOrThrow().snapshot
        assertEquals(120, reset.plannedSets[1].restSeconds)
        assertNull(reset.plannedSets[1].restOverrideSeconds)
        assertEquals(before.restEndAtMs, reset.restEndAtMs)
        assertEquals(before.restRev, reset.restRev)
        val adjusted = store.adjustRestDuration(before.sessionId, 15, 50_000).getOrThrow()!!
        assertEquals(reset.plannedSets, adjusted.plannedSets)
        assertEquals(before.restEndAtMs + 15_000, adjusted.restEndAtMs)
    }

    @Test fun staleRevisionRejectsEveryIntentWithoutSendingOrOverwriting() {
        val (store, before) = seed()
        val service = SessionStore(app)
        val newest = service.loadActive().getOrThrow()!!.copy(lastSequence = 7)
        check(service.saveActive(newest))
        var sends = 0
        val controller = PhoneSessionController(store) { _, _ -> sends++ }
        val edits = listOf(PhonePlanEdit.AddSet(0), PhonePlanEdit.RemoveSet(0, 0),
            PhonePlanEdit.RestoreSet(before.plannedSets[0].copy(setId = "restore")),
            PhonePlanEdit.SetRest(0, 0, 60), PhonePlanEdit.ExerciseRest(0, 120))
        edits.forEach {
            assertTrue(controller.editPlan({ before }, it).isFailure)
            assertEquals(newest, SessionStore(app).loadActive().getOrThrow())
        }
        assertEquals(0, sends)
    }

    @Test fun invalidCandidateAndInvalidIntentCannotSaveOrSend() {
        val (store, before) = seed()
        val controller = PhoneSessionController(store) { _, _ -> fail("must not send") }
        assertTrue(controller.editPlan({ before.copy(plannedSets = before.plannedSets.map { it.copy(weightKg = Double.NaN) }) }, PhonePlanEdit.AddSet(0)).isFailure)
        assertTrue(controller.editPlan({ before }, PhonePlanEdit.ExerciseRest(0, -1)).isFailure)
        assertTrue(controller.editPlan({ before }, PhonePlanEdit.RemoveSet(0, 99)).isFailure)
        assertTrue(controller.editPlan({ before }, PhonePlanEdit.RestoreSet(before.plannedSets[0])).isFailure)
        assertTrue(controller.editPlan({ before }, PhonePlanEdit.RestoreSet(before.plannedSets[0].copy(setId = "other", exerciseId = "changed"))).isFailure)
        assertEquals(before, store.loadActive().getOrThrow())
    }

    @Test fun transportFailureReturnsDurableSnapshotAndExplicitError() {
        val (store, before) = seed()
        val failure = IllegalStateException("transport unavailable")
        val result = PhoneSessionController(store) { _, _ -> throw failure }
            .editPlan({ before }, PhonePlanEdit.AddSet(0)).getOrThrow()
        assertSame(failure, result.syncFailure)
        assertEquals(4, result.snapshot.plannedSets.size)
        assertEquals(result.snapshot, store.loadActive().getOrThrow())
    }
    @Test fun finalSetAndBusyCaptureCannotChangePlan() {
        val (store, before) = seed()
        val single = before.copy(setIndex = 0, plannedSets = before.plannedSets.take(1))
        check(store.saveActive(single))
        val committed = store.loadActive().getOrThrow()!!
        val controller = PhoneSessionController(store) { _, _ -> fail("must not send") }
        assertTrue(controller.editPlan({ single }, PhonePlanEdit.RemoveSet(0, 0)).isFailure)
        app.getSharedPreferences("calibration-captures", 0).edit().putString("active", "busy").commit()
        assertTrue(controller.editPlan({ single }, PhonePlanEdit.AddSet(0)).isFailure)
        assertEquals(committed, store.loadActive().getOrThrow())
    }

}
