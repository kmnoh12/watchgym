package dev.kyungmin.kcoach.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.WearSync
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Hands the previous run's crash report to the phone on the next launch.
 * The watch has no export UI, so without this relay a watch crash leaves no retrievable stack.
 */
internal object CrashReportRelay {
    private const val PREFS = "crash-report-relay"
    private const val KEY_SENT = "sentHash"
    /** Wear Data Layer messages are capped near 100 KB; stay under it without chunking. */
    private const val MAX_PAYLOAD_BYTES = 90_000
    private val reportNames = listOf("last-exception.json", "system-exits.json", "last-state.json",
        "exception-history.json", "event-history.json", "runtime-events.json", "memory-events.json", "device-info.json",
        "diagnostics-status.json")

    /** True until a phone has accepted the current report, so a reconnect can finish a send that found no phone. */
    @Volatile private var undelivered = true

    fun relayIfUndelivered(context: Context) { if (undelivered) relay(context) }

    fun relay(context: Context) {
        LocalExitDiagnostics.refreshSystemHistory(context)
        val reports = LocalExitDiagnostics.readReports().getOrElse {
            LocalExitDiagnostics.event("relay-read-failed", detail = it.javaClass.simpleName)
            return
        }
        val payload = buildPayload(reports) ?: run {
            if ("last-exception.json" in reports || "system-exits.json" in reports) LocalExitDiagnostics.event("relay-payload-too-large")
            else undelivered = false
            return
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SENT, null) == hash) { undelivered = false; return }
        var sent = false
        runCatching {
            Tasks.await(Wearable.getNodeClient(context).connectedNodes).forEach { node ->
                Tasks.await(Wearable.getMessageClient(context).sendMessage(
                    node.id, WearSync.PATH_DIAGNOSTICS, payload.toByteArray(),
                ))
                sent = true
            }
        }.onFailure { LocalExitDiagnostics.event("relay-send-failed", detail = it.javaClass.simpleName) }
        // Only remember it once a phone actually accepted the message, so an offline crash retries next launch.
        if (sent) { prefs.edit().putString(KEY_SENT, hash).apply(); undelivered = false } else undelivered = true
    }

    /**
     * Null when there is nothing to report. A Java exception is not the only way the watch app dies: the system kills it
     * for memory, a missed foreground deadline or an ANR without leaving an exception, so the system exit history alone
     * is also worth sending.
     */
    internal fun buildPayload(reports: Map<String, String>): String? {
        val crash = reports["last-exception.json"]
        val exits = reports["system-exits.json"]
        if (crash == null && exits == null) return null
        val leading = crash ?: exits!!
        fun encode(selected: Map<String, String>): String = JSONObject()
            .put("atMs", runCatching {
                if (crash != null) JSONObject(crash).optLong("atMs")
                else JSONObject(exits!!).getJSONArray("exits").getJSONObject(0).optLong("atMs")
            }.getOrDefault(0L))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("reports", JSONObject(selected))
            .toString()

        val full = LinkedHashMap<String, String>().apply {
            reportNames.forEach { name -> reports[name]?.let { put(name, it) } }
        }
        // JSON escaping also consumes wire bytes. Trim oldest journal entries, preserving all report names.
        val journals = listOf("runtime-events.json" to "events", "event-history.json" to "events",
            "exception-history.json" to "exceptions")
        var payload = encode(full)
        for ((name, key) in journals) {
            val journal = full[name]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val rows = journal.optJSONArray(key) ?: continue
            while (payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES && rows.length() > 1) {
                rows.remove(0)
                full[name] = journal.put("relayTruncated", true).toString()
                full["diagnostics-status.json"] = JSONObject().put("failure", "relay-history-truncated")
                    .put("sourceStatus", reports["diagnostics-status.json"]).toString()
                payload = encode(full)
            }
        }
        if (payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) return payload
        val crashOnly = encode(mapOf(if (crash != null) "last-exception.json" to crash else "system-exits.json" to leading,
            "diagnostics-status.json" to JSONObject().put("failure", "relay-payload-too-large").toString()))
        return crashOnly.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES }
    }
}
