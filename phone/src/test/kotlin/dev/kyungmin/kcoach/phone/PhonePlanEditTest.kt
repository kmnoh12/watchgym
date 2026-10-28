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

    @Test fun removeAndUndoPreserveCurrentSetIdentityAndAdvanceRestRevision() {
        val (store, before) = seed()
        val controller = PhoneSessionController(store) { _, _ -> }
        val removed = controller.editPlan({ before }, PhonePlanEdit.RemoveSet(0, 0)).getOrThrow().snapshot
        assertEquals(0, removed.setIndex)
        assertEquals("set-1", removed.plannedSets[removed.setIndex].setId)
        assertEquals(listOf(0, 1), removed.plannedSets.map { it.setIndex })
        val restored = controller.editPlan({ removed }, PhonePlanEdit.RestoreSet(before.plannedSets[0])).getOrThrow().snapshot
        assertEquals(before.plannedSets, restored.plannedSets)
        assertEquals(before.setIndex, restored.setIndex)
        assertTrue(restored.restRev > removed.restRev)
        assertEquals(before.restEndAtMs, restored.restEndAtMs)
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

    @Test fun aBackgroundCompletionIsPreservedWhenAnOlderScreenAddsASet() {
        val (store, seed) = seed()
        check(store.saveActive(seed.copy(setIndex = 0, phase = WorkoutPhase.WORKING, restEndAtMs = 0)))
        val before = store.loadActive().getOrThrow()!!
        val first = before.plannedSets.first()
        val service = SessionStore(app)
        val result = service.completeWatchSet(SetCompletionEvent(before.sessionId, first.exerciseId, first.setIndex,
            8, 0, 7, setId = first.setId, attemptId = first.attemptId), 1000).getOrThrow() as SetCompletionResult.Applied
        var sends = 0
        val edited = PhoneSessionController(store) { _, _ -> sends++ }
            .editPlan({ before }, PhonePlanEdit.AddSet(0)).getOrThrow().snapshot
        assertEquals(1, sends)
        assertEquals(4, edited.plannedSets.size)
        assertEquals(result.snapshot.plannedSets.first(), edited.plannedSets.first())
        assertEquals(result.snapshot.performedReps, edited.performedReps)
        assertEquals(result.snapshot.restEndAtMs, edited.restEndAtMs)
        assertEquals(result.snapshot.lastSequence, edited.lastSequence)
    }

    @Test fun anOlderScreenEditsTheSameExerciseAfterReorderingAndRejectsRemovedTargets() {
        val (store, seed) = seed()
        val other = seed.plannedSets.first().copy(exerciseIndex = 1, exerciseId = "other", setId = "other-set", attemptId = "other-attempt")
        check(store.saveActive(seed.copy(plannedSets = seed.plannedSets + other)))
        val before = store.loadActive().getOrThrow()!!
        val service = SessionStore(app)
        val latest = service.loadActive().getOrThrow()!!
        check(service.saveActive(latest.copy(exerciseIndex = 1, plannedSets = listOf(other.copy(exerciseIndex = 0)) +
            latest.plannedSets.filter { it.exerciseIndex == 0 }.map { it.copy(exerciseIndex = 1) })))
        val controller = PhoneSessionController(store) { _, _ -> }
        val edited = controller.editPlan({ before }, PhonePlanEdit.AddSet(0)).getOrThrow().snapshot
        assertEquals(1, edited.plannedSets.count { it.exerciseId == "other" })
        assertEquals(4, edited.plannedSets.count { it.exerciseId == "curl" })
        val removed = controller.editPlan({ edited }, PhonePlanEdit.RemoveSet(1, 0)).getOrThrow().snapshot
        assertTrue(controller.editPlan({ before }, PhonePlanEdit.DuplicateSet(0, 0)).isFailure)
        assertEquals(removed, store.loadActive().getOrThrow())
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

    @Test fun duplicateDuringRestSchedulesFreshCopyAndPreservesDeadlineAndRecordedSource() {
        val (store, seed) = seed()
        val source = seed.plannedSets[0].copy(completed = true, performedReps = 8,
            repSource = SetRepSource.MANUAL, rpe = "9", setType = "DROP", gripType = "뉴트럴")
        check(store.saveActive(seed.copy(plannedSets = listOf(source) + seed.plannedSets.drop(1),
            performedReps = mapOf("curl" to 8), lastCompletedSetKey = SetIdentity.completionKey(seed.sessionId, source))))
        val before = store.loadActive().getOrThrow()!!
        var sends = 0
        val saved = PhoneSessionController(store) { _, _ ->
            sends++
            assertEquals(4, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.size)
        }.editPlan({ before }, PhonePlanEdit.DuplicateSet(0, 0)).getOrThrow().snapshot
        val copy = saved.plannedSets[1]
        assertEquals(source, saved.plannedSets[0])
        assertEquals(source.weightKg, copy.weightKg)
        assertEquals(source.targetReps, copy.targetReps)
        assertEquals(source.setType, copy.setType)
        assertEquals(source.gripType, copy.gripType)
        assertEquals(source.restOverrideSeconds, copy.restOverrideSeconds)
        assertFalse(copy.completed)
        assertNull(copy.performedReps)
        assertNull(copy.repSource)
        assertNull(copy.rpe)
        assertNotEquals(source.setId, copy.setId)
        assertNotEquals(source.attemptId, copy.attemptId)
        assertEquals(copy.setId, WorkoutSessionReducer.nextPendingSet(saved)!!.setId)
        assertEquals(before.lastCompletedSetKey, saved.lastCompletedSetKey)
        assertEquals(before.performedReps, saved.performedReps)
        assertEquals(before.restEndAtMs, saved.restEndAtMs)
        assertEquals(1, sends)
    }

    @Test fun duplicateBeforeWorkingCursorKeepsMeasuringAttemptEvenWhenPaused() {
        val (store, seed) = seed()
        val controller = PhoneSessionController(store) { _, _ -> }
        for (paused in listOf(false, true)) {
            val working = seed.copy(phase = if (paused) WorkoutPhase.PAUSED else WorkoutPhase.WORKING,
                restEndAtMs = 0, paused = paused, pausedSinceMs = if (paused) 10 else 0)
            check(store.saveActive(working))
            val before = store.loadActive().getOrThrow()!!
            val saved = controller.editPlan({ before }, PhonePlanEdit.DuplicateSet(0, 0)).getOrThrow().snapshot
            assertEquals(2, saved.setIndex)
            assertEquals(before.plannedSets[1].setId, WorkoutSessionReducer.nextPendingSet(saved)!!.setId)
            assertEquals(before.plannedSets[1].attemptId, WorkoutSessionReducer.nextPendingSet(saved)!!.attemptId)
            assertEquals(paused, saved.paused)
        }
    }

    @Test fun addingAfterTheLastCompletedSetCanCompleteTheAdvertisedNextAttempt() {
        val (store, seed) = seed()
        val finished = seed.plannedSets.first().copy(completed = true, performedReps = 10, repSource = SetRepSource.WATCH)
        check(store.saveActive(seed.copy(setIndex = 0, plannedSets = listOf(finished),
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, performedReps = mapOf("curl" to 10),
            lastCompletedSetKey = SetIdentity.completionKey(seed.sessionId, finished))))
        val before = store.loadActive().getOrThrow()!!
        val controller = PhoneSessionController(store) { _, _ -> }
        val added = controller.editPlan({ before }, PhonePlanEdit.AddSet(0)).getOrThrow().snapshot
        val next = WorkoutSessionReducer.nextPendingSet(added)!!
        val result = store.completeWatchSet(SetCompletionEvent(added.sessionId, next.exerciseId, next.setIndex, 10, 0, 0,
            setId = next.setId, attemptId = next.attemptId), 1000).getOrThrow()
        assertTrue("the exact next attempt sent to the wrist must be completable: $result", result is SetCompletionResult.Applied)
    }

}
