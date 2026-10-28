package dev.kyungmin.kcoach.wear

import android.content.Intent
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
class RestPauseServiceTest {
    private fun command(operation: String, revision: Long) = Intent().putExtra("command", operation).putExtra("session", "rest-session")
        .putExtra("exerciseId", "barbell_biceps_curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
        .putExtra("targetReps", 8).putExtra("restSeconds", 90).putExtra("commandRevision", revision)
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }

    @Test fun aPausedRestStaysAFrozenRestForTheScreenAndResumeContinuesFromTheSameTime() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command("START", 1), 0, 1)
            controller.get().onStartCommand(command("REST", 2).putExtra("restEndAtMs", System.currentTimeMillis() + 60_000L), 0, 1)
            assertTrue(state().getBooleanExtra("resting", false))
            assertFalse(state().getBooleanExtra("restPaused", true))
            controller.get().onStartCommand(command("PAUSE", 3), 0, 2)
            assertTrue(state().getBooleanExtra("paused", false))
            assertTrue("the screen must keep showing the rest, not the last set", state().getBooleanExtra("restPaused", false))
            val frozen = state().getLongExtra("pausedRestRemainingMs", 0L)
            assertTrue("remaining time is kept: $frozen", frozen in 50_000L..60_000L)
            controller.get().onStartCommand(command("RESUME", 4), 0, 3)
            assertTrue(state().getBooleanExtra("resting", false))
            assertFalse(state().getBooleanExtra("restPaused", true))
            assertTrue(state().getLongExtra("restEndAtMs", 0L) - System.currentTimeMillis() in (frozen - 5_000L)..(frozen + 1_000L))
        } finally { controller.destroy() }
    }
    @Test fun pausingAtTheDeadlineKeepsAResumableRest() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(command("START", 1), 0, 1)
            service.onStartCommand(command("REST", 2).putExtra("restEndAtMs", System.currentTimeMillis()), 0, 2)
            service.onStartCommand(command("REST_PAUSE", 0), 0, 3)
            assertTrue(state().getBooleanExtra("paused", false))
            assertTrue("zero-second pause must keep rest identity", state().getBooleanExtra("restPaused", false))
            assertEquals(1L, state().getLongExtra("pausedRestRemainingMs", 0))
            service.onStartCommand(command("REST_RESUME", 0), 0, 4)
            assertFalse(state().getBooleanExtra("paused", true))
        } finally { controller.destroy() }
    }

    @Test fun pauseAfterTheZeroTickSurvivesPhoneAdvancingTheSameNextAttempt() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(command("START", 1), 0, 1)
            WearWorkoutService::class.java.getDeclaredField("reps").apply { isAccessible = true }.set(service, 8)
            service.onStartCommand(command("REST", 2).putExtra("restEndAtMs", System.currentTimeMillis()), 0, 2)
            val tick = WearWorkoutService::class.java.getDeclaredField("restTicker").apply { isAccessible = true }.get(service) as Runnable
            tick.run()
            service.onStartCommand(command("REST_PAUSE", 0), 0, 3)
            assertTrue("a pause must not be dropped after the zero tick", state().getBooleanExtra("paused", false))
            service.onStartCommand(command("SYNC", 3).putExtra("syncPhase", "PAUSED").putExtra("pausedRestRemainingMs", 0L)
                .putExtra("restRev", 1L).putExtra("watchRestSeq", 1L), 0, 4)
            assertTrue(state().getBooleanExtra("paused", false))
            assertFalse(state().getBooleanExtra("restPaused", true))
            assertEquals("the next set must not inherit the previous set count", 0, state().getIntExtra("reps", -1))
        } finally { controller.destroy() }
    }

}
