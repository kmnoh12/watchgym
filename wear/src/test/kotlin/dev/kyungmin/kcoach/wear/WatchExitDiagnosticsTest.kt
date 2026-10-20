package dev.kyungmin.kcoach.wear

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DiagnosticsTestApplication::class)
class WatchExitDiagnosticsTest {
    @Test fun exceptionHandlerDelegatesWithoutSavingThrowableMessages() {
        val app = RuntimeEnvironment.getApplication()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> delegated = error }
            LocalExitDiagnostics.install(app)
            val error = IllegalStateException("private workout facts")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertSame(error, delegated)
            val report = LocalExitDiagnostics.readReports().getOrThrow().getValue("last-exception.json")
            assertFalse(report.contains("private workout facts"))
            assertTrue(report.contains("IllegalStateException"))
        } finally { Thread.setDefaultUncaughtExceptionHandler(original) }
    }
    @Test fun interruptionAndRecoveryAreSeparateBoundedEventsWithOriginalSampleWatermarks() {
        repeat(50) { LocalExitDiagnostics.event("runtime-interrupted-sensor", "private-session", "PAUSED", 123L) }
        LocalExitDiagnostics.event("recovery-restored", "private-session", "WORKING", 123L)
        val report = LocalExitDiagnostics.readReports().getOrThrow().getValue("runtime-events.json")
        assertTrue(report.toByteArray().size <= 48 * 1024)
        assertFalse(report.contains("private-session"))
        val events = JSONObject(report).getJSONArray("events")
        assertEquals(32, events.length())
        assertEquals("runtime-interrupted-sensor", events.getJSONObject(30).getString("kind"))
        assertEquals("recovery-restored", events.getJSONObject(31).getString("kind"))
        assertEquals(123L, events.getJSONObject(31).getLong("lastSampleNs"))
        assertFalse(events.getJSONObject(31).has("measuredDowntimeMs"))
    }
}
