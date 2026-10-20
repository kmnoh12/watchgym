package dev.kyungmin.kcoach.wear

import android.content.Intent
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
class ManualWatchFlowTest {
    @Test fun watchActualZeroIsQueuedDurablyAndAHumanCountNeverCreatesAnAutomaticCompletion() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "session")
            .putExtra("exerciseId", "leg_press").putExtra("exerciseName", "레그프레스")
            .putExtra("setId", "set").putExtra("attemptId", "attempt").putExtra("targetReps", 12), 0, 1)
        val manual = ManualSetCommand("manual", "session", "leg_press", 0, "set", "attempt", 0, 0, ManualSetOperation.COMPLETE)
        service.onStartCommand(Intent().putExtra("command", "MANUAL_SUBMIT")
            .putExtra("payload", WireCodec.encode(manual.toEnvelope(0, 0))), 0, 2)
        assertEquals(listOf(manual), ManualSetOutbox(RuntimeEnvironment.getApplication()).pending())
        val published = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
        assertEquals("MANUAL_PENDING", published.getStringExtra("phase"))
        assertFalse(published.getBooleanExtra("running", true))
        controller.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        restored.get().onStartCommand(Intent().putExtra("command", "MANUAL_ACK").putExtra("payload",
            ManualSetAck("manual", "session", "set", "attempt", ManualSetStatus.APPLIED, 1, 0).encode(0, 0)), 0, 3)
        assertTrue(ManualSetOutbox(RuntimeEnvironment.getApplication()).pending().isEmpty())
        restored.destroy()
    }
    private fun request(type: String) = Intent().putExtra("command", type).putExtra("session", "session")
        .putExtra("setId", "next").putExtra("attemptId", "attempt")
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
    private fun rest(service: WearWorkoutService) {
        service.onStartCommand(request("START").putExtra("exerciseId", "leg_press"), 0, 1)
        service.onStartCommand(request("REST").putExtra("restSeconds", 60)
            .putExtra("restEndAtMs", System.currentTimeMillis() + 60_000), 0, 2)
    }
    @Test fun restAdjustClampsBothValuesAndQueuesExactDeadlineDurably() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            rest(service)
            val original = state().getLongExtra("restEndAtMs", 0)
            service.onStartCommand(request("REST_ADJUST").putExtra("deltaSeconds", 10), 0, 3)
            val outbox = WatchWorkoutOutbox(RuntimeEnvironment.getApplication())
            val first = outbox.pending().single()
            assertEquals(WatchWorkoutAction.REST_ADJUST, first.action)
            assertEquals(60, first.restSeconds) // one-off: the configured rest is untouched
            assertEquals(original + 10_000, first.restEndAtMs)
            assertEquals(first.restEndAtMs, state().getLongExtra("restEndAtMs", 0))
            val beforeUpper = System.currentTimeMillis()
            service.onStartCommand(request("REST_ADJUST").putExtra("deltaSeconds", Int.MAX_VALUE), 0, 4)
            assertEquals(60, state().getIntExtra("restSeconds", -1))
            assertTrue(state().getLongExtra("restEndAtMs", 0) in (beforeUpper + 1_800_000)..(System.currentTimeMillis() + 1_800_000))
            val beforeLower = System.currentTimeMillis()
            service.onStartCommand(request("REST_ADJUST").putExtra("deltaSeconds", Int.MIN_VALUE), 0, 5)
            assertEquals(60, state().getIntExtra("restSeconds", -1))
            assertTrue(state().getLongExtra("restEndAtMs", 0) in beforeLower..System.currentTimeMillis())
            assertEquals(1, WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().size)
            assertEquals("PENDING", state().getStringExtra("manualStatus"))
        } finally { controller.destroy() }
    }
    @Test fun currentWeightIsImmediateDurableAndFollowsPhoneSyncWithoutChangingCountDraft() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(request("START").putExtra("exerciseId", "leg_press")
                .putExtra("targetReps", 12).putExtra("weightKg", 30.0), 0, 1)
            service.onStartCommand(request("EDIT_SET").putExtra("which", "CURRENT")
                .putExtra("reps", -1).putExtra("weightKg", 35.0), 0, 2)
            assertEquals(35.0, state().getDoubleExtra("weightKg", -1.0), 0.0)
            assertTrue(state().getBooleanExtra("running", false))
            val command = WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().single()
            assertEquals("CURRENT", command.which); assertEquals(-1, command.reps)
            assertEquals(35.0, command.weightKg!!, 0.0)
            // Phone resync uses this same attempt and the persisted edited weight.
            service.onStartCommand(request("SYNC").putExtra("exerciseId", "leg_press")
                .putExtra("syncPhase", "WORKING").putExtra("targetReps", 12).putExtra("weightKg", 37.5), 0, 3)
            assertEquals(37.5, state().getDoubleExtra("weightKg", -1.0), 0.0)
            assertEquals(0, state().getIntExtra("reps", -1))
            assertEquals(12, state().getIntExtra("targetReps", -1))
            assertEquals(listOf(command), WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending())
        } finally { controller.destroy() }
    }

    @Test fun currentRejectsRestRepsMissingWeightAndMismatchedIdentity() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(request("START").putExtra("exerciseId", "leg_press").putExtra("weightKg", 30.0), 0, 1)
            fun edit() = request("EDIT_SET").putExtra("which", "CURRENT").putExtra("weightKg", 35.0)
            val missing = edit().apply { removeExtra("weightKg") }
            for (invalid in listOf(missing, edit().putExtra("reps", 0), edit().putExtra("session", "other"),
                edit().putExtra("attemptId", "old"))) {
                service.onStartCommand(invalid, 0, 2)
                assertEquals(30.0, state().getDoubleExtra("weightKg", -1.0), 0.0)
                assertTrue(WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().isEmpty())
            }
            rest(service)
            service.onStartCommand(edit(), 0, 3)
            assertTrue(WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun editsForwardPreviousAndNextWithoutInventingLocalResultsAndAckIsVisible() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(request("START").putExtra("exerciseId", "leg_press"), 0, 1)
            service.onStartCommand(request("EDIT_SET").putExtra("which", "NEXT").putExtra("reps", 9), 0, 2)
            assertTrue(WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().isEmpty())
            rest(service)
            service.onStartCommand(request("EDIT_SET").putExtra("which", "PREVIOUS")
                .putExtra("reps", 0).putExtra("weightKg", 35.0), 0, 3)
            service.onStartCommand(request("EDIT_SET").putExtra("which", "NEXT")
                .putExtra("reps", -1).putExtra("weightKg", 40.0), 0, 4)
            val pending = WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending()
            assertEquals(listOf("PREVIOUS", "NEXT"), pending.map { it.which })
            assertEquals(0, pending[0].reps); assertEquals(35.0, pending[0].weightKg!!, 0.0)
            assertEquals(-1, pending[1].reps); assertEquals(40.0, pending[1].weightKg!!, 0.0)
            assertEquals(pending, WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending())
            val command = pending.last()
            service.onStartCommand(request("WORKOUT_CONTROL_ACK").putExtra("payload", WatchWorkoutControlAck(
                command.commandId, command.sessionId, command.setId, command.attemptId, WatchWorkoutControlStatus.STALE).encode()), 0, 5)
            assertEquals("STALE", state().getStringExtra("manualStatus"))
            service.onStartCommand(request("EDIT_SET").putExtra("session", "other").putExtra("which", "NEXT").putExtra("reps", 7), 0, 6)
            assertEquals(1, WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().size)
        } finally { controller.destroy() }
    }

}
