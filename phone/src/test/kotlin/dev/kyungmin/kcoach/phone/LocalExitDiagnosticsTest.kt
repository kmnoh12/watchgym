package dev.kyungmin.kcoach.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DiagnosticsTestApplication::class)
class LocalExitDiagnosticsTest {
    @Test fun diagnosticsZipContainsRecordedExceptionAndVersionWithoutChangingOriginalReports() {
        val app = RuntimeEnvironment.getApplication()
        LocalExitDiagnostics.state("ACTIVE", "RESTING")
        LocalExitDiagnostics.recordException(IllegalStateException("PRIVATE workout name or weight"))
        val before = LocalExitDiagnostics.readReports().getOrThrow().getValue("last-exception.json")
        val bytes = ByteArrayOutputStream().also { DiagnosticsExport.write(app, it) }.toByteArray()
        val entries = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(entries.keys.all { it in setOf("export.json", "last-exception.json", "last-state.json", "system-exits.json", "exception-history.json", "event-history.json", "device-info.json") })
        assertEquals(1, JSONObject(entries.getValue("exception-history.json")).getJSONArray("exceptions").length())
        assertTrue(JSONObject(entries.getValue("export.json")).getBoolean("hasExceptionHistory"))
        assertEquals(before, entries["last-exception.json"])
        assertEquals(before, LocalExitDiagnostics.readReports().getOrThrow()["last-exception.json"])
        val metadata = JSONObject(entries.getValue("export.json"))
        assertEquals(BuildConfig.VERSION_CODE, metadata.getInt("exportAppVersionCode"))
        assertTrue(metadata.getBoolean("hasExceptionReport"))
        assertFalse(entries.values.any { it.contains("PRIVATE") })
        assertEquals("RESTING", JSONObject(entries.getValue("last-exception.json")).getString("phase"))
    }

    @Test fun watchCrashReportArrivesInTheSameExport() {
        val app = RuntimeEnvironment.getApplication()
        val watch = JSONObject().put("atMs", 1L).put("appVersion", "0.2.12")
            .put("reports", JSONObject()
                .put("last-exception.json", JSONObject().put("causes", JSONArray().put(JSONObject()
                    .put("type", "java.lang.IllegalStateException"))).toString())
                .put("system-exits.json", JSONObject().put("exits", JSONArray()).toString())
                .put("exception-history.json", JSONObject().put("exceptions", JSONArray().put(JSONObject().put("screen", "watch"))).toString())
                .put("event-history.json", JSONObject().put("events", JSONArray().put(JSONObject().put("startId", 12))).toString())
                .put("runtime-events.json", JSONObject().put("events", JSONArray()).toString()))
        LocalExitDiagnostics.storeWatchReports(watch.toString())
        assertTrue("watch-last-exception.json" in LocalExitDiagnostics.readReports().getOrThrow())
        val bytes = ByteArrayOutputStream().also { DiagnosticsExport.write(app, it) }.toByteArray()
        val names = linkedSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name }
        }
        assertTrue(names.contains("watch-last-exception.json"))
        assertTrue(names.contains("watch-system-exits.json"))
        assertTrue(names.contains("watch-exception-history.json"))
        assertTrue(names.contains("watch-event-history.json"))
        assertTrue(names.contains("watch-runtime-events.json"))
    }

    @Test fun exceptionRecordCarriesMemoryAndTheExportCarriesTheDeviceFacts() {
        val app = RuntimeEnvironment.getApplication()
        LocalExitDiagnostics.recordException(IllegalStateException("PRIVATE"))
        val reports = LocalExitDiagnostics.readReports().getOrThrow()
        val memory = JSONObject(reports.getValue("last-exception.json")).getJSONObject("memory")
        assertTrue(memory.has("heapMaxMb")); assertTrue(memory.has("system"))
        val device = JSONObject(reports.getValue("device-info.json"))
        assertTrue(device.has("fingerprint")); assertTrue(device.has("previewSdk")); assertTrue(device.has("codename"))
        val bytes = ByteArrayOutputStream().also { DiagnosticsExport.write(app, it) }.toByteArray()
        val metadata = ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "export.json" }.let { JSONObject(zip.readBytes().toString(Charsets.UTF_8)) }
        }
        assertTrue(metadata.has("androidPreviewSdk")); assertTrue(metadata.has("buildFingerprint"))
        assertFalse(reports.values.any { it.contains("PRIVATE") })
    }

    @Test fun watchDeviceFactsAndMemoryJournalArriveWithTheWatchReports() {
        val watch = JSONObject().put("atMs", 1L).put("appVersion", "0.2.12").put("reports", JSONObject()
            .put("system-exits.json", JSONObject().put("exits", JSONArray()).toString())
            .put("device-info.json", JSONObject().put("fingerprint", "watch-build").toString())
            .put("memory-events.json", JSONObject().put("events", JSONArray().put(JSONObject().put("kind", "low-memory"))).toString()))
        LocalExitDiagnostics.storeWatchReports(watch.toString())
        val reports = LocalExitDiagnostics.readReports().getOrThrow()
        assertTrue("watch-device-info.json" in reports); assertTrue("watch-memory-events.json" in reports)
    }

    @Test fun localExceptionHasCodeFramesAndStateButNoPrivateMessages() {
        val app = RuntimeEnvironment.getApplication()
        LocalExitDiagnostics.state("ACTIVE", "WORKING")
        LocalExitDiagnostics.recordException(IllegalStateException("PRIVATE workout 80kg", IllegalArgumentException("PRIVATE bodyweight")))
        val dir = File(app.noBackupFilesDir, "exit-diagnostics")
        val text = File(dir, "last-exception.json").readText()
        assertTrue(text.contains("IllegalStateException")); assertTrue(text.contains("IllegalArgumentException"))
        assertTrue(text.contains("LocalExitDiagnosticsTest")); assertTrue(text.contains("WORKING"))
        assertFalse(text.contains("PRIVATE")); assertTrue(File(dir, "last-state.json").exists())
        val reports = LocalExitDiagnostics.readReports().getOrThrow()
        assertEquals(text, reports["last-exception.json"])
        assertTrue(reports.keys.all { it in setOf("last-exception.json", "last-state.json", "system-exits.json", "exception-history.json", "event-history.json", "device-info.json") })
        assertEquals(text, File(dir, "last-exception.json").readText())
    }
    @Test fun malformedWatchReportsAndCorruptLocalFilesProduceAnIncompleteExport() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(LocalExitDiagnostics.storeWatchReports("broken").isFailure)
        File(app.noBackupFilesDir, "exit-diagnostics/exception-history.json").writeText("broken")
        val bytes = ByteArrayOutputStream().also { DiagnosticsExport.write(app, it) }.toByteArray()
        val entries = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(entries.containsKey("diagnostics-status.json"))
        assertTrue(JSONObject(entries.getValue("export.json")).getBoolean("diagnosticsIncomplete"))
    }

}
