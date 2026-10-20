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
    private val reportNames = listOf("last-exception.json", "system-exits.json", "last-state.json")

    fun relay(context: Context) {
        val reports = LocalExitDiagnostics.readReports().getOrNull().orEmpty()
        val payload = buildPayload(reports) ?: return
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SENT, null) == hash) return
        var sent = false
        runCatching {
            Tasks.await(Wearable.getNodeClient(context).connectedNodes).forEach { node ->
                Tasks.await(Wearable.getMessageClient(context).sendMessage(
                    node.id, WearSync.PATH_DIAGNOSTICS, payload.toByteArray(),
                ))
                sent = true
            }
        }
        // Only remember it once a phone actually accepted the message, so an offline crash retries next launch.
        if (sent) prefs.edit().putString(KEY_SENT, hash).apply()
    }

    /** Null when there is nothing to report; the newest exception is what identifies a crash. */
    internal fun buildPayload(reports: Map<String, String>): String? {
        val crash = reports["last-exception.json"] ?: return null
        fun encode(selected: Map<String, String>): String = JSONObject()
            .put("atMs", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("reports", JSONObject(selected))
            .toString()

        val full = LinkedHashMap<String, String>().apply {
            put("last-exception.json", crash)
            reportNames.drop(1).forEach { name -> reports[name]?.let { put(name, it) } }
        }
        val fullPayload = encode(full)
        if (fullPayload.toByteArray().size <= MAX_PAYLOAD_BYTES) return fullPayload
        val crashOnly = encode(mapOf("last-exception.json" to crash))
        return crashOnly.takeIf { it.toByteArray().size <= MAX_PAYLOAD_BYTES }
    }
}
