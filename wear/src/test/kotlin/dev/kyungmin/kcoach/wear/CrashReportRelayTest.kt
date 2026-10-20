package dev.kyungmin.kcoach.wear

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportRelayTest {
    @Test fun aRunWithoutAnExceptionProducesNoPayload() {
        assertNull(CrashReportRelay.buildPayload(mapOf("last-state.json" to "{}")))
    }

    @Test fun thePayloadCarriesTheWatchStackAndStaysInsideTheMessageLimit() {
        val payload = CrashReportRelay.buildPayload(mapOf(
            "last-exception.json" to JSONObject().put("causes", "stack").toString(),
            "system-exits.json" to JSONObject().put("exits", "rows").toString(),
            "last-state.json" to JSONObject().put("phase", "RESTING").toString(),
        ))!!
        val reports = JSONObject(payload).getJSONObject("reports")
        assertTrue(reports.has("last-exception.json"))
        assertTrue(reports.has("system-exits.json"))
        assertTrue(reports.has("last-state.json"))
        assertTrue("Wear messages cap near 100 KB", payload.toByteArray().size < 90_000)
    }
}
