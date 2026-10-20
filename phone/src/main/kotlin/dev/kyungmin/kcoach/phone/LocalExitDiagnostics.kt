package dev.kyungmin.kcoach.phone

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
    private const val MAX_REPORT_BYTES = 64 * 1024
    @Volatile private var screen = "UNKNOWN"
    @Volatile private var phase = "UNKNOWN"
    private var directory: File? = null
    private var priorStateAtLaunch: String? = null

    fun install(app: Application) {
        directory = File(app.noBackupFilesDir, "exit-diagnostics").apply { mkdirs() }
        priorStateAtLaunch = runCatching { File(directory, "last-state.json").readText() }.getOrNull()
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

    fun workoutPhase(workoutPhase: String) {
        if (phase == workoutPhase) return
        phase = workoutPhase
        runCatching { write("last-state.json", stateJson()) }
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
                    .put("frames", JSONArray(current.stackTrace.take(48).map { it.toString() })))
                cause = current.cause?.takeUnless { it === current }
            }
            write("last-exception.json", stateJson().put("causes", causes))
        }
    }

    /**
     * Keeps the watch's last crash report under watch-* so one export carries both devices.
     * Called from the Wear listener; malformed or oversized payloads are dropped.
     */
    @Synchronized fun storeWatchReports(watchPayload: String) {
        runCatching {
            val dir = directory ?: return@runCatching
            val reports = JSONObject(watchPayload).optJSONObject("reports") ?: return@runCatching
            listOf("last-exception.json", "last-state.json", "system-exits.json").forEach { name ->
                val text = reports.optString(name).takeIf { it.isNotBlank() } ?: return@forEach
                if (text.toByteArray().size > MAX_REPORT_BYTES) return@forEach
                val file = AtomicFile(File(dir, "watch-$name"))
                val stream = file.startWrite()
                try { stream.write(text.toByteArray()); file.finishWrite(stream) }
                catch (error: Throwable) { file.failWrite(stream); throw error }
            }
        }
    }

    /** Local UI integration point. Does not upload, delete, or alter the reports. Call off the main thread. */
    @Synchronized fun readReports(): Result<Map<String, String>> = runCatching {
        val dir = directory ?: return@runCatching emptyMap()
        listOf("last-exception.json", "last-state.json", "system-exits.json",
            "watch-last-exception.json", "watch-last-state.json", "watch-system-exits.json").mapNotNull { name ->
            val file = File(dir, name)
            if (file.exists() && file.length() <= MAX_REPORT_BYTES) name to AtomicFile(file).readFully().toString(Charsets.UTF_8) else null
        }.toMap()
    }

    private fun stateJson() = JSONObject().put("atMs", System.currentTimeMillis()).put("screen", screen).put("phase", phase)
        .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
    @Synchronized private fun write(name: String, value: JSONObject) {
        val dir = directory ?: return
        val file = AtomicFile(File(dir, name))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
