package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit local export only. No workout files, identifiers, automatic upload or report deletion. */
internal object DiagnosticsExport {
    fun write(context: Context, output: OutputStream) {
        LocalExitDiagnostics.refreshSystemHistory(context)
        val reports = LocalExitDiagnostics.readReports().getOrThrow()
        val metadata = JSONObject().put("schemaVersion", 1).put("exportedAtMs", System.currentTimeMillis())
            .put("exportAppVersion", BuildConfig.VERSION_NAME).put("exportAppVersionCode", BuildConfig.VERSION_CODE)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE).put("androidSdk", Build.VERSION.SDK_INT)
            .put("reports", JSONArray(reports.keys.sorted()))
            .put("hasExceptionReport", "last-exception.json" in reports)
            .put("hasExceptionHistory", "exception-history.json" in reports)
            .put("hasWatchExceptionHistory", "watch-exception-history.json" in reports)
            .put("diagnosticsIncomplete", "diagnostics-status.json" in reports || "watch-diagnostics-status.json" in reports)
            .put("note", "Export version is not necessarily the version that crashed; older reports may lack app version.")
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("export.json", metadata.toString(2))
            reports.toSortedMap().forEach { (name, text) -> entry(name, text) }
        }
    }
}
