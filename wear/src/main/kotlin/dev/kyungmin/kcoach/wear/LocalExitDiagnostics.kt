package dev.kyungmin.kcoach.wear

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
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

    fun install(app: Application) {
        directory = File(app.noBackupFilesDir, "exit-diagnostics").apply { mkdirs() }
        priorStateAtLaunch = runCatching { File(directory, "last-state.json").takeIf { it.length() <= MAX_REPORT_BYTES }?.readText() }.getOrNull()
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
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val exits = context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 8)
                val rows = JSONArray()
                exits.forEach { exit -> rows.put(JSONObject().put("atMs", exit.timestamp)
                    .put("reason", exit.reason).put("status", exit.status).put("importance", exit.importance)
                    .put("pssKb", exit.pss).put("rssKb", exit.rss)) }
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
        runCatching { write("last-state.json", stateJson()) }
    }

    private fun token(sessionId: String): String = if (sessionId.isBlank()) "" else
        java.security.MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /** Bounded local interruption/recovery evidence, not a measured downtime duration. */
    @Synchronized fun event(kind: String, sessionId: String, workoutPhase: String, sampleNs: Long, error: Throwable? = null) {
        runCatching {
            sessionToken = token(sessionId); phase = workoutPhase; lastSampleNs = sampleNs
            val file = File(directory ?: return, "runtime-events.json")
            val old = if (file.isFile && file.length() <= MAX_REPORT_BYTES)
                JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)).optJSONArray("events") else null
            val rows = JSONArray()
            if (old != null) for (i in maxOf(0, old.length() - 31) until old.length()) rows.put(old.getJSONObject(i))
            rows.put(stateJson().put("kind", kind.take(64)).put("errorType", error?.javaClass?.name))
            write("runtime-events.json", JSONObject().put("events", rows))
            write("last-state.json", stateJson())
        }
    }

    fun state(screenName: String, workoutPhase: String) {
        screen = screenName; phase = workoutPhase
        runCatching { write("last-state.json", stateJson()) }
    }

    internal fun recordException(error: Throwable) {
        runCatching {
            val causes = JSONArray()
            var cause: Throwable? = error
            repeat(6) {
                val current = cause ?: return@repeat
                // StackTraceElement contains code locations, without the exception's possibly private message.
                causes.put(JSONObject().put("type", current.javaClass.name)
                    .put("frames", JSONArray(current.stackTrace.take(32).map { it.toString().take(180) })))
                cause = current.cause?.takeUnless { it === current }
            }
            write("last-exception.json", stateJson().put("causes", causes))
        }
    }

    /** Local UI integration point. Does not upload, delete, or alter the reports. Call off the main thread. */
    @Synchronized fun readReports(): Result<Map<String, String>> = runCatching {
        val dir = directory ?: return@runCatching emptyMap()
        listOf("last-exception.json", "last-state.json", "system-exits.json", "runtime-events.json").mapNotNull { name ->
            val file = File(dir, name)
            if (file.exists() && file.length() <= MAX_REPORT_BYTES) name to AtomicFile(file).readFully().toString(Charsets.UTF_8) else null
        }.toMap()
    }

    private fun stateJson() = JSONObject().put("atMs", System.currentTimeMillis()).put("screen", screen).put("phase", phase)
        .put("sessionToken", sessionToken).put("lastSampleNs", lastSampleNs)
        .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
    @Synchronized private fun write(name: String, value: JSONObject) {
        val dir = directory ?: return
        val file = AtomicFile(File(dir, name))
        val bytes = value.toString().toByteArray()
        if (bytes.size > MAX_REPORT_BYTES) return
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
