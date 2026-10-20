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
@Config(sdk = [34])
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
        assertTrue(entries.keys.all { it in setOf("export.json", "last-exception.json", "last-state.json", "system-exits.json") })
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
                .put("system-exits.json", JSONObject().put("exits", JSONArray()).toString()))
        LocalExitDiagnostics.storeWatchReports(watch.toString())
        assertTrue("watch-last-exception.json" in LocalExitDiagnostics.readReports().getOrThrow())
        val bytes = ByteArrayOutputStream().also { DiagnosticsExport.write(app, it) }.toByteArray()
        val names = linkedSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name }
        }
        assertTrue(names.contains("watch-last-exception.json"))
        assertTrue(names.contains("watch-system-exits.json"))
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
        assertTrue(reports.keys.all { it in setOf("last-exception.json", "last-state.json", "system-exits.json") })
        assertEquals(text, File(dir, "last-exception.json").readText())
    }
}
