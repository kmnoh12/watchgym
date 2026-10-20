package dev.kyungmin.kcoach.phone

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CachedAssetBoundaryTest {
    private fun flag(service: PhoneWearListenerService, name: String) = PhoneWearListenerService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service) as AtomicBoolean

    @Test fun freezeOrReleaseFailureReleasesOnceClearsQueueAndPublishesRetryableError() {
        for ((failFreeze, failRelease) in listOf(true to false, false to true, true to true)) {
            val service = Robolectric.buildService(PhoneWearListenerService::class.java).get()
            flag(service, "assetRecoveryQueued").set(true)
            var releases = 0
            var timedOut = false
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable { timedOut = true }
            handler.postDelayed(timeout, 100)
            service.consumeCachedAssets(AtomicBoolean(false), handler, timeout,
                { if (failFreeze) error("freeze failed") else emptyList<DataItem>() },
                { releases++; if (failRelease) error("release failed") })
            assertEquals(1, releases)
            assertFalse(flag(service, "assetRecoveryQueued").get())
            assertTrue(flag(service, "assetRecoveryRequested").get())
            val app = RuntimeEnvironment.getApplication()
            assertNotNull(app.getSharedPreferences("wear-sync", 0).getString("assetScanError", null))
            assertTrue(shadowOf(app).broadcastIntents.any {
                it.action == PhoneWearListenerService.ACTION_CAPTURE_ERROR && it.getStringExtra("stage") == "asset_scan"
            })
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            assertFalse(timedOut)
        }
    }
    @Test fun lateCallbackReleasesEvenIfReleaseFailsWithoutResettingANewerScan() {
        val service = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        flag(service, "assetRecoveryQueued").set(true)
        var releases = 0
        service.consumeCachedAssets(AtomicBoolean(true), Handler(Looper.getMainLooper()), Runnable {},
            { error("Late buffer must not be read") }, { releases++; error("late release failed") })
        assertEquals(1, releases)
        assertTrue(flag(service, "assetRecoveryQueued").get())
    }
    @Test fun successfulScanClearsPreviousFailureAndCompletesQueue() {
        val service = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("wear-sync", 0)
        prefs.edit().putString("assetScanError", "previous error").commit()
        flag(service, "assetRecoveryQueued").set(true)
        var released = false
        service.consumeCachedAssets(AtomicBoolean(false), Handler(Looper.getMainLooper()), Runnable {},
            { emptyList() }, { released = true })
        assertTrue(released)
        assertFalse(flag(service, "assetRecoveryQueued").get())
        assertFalse(prefs.contains("assetScanError"))
    }
}
