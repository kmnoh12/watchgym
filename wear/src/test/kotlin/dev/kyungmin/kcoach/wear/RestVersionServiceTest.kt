package dev.kyungmin.kcoach.wear

import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestVersionServiceTest {
    private fun command(type: String) = Intent().putExtra("command", type).putExtra("session", "rest-session")
        .putExtra("exerciseId", "leg_press").putExtra("setId", "set").putExtra("attemptId", "attempt")
        .putExtra("targetReps", 8).putExtra("restSeconds", 90)
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
    private fun sync(rev: Long, seq: Long, end: Long) = command("SYNC").putExtra("syncPhase", "RESTING")
        .putExtra("restRev", rev).putExtra("watchRestSeq", seq).putExtra("restEndAtMs", end)
    private fun start(service: WearWorkoutService): Long {
        val end = System.currentTimeMillis() + 90_000
        service.onStartCommand(command("START").putExtra("commandRevision", 1L), 0, 1)
        service.onStartCommand(sync(1, 0, end).putExtra("commandRevision", 2L), 0, 2)
        return end
    }

    @Test fun rapidEditsKeepLocalDeadlineUntilAcknowledgedAndShuffledSyncsConvergeExactly() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get(); val end = start(service)
            for (delta in listOf(10, 10, -10, 10)) service.onStartCommand(command("REST_ADJUST").putExtra("deltaSeconds", delta), 0, 3)
            val latest = WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().single()
            assertEquals(4L, latest.seq); assertEquals(end + 20_000, latest.restEndAtMs)
            val expected = latest.restEndAtMs!!
            service.onStartCommand(sync(3, 2, end - 10_000).putExtra("commandRevision", 10L), 0, 4)
            assertEquals(expected, state().getLongExtra("restEndAtMs", 0))
            // A rest revision takes precedence even when the transport revision arrived out of order.
            service.onStartCommand(sync(5, 4, expected + 1_234).putExtra("commandRevision", 8L), 0, 5)
            assertEquals(expected + 1_234, state().getLongExtra("restEndAtMs", 0))
            for (rev in listOf(1L, 4L, 5L, 2L)) {
                service.onStartCommand(sync(rev, 4, end).putExtra("commandRevision", 20L + rev), 0, 6)
                assertEquals(expected + 1_234, state().getLongExtra("restEndAtMs", 0))
            }
            service.onStartCommand(command("REST").putExtra("restEndAtMs", end), 0, 7)
            assertEquals(expected + 1_234, state().getLongExtra("restEndAtMs", 0))
        } finally { controller.destroy() }
    }

    @Test fun aTwoHundredTapRestAdjustBurstKeepsOneLatestDeadlineAndDoesNotThrow() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get(); val end = start(service)
            var expected = end
            repeat(200) { i ->
                val delta = if (i % 2 == 0) 10 else -10
                service.onStartCommand(command("REST_ADJUST").putExtra("deltaSeconds", delta), 0, 10 + i)
                expected += delta * 1_000L
            }
            val pending = WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending()
            assertEquals("A burst must coalesce into the newest command", 1, pending.size)
            assertEquals(200L, pending.single().seq)
            assertEquals(expected, pending.single().restEndAtMs)
            assertEquals(expected, state().getLongExtra("restEndAtMs", 0))
        } finally { controller.destroy() }
    }

    @Test fun localPauseFreezesImmediatelyAndLatestResumeSurvivesOldPhoneStatesAndRecreation() {
        var controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val end = start(controller.get())
            controller.get().onStartCommand(command("REST_PAUSE"), 0, 3)
            val pause = WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().single()
            assertEquals(WatchWorkoutAction.REST_PAUSE, pause.action); assertEquals(1L, pause.seq)
            assertTrue(state().getBooleanExtra("restPaused", false)); assertTrue(state().getBooleanExtra("paused", false))
            val frozen = state().getLongExtra("pausedRestRemainingMs", 0)
            assertTrue(frozen > 0)
            controller.get().onStartCommand(sync(2, 0, end), 0, 4)
            assertEquals(frozen, state().getLongExtra("pausedRestRemainingMs", 0))
            controller.destroy()
            controller = Robolectric.buildService(WearWorkoutService::class.java).create()
            assertEquals(1L, state().getLongExtra("watchRestSeq", 0))
            assertEquals(2L, state().getLongExtra("restRev", 0))
            assertEquals(frozen, state().getLongExtra("pausedRestRemainingMs", 0))
            controller.get().onStartCommand(command("REST_RESUME"), 0, 5)
            val resume = WatchWorkoutOutbox(RuntimeEnvironment.getApplication()).pending().single()
            assertEquals(WatchWorkoutAction.REST_RESUME, resume.action); assertEquals(2L, resume.seq)
            assertFalse(state().getBooleanExtra("paused", true)); assertTrue(state().getBooleanExtra("resting", false))
            assertEquals(resume.restEndAtMs!!, state().getLongExtra("restEndAtMs", 0))
            controller.get().onStartCommand(sync(3, 1, 0).putExtra("syncPhase", "PAUSED")
                .putExtra("pausedRestRemainingMs", frozen), 0, 6)
            assertFalse(state().getBooleanExtra("paused", true))
            controller.get().onStartCommand(sync(4, 2, resume.restEndAtMs!!), 0, 7)
            assertEquals(resume.restEndAtMs!!, state().getLongExtra("restEndAtMs", 0))
            assertEquals(4L, state().getLongExtra("restRev", 0))
            controller.get().onStartCommand(sync(5, 2, 0).putExtra("syncPhase", "PAUSED")
                .putExtra("pausedRestRemainingMs", 12_345L), 0, 8)
            assertEquals(0L, state().getLongExtra("restEndAtMs", -1))
            assertEquals(12_345L, state().getLongExtra("pausedRestRemainingMs", 0))
            controller.get().onStartCommand(sync(5, 2, end), 0, 9)
            assertTrue(state().getBooleanExtra("restPaused", false))
            assertEquals(12_345L, state().getLongExtra("pausedRestRemainingMs", 0))
        } finally { controller.destroy() }
    }
}
