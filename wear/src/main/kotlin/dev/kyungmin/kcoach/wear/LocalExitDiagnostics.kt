package dev.kyungmin.kcoach.wear

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, bounded, excluded from backup; no throwable messages, intent extras or workout facts. */
internal object LocalExitDiagnostics {
    @Volatile private var screen = "UNKNOWN"
    @Volatile private var phase = "UNKNOWN"
    private var directory: File? = null
    private var priorStateAtLaunch: String? = null
    private var sessionToken = ""
    private var lastSampleNs = 0L
    private var lastStateAtMs = 0L
    private const val MAX_REPORT_BYTES = 48 * 1024

    private const val MAX_ENTRIES = 20
    private const val MAX_ENTRY_BYTES = 2048
    @Volatile internal var lastFailure: String? = null
        private set
    fun install(app: Application) {
        lastFailure = null
        guarded("install") {
            directory = File(app.noBackupFilesDir, "exit-diagnostics").apply { mkdirs() }
            priorStateAtLaunch = readObject("last-state.json")?.toString()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { recordException(error) } finally { previous?.uncaughtException(thread, error) }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { screen = activity.javaClass.simpleName }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        Thread({ refreshSystemHistory(app) }, "local-exit-history").start()
    }

    /** Refresh on export too, so the startup worker need not have completed first. */
    internal fun refreshSystemHistory(context: Context) {
        guarded("record") {
            if (Build.VERSION.SDK_INT >= 30) {
                val exits = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 8)
                val rows = JSONArray()
                exits.forEach { exit -> rows.put(JSONObject().put("atMs", exit.timestamp)
                    .put("reason", exit.reason).put("status", exit.status).put("importance", exit.importance)
                    .put("pssKb", exit.pss).put("rssKb", exit.rss)) }
                for (i in rows.length() - 1 downTo 0) {
                    val exit = rows.getJSONObject(i)
                    append("event-history.json", "events", stateJson().put("kind", "system-exit")
                        .put("atMs", exit.getLong("atMs")).put("observedAtMs", System.currentTimeMillis())
                        .put("stateSource", "collector").put("exit", exit),
                        "${exit.getLong("atMs")}:${exit.getInt("reason")}:${exit.getInt("status")}")
                }
                write("system-exits.json", JSONObject().put("exits", rows)
                    .put("priorState", priorStateAtLaunch?.let(::JSONObject)))
            }
        }
    }

    fun workoutPhase(workoutPhase: String, sessionId: String, sampleNs: Long) {
        val token = token(sessionId)
        lastSampleNs = sampleNs
        val now = android.os.SystemClock.elapsedRealtime()
        if (phase == workoutPhase && sessionToken == token && now - lastStateAtMs < 5_000L) return
        lastStateAtMs = now
        phase = workoutPhase; sessionToken = token
        guarded("record") { write("last-state.json", stateJson()) }
    }

    private fun token(sessionId: String): String = if (sessionId.isBlank()) "" else
        java.security.MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /** Bounded local interruption/recovery evidence, not a measured downtime duration. */
    @Synchronized fun event(kind: String, sessionId: String, workoutPhase: String, sampleNs: Long, error: Throwable? = null) {
        guarded("runtime-event") {
            sessionToken = token(sessionId); phase = workoutPhase.take(128); lastSampleNs = sampleNs
            val row = stateJson().put("kind", kind.take(64)).put("errorType", error?.javaClass?.name?.take(128))
            append("runtime-events.json", "events", row, limit = 32)
            append("event-history.json", "events", row)
            write("last-state.json", stateJson())
        }
    }

    /** Call at start request, foreground promotion and stop; detail must contain no private facts. */
    @Synchronized fun event(kind: String, detail: String = "", startId: Int? = null,
                            sessionId: String? = null): Result<Unit> = guarded("event") {
        if (sessionId != null) sessionToken = token(sessionId)
        append("event-history.json", "events", stateJson().put("kind", kind.take(64))
            .put("detail", detail.take(128)).put("startId", startId))
    }

    fun state(screenName: String, workoutPhase: String) {
        screen = screenName; phase = workoutPhase
        guarded("record") { write("last-state.json", stateJson()) }
    }

    @Synchronized internal fun recordException(error: Throwable) {
        guarded("record") {
            val causes = JSONArray()
            var cause: Throwable? = error
            repeat(4) {
                val current = cause ?: return@repeat
                // StackTraceElement contains code locations, without the exception's possibly private message.
                causes.put(JSONObject().put("type", current.javaClass.name.take(128))
                    .put("frames", JSONArray(current.stackTrace.take(6).map { it.toString().take(120) })))
                cause = current.cause?.takeUnless { it === current }
            }
            val report = stateJson().put("causes", causes)
            append("exception-history.json", "exceptions", report)
            write("last-exception.json", report)
        }
    }

    /** Bounded reports plus an explicit status when any diagnostic operation failed. */
    @Synchronized fun readReports(): Result<Map<String, String>> = runCatching {
        val reports = linkedMapOf<String, String>()
        listOf("last-exception.json", "last-state.json", "system-exits.json", "exception-history.json", "event-history.json", "runtime-events.json").forEach { name ->
            readObject(name)?.let { reports[name] = it.toString() }
        }
        lastFailure?.let { reports["diagnostics-status.json"] = JSONObject().put("failure", it).toString() }
        reports
    }

    private fun stateJson() = JSONObject().put("atMs", System.currentTimeMillis()).put("screen", screen.take(128)).put("phase", phase.take(128))
        .put("sessionToken", sessionToken).put("lastSampleNs", lastSampleNs)
        .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
    private fun failure(operation: String, error: Throwable) {
        lastFailure = "$operation:${error.javaClass.simpleName}"
        Log.e("LocalExitDiagnostics", "Diagnostic operation failed: $operation (${error.javaClass.simpleName})")
    }

    private inline fun guarded(operation: String, action: () -> Unit): Result<Unit> =
        runCatching(action).onFailure { failure(operation, it) }

    private fun readObject(name: String): JSONObject? {
        val dir = directory ?: return null
        val base = File(dir, name)
        if (!base.exists() && !File(dir, "$name.bak").exists()) return null
        return runCatching {
            AtomicFile(base).openRead().use { stream ->
                val bytes = stream.readBytesBounded()
                JSONObject(bytes.toString(Charsets.UTF_8))
            }
        }.onFailure { failure("read-$name", it) }.getOrNull()
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_REPORT_BYTES) { "Report too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    @Synchronized private fun append(name: String, key: String, row: JSONObject,
                                      id: String? = null, limit: Int = MAX_ENTRIES) {
        if (id != null) row.put("id", id)
        // Remove oldest frames until the complete JSON entry fits the byte limit.
        val causes = row.optJSONArray("causes")
        while (row.toString().toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) {
            val frames = (0 until (causes?.length() ?: 0)).mapNotNull { causes?.optJSONObject(it)?.optJSONArray("frames") }
                .lastOrNull { it.length() > 0 }
            check(frames != null) { "Diagnostic entry too large" }
            frames.remove(frames.length() - 1)
        }
        val old = readObject(name)?.optJSONArray(key) ?: JSONArray()
        if (id != null && (0 until old.length()).any { old.optJSONObject(it)?.optString("id") == id }) return
        val rows = JSONArray()
        for (i in maxOf(0, old.length() - limit + 1) until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.toString().toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) rows.put(item)
        }
        rows.put(row)
        while (JSONObject().put(key, rows).toString().toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) rows.remove(0)
        write(name, JSONObject().put(key, rows))
    }

    @Synchronized private fun write(name: String, value: JSONObject) {
        val dir = checkNotNull(directory) { "Diagnostics not installed" }
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_REPORT_BYTES) { "Report too large" }
        val base = File(dir, name)
        check(!base.exists() || base.isFile) { "Report target is not a file" }
        val file = AtomicFile(base)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            // AtomicFile logs some sync/rename failures instead of throwing them.
            check(!File(dir, "$name.new").exists() && file.openRead().use { it.readBytesBounded() }.contentEquals(bytes)) {
                "Atomic report write did not persist"
            }
        }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
