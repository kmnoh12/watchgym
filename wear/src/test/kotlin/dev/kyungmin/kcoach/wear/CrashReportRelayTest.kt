package dev.kyungmin.kcoach.wear

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
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

    @Test fun systemExitsAloneAreRelayedWhenTheSystemKilledTheAppWithoutAnException() {
        val exits = JSONObject().put("exits", JSONArray().put(JSONObject().put("atMs", 777L).put("reasonName", "LOW_MEMORY"))).toString()
        val payload = CrashReportRelay.buildPayload(mapOf("system-exits.json" to exits,
            "device-info.json" to JSONObject().put("fingerprint", "f").toString()))!!
        val json = JSONObject(payload)
        assertEquals(777L, json.getLong("atMs"))
        assertTrue(json.getJSONObject("reports").has("system-exits.json"))
        assertTrue(json.getJSONObject("reports").has("device-info.json"))
        assertTrue(!json.getJSONObject("reports").has("last-exception.json"))
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
    @Test fun accumulatedReportsAreRelayedWithStablePayloadAndBoundedWireBytes() {
        fun history(key: String) = JSONObject().put(key, JSONArray().apply {
            repeat(32) { put(JSONObject().put("index", it).put("detail", "\\".repeat(1800))) }
        }).toString()
        val input = mapOf("last-exception.json" to "{\"atMs\":123}",
            "exception-history.json" to history("exceptions"),
            "event-history.json" to history("events"),
            "runtime-events.json" to history("events"))
        val payload = CrashReportRelay.buildPayload(input)!!
        assertEquals(payload, CrashReportRelay.buildPayload(input))
        assertTrue(payload.toByteArray().size <= 90_000)
        val reports = JSONObject(payload).getJSONObject("reports")
        for ((name, key) in listOf("exception-history.json" to "exceptions", "event-history.json" to "events", "runtime-events.json" to "events")) {
            val rows = JSONObject(reports.getString(name)).getJSONArray(key)
            assertTrue(rows.length() > 0)
            assertEquals(31, rows.getJSONObject(rows.length() - 1).getInt("index"))
        }
    }

}
