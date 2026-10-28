package dev.kyungmin.kcoach.phone

import android.content.Intent
import dev.kyungmin.kcoach.core.WearSync
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneServiceHardeningTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun startCommand(): String = WearSync.richCommand(
        "START", "hardening-session", "dumbbell_curl", "컬", 0, 8, 5.0, 60,
        setId = "set-1", attemptId = "attempt-1")

    @Test fun aStaleSetIdentityIsRejectedBeforeTheOutboxAndNeverThrows() {
        WorkoutWearingStore(app).beginSession("hardening-session",
            WorkoutWearingSelection("LEFT", "OUTSIDE")).getOrThrow()
        assertTrue(PhoneWorkoutService.send(app, WearSync.PATH_COMMAND, startCommand()))
        val pending = PhoneCommandOutbox(app).pending()
        assertNotNull(pending)

        // The same set/attempt identity cannot be reused for a different exercise after a replacement.
        val stale = WearSync.richCommand("SET", "hardening-session", "lat_pull_down", "랫풀다운", 0, 8, 5.0, 60,
            setId = "set-1", attemptId = "attempt-1")
        assertFalse(PhoneWorkoutService.send(app, WearSync.PATH_COMMAND, stale))
        assertEquals("A rejected replacement must not overwrite the durable command", pending, PhoneCommandOutbox(app).pending())
    }

    @Test fun onStartCommandContainsABindingFailureAndLeavesTheServiceAlive() {
        WorkoutWearingStore(app).beginSession("hardening-session",
            WorkoutWearingSelection("LEFT", "OUTSIDE")).getOrThrow()
        PhoneWorkoutService.send(app, WearSync.PATH_COMMAND, startCommand())
        val stale = WearSync.richCommand("SET", "hardening-session", "lat_pull_down", "랫풀다운", 0, 8, 5.0, 60,
            setId = "set-1", attemptId = "attempt-1")
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        try {
            // Non-durable path calls PhoneWearBridge.sendRaw, which re-binds and throws for the stale identity.
            val intent = Intent().putExtra(PhoneWorkoutService.EXTRA_PATH, WearSync.PATH_COMMAND)
                .putExtra(PhoneWorkoutService.EXTRA_PAYLOAD, stale)
                .putExtra("durableCommand", false)
            controller.get().onStartCommand(intent, 0, 1)
            assertFalse(PhoneCommandOutbox(app).pending().isNullOrBlank())
        } finally { controller.destroy() }
    }

    @Test fun theBridgeAlsoRejectsAStaleRawCommandWithoutThrowing() {
        WorkoutWearingStore(app).beginSession("hardening-session",
            WorkoutWearingSelection("LEFT", "OUTSIDE")).getOrThrow()
        PhoneWorkoutService.send(app, WearSync.PATH_COMMAND, startCommand())
        val pending = PhoneCommandOutbox(app).pending()
        val stale = WearSync.richCommand("SET", "hardening-session", "lat_pull_down", "랫풀다운", 0, 8, 5.0, 60,
            setId = "set-1", attemptId = "attempt-1")
        val bridge = PhoneWearBridge(app)
        try { bridge.sendRaw(WearSync.PATH_COMMAND, stale) } finally { bridge.close() }
        assertEquals(pending, PhoneCommandOutbox(app).pending())
    }

    @Test fun aMalformedCommandAckCannotKillTheWearListener() {
        // A corrupt pending command used to make pending[2] throw on the listener main thread.
        app.getSharedPreferences("wear-command-outbox", android.content.Context.MODE_PRIVATE)
            .edit().putString("pending", "x").commit()
        val controller = Robolectric.buildService(PhoneWearListenerService::class.java).create()
        try {
            val listener = controller.get()
            val method = PhoneWearListenerService::class.java.getDeclaredMethod(
                "handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
            method.invoke(listener, WearSync.PATH_COMMAND_ACK, "1|session|0|APPLIED|0")
        } finally { controller.destroy() }
    }

    @Test fun aMalformedStoredProfileTransferIsDroppedWithoutThrowing() {
        val bridge = PhoneWearBridge(app)
        try {
            bridge.sendRaw(WearSync.PATH_PATTERN_PROFILE, "not-a-profile-command")
            bridge.retryPendingCommand()
        } finally { bridge.close() }
    }

    @Test fun aForegroundPromotionRefusalBecomesAResultInsteadOfAProcessCrash() {
        assertTrue(promoteForegroundSafely { })
        assertFalse(promoteForegroundSafely {
            throw android.app.ForegroundServiceStartNotAllowedException("background start refused")
        })
    }
}
