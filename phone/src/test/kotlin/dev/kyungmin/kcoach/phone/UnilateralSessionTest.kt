package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnilateralSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun initial() = WorkoutSnapshot(sessionId = "s", routineId = "r", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "one_arm_dumbbell_row", 0, 10, 20.0, false,
            restSeconds = 60, setId = "set", attemptId = "a", identityRequired = true)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())
    private fun completion() = WearSync.liveState("s", "one_arm_dumbbell_row", "원암 덤벨 로우", 0, 12, 10,
        20.0, 80, "SET_COMPLETE", false, completionId = "completion", setId = "set", attemptId = "a")
    private fun command(action: WatchWorkoutAction, id: String = action.name, reps: Int = -1) =
        WatchWorkoutControl(id, action, "s", "set", "a", reps = reps)

    @Test fun autoCompletionSendsHoldInsteadOfRestAndSurvivesStoreRecreation() {
        val store = SessionStore(app); assertTrue(store.saveActive(initial()))
        val sent = mutableListOf<Pair<String, String>>()
        PhoneSessionController(store) { path, payload -> sent += path to payload }.receiveCompletion(completion(), 1_000).getOrThrow()
        val snapshot = SessionStore(app).loadActive().getOrThrow()!!
        assertFalse(snapshot.plannedSets.single().completed)
        assertEquals(0L, snapshot.restEndAtMs)
        val sync = WearSync.parse(sent.single { it.first == WearSync.PATH_COMMAND }.second)
        assertEquals("SYNC", sync[1])
        assertEquals(UnilateralStage.AWAITING_OTHER_SIDE, UnilateralProgress.decode(sync[24])!!.stage)
        assertTrue(sent.any { it.first == WearSync.PATH_SET_ACK })
        assertNull(store.advanceAfterRest(999_999).getOrThrow())
        assertEquals(UnilateralStage.AWAITING_OTHER_SIDE, restoreWorkoutRoutine(snapshot, null).exercises[0].sets[0].unilateral!!.stage)
    }
    @Test fun manualCompletionAlsoHoldsAndSecondManualCompletionRecordsActualOppositeCount() {
        val store = SessionStore(app); assertTrue(store.saveActive(initial()))
        fun manual(id: String, count: Int) = store.applyManualSet(ManualSetCommand(id, "s", "one_arm_dumbbell_row", 0,
            "set", "a", count, store.manualSetRevision("s", "set", "a").getOrThrow()!!, ManualSetOperation.COMPLETE), 1_000).getOrThrow()
        val first = manual("first", 12)
        assertFalse(first.restStarted)
        assertEquals(ManualSetStatus.APPLIED, first.ack.status)
        assertEquals(WatchWorkoutControlStatus.APPLIED, store.applyWatchControl(command(WatchWorkoutAction.START_OTHER_SIDE), 2_000).getOrThrow().ack.status)
        val final = manual("other", 9)
        assertTrue(final.restStarted)
        assertEquals(9, final.snapshot!!.plannedSets.single().unilateral!!.otherReps)
        assertEquals(12, final.snapshot!!.plannedSets.single().performedReps)
        assertEquals(ManualSetStatus.DUPLICATE, store.applyManualSet(ManualSetCommand("first", "s", "one_arm_dumbbell_row", 0,
            "set", "a", 12, 0, ManualSetOperation.COMPLETE), 9_000).getOrThrow().ack.status)
        assertEquals(9, store.loadActive().getOrThrow()!!.plannedSets.single().unilateral!!.otherReps)
    }
    @Test fun phoneAndWatchActionsShareReceiptsAndNeverDoubleCompleteOrRestartRest() {
        val store = SessionStore(app); assertTrue(store.saveActive(initial()))
        val sent = mutableListOf<String>()
        val controller = PhoneSessionController(store) { _, payload -> sent += payload }
        controller.receiveCompletion(completion(), 1_000).getOrThrow()
        val before = store.loadActive().getOrThrow()!!.restRev
        val start = command(WatchWorkoutAction.START_OTHER_SIDE)
        assertEquals(start, WatchWorkoutControl.decode(start.encode()))
        assertEquals(WatchWorkoutControlStatus.APPLIED, store.applyWatchControl(start, 2_000).getOrThrow().ack.status)
        assertTrue(store.loadActive().getOrThrow()!!.restRev > before)
        controller.receiveCompletion(completion(), 3_000).getOrThrow()
        assertEquals(UnilateralStage.OTHER_SIDE_WORKING, store.loadActive().getOrThrow()!!.plannedSets.single().unilateral!!.stage)
        val done = command(WatchWorkoutAction.COMPLETE_OTHER_SIDE, reps = 8)
        assertEquals(done, WatchWorkoutControl.decode(done.encode()))
        val result = store.applyWatchControl(done, 4_000).getOrThrow()
        assertEquals(64_000L, result.snapshot!!.restEndAtMs)
        assertEquals(8, result.snapshot!!.plannedSets.single().unilateral!!.otherReps)
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, store.applyWatchControl(done, 99_000).getOrThrow().ack.status)
        assertEquals(64_000L, store.loadActive().getOrThrow()!!.restEndAtMs)
        val stale = start.copy(commandId = "old", attemptId = "old")
        assertEquals(WatchWorkoutControlStatus.REJECTED, store.applyWatchControl(stale, 99_000).getOrThrow().ack.status)
    }
    @Test fun phoneFacadeFinishesOnlyFirstArmAndKeepsOppositeCountUnknown() {
        val store = SessionStore(app); assertTrue(store.saveActive(initial()))
        val controller = PhoneSessionController(store) { _, _ -> }
        controller.receiveCompletion(completion(), 1_000).getOrThrow()
        val result = controller.unilateralAction("s", "set", "a", UnilateralAction.FINISH_WATCH_SIDE_ONLY, 5_000).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.APPLIED, result.ack.status)
        assertEquals(UnilateralStage.WATCH_SIDE_ONLY, result.snapshot!!.plannedSets.single().unilateral!!.stage)
        assertNull(result.snapshot!!.plannedSets.single().unilateral!!.otherReps)
    }
}
