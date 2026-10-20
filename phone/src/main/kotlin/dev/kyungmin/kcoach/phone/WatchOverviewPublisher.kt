package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/** Phone-owned history summary for the watch's idle screen; never starts a workout. */
internal object WatchOverviewPublisher {
    private const val PATH = "/kcoach/watch/overview"
    private val sessionId = Regex("[A-Za-z0-9._-]{1,160}")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kcoach-watch-overview").apply { isDaemon = true }
    }

    fun publish(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching { publishNow(app) }
                .onFailure { Log.w("KCoachEvent", "watch history overview retained for next refresh", it) }
        }
    }

    internal fun publishNow(context: Context) {
        // Idle display data gets one bounded outbound drain, without a foreground-service start obligation.
        val bridge = PhoneWearBridge(context)
        try { bridge.sendRaw(PATH, snapshot(context).toString()) }
        finally { bridge.closeAfterPendingDelivery() }
    }

    internal fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        // Match the phone home screen, including legacy records without a session ID.
        val count = WorkoutLogStore(context).load().count { it.epochDay in monday.toEpochDay()..monday.plusDays(6).toEpochDay() }
        val recent = FinishedWorkoutStore(context).allEvidence()
            .filter { sessionId.matches(it.snapshot.sessionId) && it.finishedAtMs in 1..nowMs }
            .maxByOrNull { it.finishedAtMs }
        return JSONObject().put("schemaVersion", 1).put("generatedAtMs", nowMs)
            .put("weekStartDate", monday.toString()).put("weekWorkoutCount", count.coerceAtMost(10_000))
            .put("recent", recent?.let {
                JSONObject().put("sessionId", it.snapshot.sessionId)
                    .put("routineName", it.title.trim().take(120).ifBlank { "운동" })
                    .put("endedAtMs", it.finishedAtMs)
            } ?: JSONObject.NULL)
    }
}
