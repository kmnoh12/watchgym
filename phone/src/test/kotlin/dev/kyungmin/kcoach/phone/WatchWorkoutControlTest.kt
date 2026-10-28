package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchWorkoutControlTest {
    private fun snapshot() = WorkoutSnapshot(sessionId = "watch-session", routineId = "routine", routineName = "수동 루틴",
        exerciseIndex = 0, setIndex = 0, plannedSets = listOf(0, 1).map { PlannedSetSnapshot(0, "leg_press", it, 12, 30.0,
            false, exerciseName = "레그 프레스", restSeconds = 60, setId = "set-$it", attemptId = "attempt-$it", identityRequired = true) },
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 0, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())

    @Test fun preparationDoesNotStartTheClockAndObservedRequestStartsTheFrozenPlanOnce() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        assertNull(store.loadActive().getOrThrow())
        val command = WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId, offer.setId,
            offer.attemptId, offer.planHash)
        val sent = mutableListOf<Pair<String, String>>()
        val controller = WatchWorkoutController(app) { path, value -> sent += path to value }
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(command.encode(), 200).getOrThrow().ack.status)
        assertEquals(200L, store.loadActive().getOrThrow()!!.startedAtMs)
        assertNull(store.preparedForWatch(201).getOrThrow())
        assertEquals("VERIFIED_PATTERN", WearSync.parse(sent.single { it.first == WearSync.PATH_COMMAND }.second)[18])
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, WatchWorkoutController(app) { _, _ -> }
            .receive(command.encode(), 300).getOrThrow().ack.status)
        assertEquals(200L, store.loadActive().getOrThrow()!!.startedAtMs)
    }

    @Test fun stalePlanCameraOwnershipAndExpiredOfferDoNotStartAWorkout() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        fun command(id: String, hash: String = offer.planHash) = WatchWorkoutControl(id, WatchWorkoutAction.START_PREPARED,
            offer.sessionId, offer.setId, offer.attemptId, hash)
        val controller = WatchWorkoutController(app) { _, _ -> }
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(command("wrong", "a".repeat(64)).encode(), 200).getOrThrow().ack.status)
        app.getSharedPreferences("calibration-captures", 0).edit().putString("active", "camera").commit()
        assertEquals(WatchWorkoutControlStatus.BUSY, controller.receive(command("camera").encode(), 200).getOrThrow().ack.status)
        assertNull(store.loadActive().getOrThrow())
        app.getSharedPreferences("calibration-captures", 0).edit().remove("active").commit()
        assertEquals(WatchWorkoutControlStatus.NO_PLAN, controller.receive(command("expired").encode(), offer.expiresAtMs + 1).getOrThrow().ack.status)
        assertNull(store.loadActive().getOrThrow())
    }

    @Test fun manualZeroThenNextSetIsAtomicAndOfflineReplayCannotSkipAnotherSet() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        val controller = WatchWorkoutController(app) { _, _ -> }
        controller.receive(WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId,
            offer.setId, offer.attemptId, offer.planHash, "RIGHT", "INSIDE").encode(), 200).getOrThrow()
        assertEquals(WorkoutWearingSelection("RIGHT", "INSIDE"), WorkoutWearingStore(app).selection(offer.sessionId).getOrThrow())
        store.applyManualSet(ManualSetCommand("count", offer.sessionId, "leg_press", 0, "set-0", "attempt-0", 0,
            0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val next = WatchWorkoutControl("next", WatchWorkoutAction.NEXT_SET, offer.sessionId, "set-1", "attempt-1")
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(next.encode(), 400).getOrThrow().ack.status)
        val after = store.loadActive().getOrThrow()!!
        assertEquals(WorkoutPhase.WORKING, after.phase)
        assertEquals(0, after.plannedSets[0].performedReps)
        store.applyManualSet(ManualSetCommand("count2", offer.sessionId, "leg_press", 1, "set-1", "attempt-1", 5,
            0, ManualSetOperation.COMPLETE), 500).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, WatchWorkoutController(app) { _, _ -> }
            .receive(next.encode(), 600).getOrThrow().ack.status)
        assertEquals(WorkoutPhase.RESTING, store.loadActive().getOrThrow()!!.phase)
        assertEquals(WatchWorkoutControlStatus.REJECTED, controller.receive(next.copy(action = WatchWorkoutAction.NEXT_SET,
            setId = "set-0", attemptId = "attempt-0").encode(), 700).getOrThrow().ack.status)
    }
    @Test fun restAndSetEditsPersistResyncAndRejectOtherSessionsOrAttempts() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        val sent = mutableListOf<Pair<String, String>>()
        val controller = WatchWorkoutController(app) { path, value -> sent += path to value }
        controller.receive(WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId,
            offer.setId, offer.attemptId, offer.planHash).encode(), 200).getOrThrow()
        store.applyManualSet(ManualSetCommand("count", offer.sessionId, "leg_press", 0, "set-0", "attempt-0", 8,
            0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val adjust = WatchWorkoutControl("adjust", WatchWorkoutAction.REST_ADJUST, offer.sessionId, "set-1", "attempt-1",
            deltaSeconds = 10, restSeconds = 70, restEndAtMs = 70_300, seq = 1)
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(adjust.encode(), 400).getOrThrow().ack.status)
        assertEquals(70_300L, SessionStore(app).loadActive().getOrThrow()!!.restEndAtMs)
        assertEquals(60, store.loadActive().getOrThrow()!!.plannedSets[0].restSeconds)
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, controller.receive(adjust.encode(), 500).getOrThrow().ack.status)
        val previous = WatchWorkoutControl("previous", WatchWorkoutAction.EDIT_SET, offer.sessionId, "set-1", "attempt-1",
            which = "PREVIOUS", reps = 0, weightKg = 35.0)
        controller.receive(previous.encode(), 600).getOrThrow()
        val next = previous.copy(commandId = "edit-next", which = "NEXT", reps = 9, weightKg = 40.0)
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(next.encode(), 700).getOrThrow().ack.status)
        val saved = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(0, saved.plannedSets[0].performedReps)
        assertEquals(SetRepSource.MANUAL, saved.plannedSets[0].repSource)
        assertEquals(35.0, saved.plannedSets[0].weightKg!!, 0.0)
        assertEquals(0, saved.performedReps["leg_press"])
        assertEquals(9, saved.plannedSets[1].targetReps)
        assertEquals(40.0, saved.plannedSets[1].weightKg!!, 0.0)
        val sync = WearSync.parse(sent.last { it.first == WearSync.PATH_COMMAND }.second)
        assertEquals("SYNC", sync[1]); assertEquals("9", sync[6]); assertEquals("40.0", sync[7])
        assertEquals("70300", sync[9])
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(next.copy(commandId = "wrong-session", sessionId = "other").encode(), 800).getOrThrow().ack.status)
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(next.copy(commandId = "wrong-attempt", attemptId = "old").encode(), 800).getOrThrow().ack.status)
        assertEquals(saved, store.loadActive().getOrThrow())
    }

    private fun restingStore(): SessionStore {
        val store = SessionStore(RuntimeEnvironment.getApplication())
        assertTrue(store.saveActive(snapshot().copy(startedAtMs = 100)))
        store.applyManualSet(ManualSetCommand("rest-count", "watch-session", "leg_press", 0,
            "set-0", "attempt-0", 8, 0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        return store
    }

    @Test fun newestAbsoluteRestWinsAndOldOrRepeatedSequencesDoNotChangeState() {
        val store = restingStore()
        val initial = store.loadActive().getOrThrow()!!
        assertEquals(1L, initial.restRev)
        val latest = WatchWorkoutControl("latest", WatchWorkoutAction.REST_ADJUST, "watch-session", "set-1", "attempt-1",
            deltaSeconds = 10, restSeconds = 90, restEndAtMs = 90_300, seq = 3)
        val applied = store.applyWatchControl(latest, 400).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.APPLIED, applied.ack.status)
        val saved = store.loadActive().getOrThrow()!!
        assertEquals(saved, applied.snapshot)
        assertEquals(90_300L, saved.restEndAtMs); assertEquals(3L, saved.watchRestSeq)
        assertEquals(initial.restRev + 1, saved.restRev)
        for (seq in listOf(1L, 2L, 3L)) {
            assertEquals(WatchWorkoutControlStatus.STALE, store.applyWatchControl(latest.copy(commandId = "old-$seq",
                seq = seq, restEndAtMs = 10_000), 500).getOrThrow().ack.status)
            assertEquals(saved, store.loadActive().getOrThrow())
        }
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, store.applyWatchControl(latest, 600).getOrThrow().ack.status)
        assertEquals(saved, store.loadActive().getOrThrow())
    }

    @Test fun aTwoHundredAdjustBurstKeepsTheNewestDeadlineAndNeverFails() {
        val store = restingStore()
        var now = 400L
        var end = store.loadActive().getOrThrow()!!.restEndAtMs
        repeat(200) { i ->
            end += if (i % 2 == 0) 10_000 else -10_000
            val command = WatchWorkoutControl("burst-$i", WatchWorkoutAction.REST_ADJUST, "watch-session", "set-1", "attempt-1",
                deltaSeconds = if (i % 2 == 0) 10 else -10, restSeconds = 60, restEndAtMs = end, seq = (i + 1).toLong())
            val result = store.applyWatchControl(command, now)
            assertTrue("burst command $i must not fail: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(WatchWorkoutControlStatus.APPLIED, result.getOrThrow().ack.status)
            now += 20
        }
        val saved = store.loadActive().getOrThrow()!!
        assertEquals(end, saved.restEndAtMs)
        assertEquals(200L, saved.watchRestSeq)
    }

    @Test fun restPauseResumeAndPhoneEditsShareOneDurableRevisionAndDeadline() {
        val store = restingStore()
        val pause = WatchWorkoutControl("pause", WatchWorkoutAction.REST_PAUSE, "watch-session", "set-1", "attempt-1",
            deltaSeconds = 0, restSeconds = 60, restEndAtMs = 0, seq = 1, pausedRestRemainingMs = 51_234)
        val frozen = store.applyWatchControl(pause, 500).getOrThrow().snapshot!!
        assertTrue(frozen.paused); assertEquals(WorkoutPhase.PAUSED, frozen.phase)
        assertEquals(0L, frozen.restEndAtMs); assertEquals(51_234L, frozen.pausedRestRemainingMs)
        val resume = pause.copy(commandId = "resume", action = WatchWorkoutAction.REST_RESUME,
            restEndAtMs = 52_234, seq = 3, pausedRestRemainingMs = null)
        val resumed = store.applyWatchControl(resume, 1_000).getOrThrow().snapshot!!
        assertFalse(resumed.paused); assertEquals(WorkoutPhase.RESTING, resumed.phase)
        assertEquals(52_234L, resumed.restEndAtMs); assertEquals(frozen.restRev + 1, resumed.restRev)
        assertEquals(WatchWorkoutControlStatus.STALE, store.applyWatchControl(pause.copy(commandId = "late-pause", seq = 2), 1_100).getOrThrow().ack.status)
        val edited = SessionStore(RuntimeEnvironment.getApplication()).adjustRestDuration("watch-session", 10, 1_200).getOrThrow()!!
        assertEquals(62_234L, edited.restEndAtMs); assertEquals(resumed.restRev + 1, edited.restRev)
        val phonePaused = store.setPaused("watch-session", true, 2_000).getOrThrow()!!
        assertEquals(60_234L, phonePaused.pausedRestRemainingMs)
        val phoneResumed = store.setPaused("watch-session", false, 3_000).getOrThrow()!!
        assertEquals(63_234L, phoneResumed.restEndAtMs)
        assertEquals(edited.restRev + 2, phoneResumed.restRev)
        assertEquals(3L, phoneResumed.watchRestSeq)
        val next = store.advanceAfterRest(3_100, skip = true).getOrThrow()!!
        assertEquals(phoneResumed.restRev + 1, next.restRev); assertEquals(0L, next.restEndAtMs)
        assertTrue(store.saveActive(next))
        assertEquals(next.restRev, store.loadActive().getOrThrow()!!.restRev)
    }

    @Test fun currentWeightPersistsAndResyncsWithoutChangingRepsAndRejectsStaleOrRestingEdits() {
        val app = RuntimeEnvironment.getApplication()
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot().copy(startedAtMs = 100)))
        val sent = mutableListOf<Pair<String, String>>()
        val controller = WatchWorkoutController(app) { path, value -> sent += path to value }
        val edit = WatchWorkoutControl("current", WatchWorkoutAction.EDIT_SET, "watch-session", "set-0", "attempt-0",
            which = "CURRENT", reps = -1, weightKg = 35.0)
        assertEquals(edit, WatchWorkoutControl.decode(edit.encode()))
        assertTrue(runCatching { edit.copy(reps = 8) }.isFailure)
        assertTrue(runCatching { edit.copy(weightKg = null) }.isFailure)
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(edit.encode(), 200).getOrThrow().ack.status)
        val saved = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(snapshot().copy(startedAtMs = 100,
            plannedSets = snapshot().plannedSets.mapIndexed { index, set -> if (index == 0) set.copy(weightKg = 35.0) else set }), saved)
        val sync = WearSync.parse(sent.last { it.first == WearSync.PATH_COMMAND }.second)
        assertEquals("SYNC", sync[1]); assertEquals("12", sync[6]); assertEquals("35.0", sync[7])
        assertEquals("WORKING", sync[11])
        assertEquals(WatchWorkoutControlStatus.DUPLICATE,
            controller.receive(edit.encode(), 250).getOrThrow().ack.status)
        for (invalid in listOf(edit.copy(commandId = "session", sessionId = "other"),
            edit.copy(commandId = "attempt", attemptId = "old"))) {
            assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(invalid.encode(), 260).getOrThrow().ack.status)
        }
        assertEquals(saved, store.loadActive().getOrThrow())
        store.applyManualSet(ManualSetCommand("count-current", edit.sessionId, "leg_press", 0,
            edit.setId, edit.attemptId, 8, 0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val resting = store.loadActive().getOrThrow()!!
        assertEquals(35.0, resting.plannedSets[0].weightKg!!, 0.0)
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(edit.copy(commandId = "rest-current",
            setId = "set-1", attemptId = "attempt-1").encode(), 400).getOrThrow().ack.status)
        assertEquals(resting, store.loadActive().getOrThrow())
    }

    @Test fun finalRestUsesEmptyNextIdentityAndStillEditsPrevious() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val plan = snapshot().copy(plannedSets = snapshot().plannedSets.take(1))
        val offer = store.prepareForWatch(plan, 100).getOrThrow()
        val controller = WatchWorkoutController(app) { _, _ -> }
        controller.receive(WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId,
            offer.setId, offer.attemptId, offer.planHash).encode(), 200).getOrThrow()
        store.applyManualSet(ManualSetCommand("count", offer.sessionId, "leg_press", 0, "set-0", "attempt-0", 8,
            0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val edit = WatchWorkoutControl("edit", WatchWorkoutAction.EDIT_SET, offer.sessionId, "", "",
            which = "PREVIOUS", reps = 7)
        val result = controller.receive(edit.encode(), 400).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.APPLIED, result.ack.status)
        assertEquals(result.ack, WatchWorkoutControlAck.decode(result.ack.encode()))
        assertEquals(7, store.loadActive().getOrThrow()!!.plannedSets.single().performedReps)
        assertEquals(WatchWorkoutControlStatus.REJECTED,
            controller.receive(edit.copy(commandId = "no-next", which = "NEXT").encode(), 500).getOrThrow().ack.status)
    }

    @Test fun zeroSecondRestPauseIsResumableAndLateBoundaryPauseFollowsTheSameNextSet() {
        val store = restingStore()
        val before = store.loadActive().getOrThrow()!!
        val pause = WatchWorkoutControl("zero-pause", WatchWorkoutAction.REST_PAUSE, "watch-session", "set-1", "attempt-1",
            deltaSeconds = 0, restSeconds = 60, restEndAtMs = 0, seq = 1, pausedRestRemainingMs = 0)
        val frozen = store.applyWatchControl(pause, before.restEndAtMs).getOrThrow().snapshot!!
        assertEquals(1L, frozen.pausedRestRemainingMs)
        val resume = pause.copy(commandId = "zero-resume", action = WatchWorkoutAction.REST_RESUME,
            restEndAtMs = before.restEndAtMs + 1, pausedRestRemainingMs = null, seq = 2)
        assertEquals(WatchWorkoutControlStatus.APPLIED, store.applyWatchControl(resume, before.restEndAtMs).getOrThrow().ack.status)
        val working = store.advanceAfterRest(before.restEndAtMs + 2).getOrThrow()!!
        val late = store.applyWatchControl(pause.copy(commandId = "boundary-pause", seq = 3), before.restEndAtMs + 3).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.APPLIED, late.ack.status)
        assertTrue(late.snapshot!!.paused)
        assertEquals(0L, late.snapshot!!.pausedRestRemainingMs)
        assertEquals(working.plannedSets, late.snapshot!!.plannedSets)
        val continued = store.applyWatchControl(resume.copy(commandId = "boundary-resume", seq = 4), before.restEndAtMs + 4).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.APPLIED, continued.ack.status)
        assertFalse(continued.snapshot!!.paused)
        assertEquals(WorkoutPhase.WORKING, continued.snapshot!!.phase)
        assertEquals(working.plannedSets, continued.snapshot!!.plannedSets)
    }

}
