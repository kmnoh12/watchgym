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
class UnilateralServiceTest {
    private fun command(stage: UnilateralStage, revision: Long, paused: Boolean = false) = Intent()
        .putExtra("command", "SYNC").putExtra("session", "s")
        .putExtra("exerciseId", "one_arm_dumbbell_row").putExtra("exerciseName", "원암 덤벨 로우")
        .putExtra("setId", "set").putExtra("attemptId", "a").putExtra("setIndex", 0)
        .putExtra("targetReps", 10).putExtra("commandRevision", revision).putExtra("restRev", revision)
        .putExtra("syncPhase", if (paused) "PAUSED" else "WORKING")
        .putExtra("unilateralProgress", UnilateralProgress(stage, 12, SetRepSource.WATCH).encode())
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        .last { it.action == WearWorkoutService.ACTION_STATE }
    @Test fun firstCompletionCanEnterHoldBeforeItsOldCompletionAckArrives() {
        val service = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val start = command(UnilateralStage.AWAITING_OTHER_SIDE, 1).putExtra("command", "START")
                .putExtra("unilateralProgress", UnilateralProgress().encode())
            service.get().onStartCommand(start, 0, 1)
            service.get().onStartCommand(Intent().putExtra("command", "SET_COMPLETE").putExtra("session", "s"), 0, 2)
            service.get().onStartCommand(command(UnilateralStage.AWAITING_OTHER_SIDE, 2), 0, 3)
            assertEquals("AWAITING_OTHER_SIDE", state().getStringExtra("phase"))
            assertFalse(state().getBooleanExtra("running", true))
            assertEquals(0L, state().getLongExtra("restEndAtMs", -1))
        } finally { service.destroy() }
    }
    @Test fun reconnectDirectlyIntoPausedOtherArmSurvivesRecreationWithoutWrongExerciseProfile() {
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command(UnilateralStage.OTHER_SIDE_WORKING, 1, paused = true), 0, 1)
        assertEquals("PAUSED", state().getStringExtra("phase"))
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            restored.get().onStartCommand(command(UnilateralStage.OTHER_SIDE_WORKING, 2), 0, 2)
            assertEquals("OTHER_SIDE_WORKING", state().getStringExtra("phase"))
            assertFalse(state().getBooleanExtra("running", true))
            restored.get().onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "s"), 0, 3)
            assertEquals("IDLE", state().getStringExtra("phase"))
            assertEquals("", state().getStringExtra("unilateralStage"))
        } finally { restored.destroy() }
    }
    @Test fun otherArmNeverRunsSensorsOrAutoCounterAndPersistsAcrossServiceRecreation() {
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command(UnilateralStage.AWAITING_OTHER_SIDE, 1), 0, 1)
        assertEquals("AWAITING_OTHER_SIDE", state().getStringExtra("phase"))
        assertFalse(state().getBooleanExtra("running", true))
        assertFalse(state().getBooleanExtra("resting", true))
        first.get().onStartCommand(command(UnilateralStage.OTHER_SIDE_WORKING, 2), 0, 2)
        assertEquals("OTHER_SIDE_WORKING", state().getStringExtra("phase"))
        assertTrue(state().getBooleanExtra("manualRequired", false))
        assertEquals(12, state().getIntExtra("otherSideTargetReps", -1))
        assertEquals(0L, state().getLongExtra("restEndAtMs", -1))
        // Old auto-complete requests have no running detector to complete.
        first.get().onStartCommand(Intent().putExtra("command", "SET_COMPLETE").putExtra("session", "s"), 0, 3)
        assertEquals("OTHER_SIDE_WORKING", state().getStringExtra("phase"))
        // Reissuing the existing SET command after editing weight must not restart auto counting.
        first.get().onStartCommand(command(UnilateralStage.OTHER_SIDE_WORKING, 3).putExtra("command", "SET"), 0, 4)
        assertEquals("OTHER_SIDE_WORKING", state().getStringExtra("phase"))
        assertFalse(state().getBooleanExtra("running", true))
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            restored.get().onStartCommand(Intent().putExtra("command", "QUERY"), 0, 4)
            assertEquals("OTHER_SIDE_WORKING", state().getStringExtra("phase"))
            assertFalse(state().getBooleanExtra("running", true))
            restored.get().onStartCommand(command(UnilateralStage.OTHER_SIDE_WORKING, 4, paused = true), 0, 5)
            assertEquals("PAUSED", state().getStringExtra("phase"))
            restored.get().onStartCommand(command(UnilateralStage.OTHER_SIDE_WORKING, 5), 0, 6)
            assertEquals("OTHER_SIDE_WORKING", state().getStringExtra("phase"))
            assertFalse(state().getBooleanExtra("running", true))
        } finally { restored.destroy() }
    }
}
