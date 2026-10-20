package dev.kyungmin.kcoach.phone

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DiagnosticsTestApplication::class)
class DiagnosticsHistoryTest {
    private fun dir() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "exit-diagnostics")
    private fun rows(name: String, key: String) = JSONObject(LocalExitDiagnostics.readReports().getOrThrow().getValue(name)).getJSONArray(key)

    @Test fun exceptionsKeepTheNewestTwentyAcrossReloadsWithBoundedUtf8Entries() {
        repeat(25) { index ->
            LocalExitDiagnostics.state("screen-$index", "WORKING")
            LocalExitDiagnostics.event("service-start", startId = index, sessionId = "private-session")
            val error = IllegalStateException("PRIVATE")
            error.stackTrace = Array(100) { StackTraceElement("가".repeat(300), "method", "File.kt", it) }
            LocalExitDiagnostics.recordException(error)
        }
        val history = rows("exception-history.json", "exceptions")
        assertEquals(20, history.length())
        assertEquals("screen-5", history.getJSONObject(0).getString("screen"))
        assertEquals("screen-24", history.getJSONObject(19).getString("screen"))
        for (i in 0 until history.length()) {
            val entry = history.getJSONObject(i)
            assertTrue(entry.toString().toByteArray().size <= 2048)
            assertEquals(BuildConfig.VERSION_CODE, entry.getInt("appVersionCode"))
            assertEquals(16, entry.getString("sessionToken").length)
            assertTrue(entry.getLong("atMs") > 0)
            assertTrue(entry.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").length() > 0)
            assertFalse(entry.toString().contains("PRIVATE"))
            assertFalse(entry.toString().contains("private-session"))
        }
        assertTrue(File(dir(), "exception-history.json").length() <= 48 * 1024)
        // Re-read from disk, rather than an in-memory ring.
        assertEquals(history.toString(), rows("exception-history.json", "exceptions").toString())
    }

    @Test fun lifecycleEventsKeepStartIdsAndEvictOldest() {
        repeat(25) { assertTrue(LocalExitDiagnostics.event("service-request", "stage", it).isSuccess) }
        val events = rows("event-history.json", "events")
        assertEquals(20, events.length())
        assertEquals(5, events.getJSONObject(0).getInt("startId"))
        assertEquals(24, events.getJSONObject(19).getInt("startId"))
    }

    @Test fun corruptAndOversizedFilesAreVisibleAndNewCrashesStillPersist() {
        File(dir(), "exception-history.json").writeText("broken-json")
        File(dir(), "last-state.json").writeText("x".repeat(49 * 1024))
        val reports = LocalExitDiagnostics.readReports().getOrThrow()
        assertTrue(reports.containsKey("diagnostics-status.json"))
        assertFalse(reports.containsKey("last-state.json"))
        LocalExitDiagnostics.recordException(IllegalArgumentException("private"))
        assertEquals(1, rows("exception-history.json", "exceptions").length())
        assertNotNull(LocalExitDiagnostics.lastFailure)
    }

    @Test fun failedAtomicWriteDoesNotCrashAndExposesFailure() {
        val target = File(dir(), "event-history.json")
        target.delete()
        target.mkdirs()
        File(target, "blocker").writeText("occupied")
        try {
            assertTrue(LocalExitDiagnostics.event("service-stop", startId = 7).isFailure)
            assertNotNull(LocalExitDiagnostics.lastFailure)
            assertTrue(LocalExitDiagnostics.readReports().getOrThrow().containsKey("diagnostics-status.json"))
        } finally { target.deleteRecursively() }
        assertTrue(LocalExitDiagnostics.event("service-stop", startId = 8).isSuccess)
        assertEquals(8, rows("event-history.json", "events").getJSONObject(0).getInt("startId"))
    }

    @Test fun atomicBackupIsRecovered() {
        val target = File(dir(), "exception-history.json")
        target.writeText("broken")
        File(dir(), "exception-history.json.bak").writeText("{\"exceptions\":[]}")
        assertEquals(0, rows("exception-history.json", "exceptions").length())
        assertFalse(File(dir(), "exception-history.json.bak").exists())
    }

    @Test fun systemExitReasonIsAccumulatedOnceAcrossRefreshes() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(ActivityManager::class.java)
        val exit = org.robolectric.util.ReflectionHelpers.newInstance(ApplicationExitInfo::class.java)
        org.robolectric.util.ReflectionHelpers.setField(exit, "mTimestamp", 123456L)
        org.robolectric.util.ReflectionHelpers.setField(exit, "mReason", ApplicationExitInfo.REASON_CRASH)
        org.robolectric.util.ReflectionHelpers.setField(exit, "mStatus", 4)
        shadowOf(manager).addApplicationExitInfo(exit)
        repeat(2) { LocalExitDiagnostics.refreshSystemHistory(app) }
        val events = rows("event-history.json", "events")
        val exits = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.optString("kind") == "system-exit" }
        assertEquals(1, exits.size)
        assertEquals(4, exits.single().getJSONObject("exit").getInt("reason"))
        assertEquals(123456L, exits.single().getLong("atMs"))
    }
}

/** Installs diagnostics without starting unrelated OTA or relay workers. */
class DiagnosticsTestApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        LocalExitDiagnostics.install(this)
    }
}
