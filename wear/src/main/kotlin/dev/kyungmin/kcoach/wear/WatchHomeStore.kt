package dev.kyungmin.kcoach.wear

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Display-only companion data. Missing data never becomes a measured zero. */
internal class WatchHomeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("watch-home", Context.MODE_PRIVATE)
    /** What the watch itself measured for the finished workout; a value it never saw is null, not zero. */
    data class Summary(val elapsedMs: Long?, val sets: Int?, val volumeKg: Double?, val averageHeartRate: Int?, val recordsBroken: Int?) {
        fun encode(): String = JSONObject().apply {
            elapsedMs?.let { put("elapsedMs", it) }; sets?.let { put("sets", it) }; volumeKg?.let { put("volumeKg", it) }
            averageHeartRate?.let { put("averageHeartRate", it) }; recordsBroken?.let { put("recordsBroken", it) }
        }.toString()
        companion object {
            fun decode(raw: String): Summary? = runCatching {
                val json = JSONObject(raw)
                Summary(json.optLong("elapsedMs", -1L).takeIf { it >= 0 }, json.optInt("sets", -1).takeIf { it >= 0 },
                    json.optDouble("volumeKg", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                    json.optInt("averageHeartRate", -1).takeIf { it in 20..240 }, json.optInt("recordsBroken", -1).takeIf { it >= 0 })
            }.getOrNull()
        }
    }
    data class Completion(val sessionId: String, val endedAtMs: Long, val summary: Summary? = null)
    fun completion(): Completion? = prefs.getString("completedSession", null)?.takeIf(::validSession)
        ?.let { Completion(it, prefs.getLong("completedAt", 0L), prefs.getString("completedSummary", null)?.let(Summary::decode)) }
    fun complete(session: String, nowMs: Long = System.currentTimeMillis()) {
        require(validSession(session))
        val summary = WatchSessionTally.get(context).finish(session, nowMs)
        check(prefs.edit().putString("completedSession", session).putLong("completedAt", nowMs)
            .putString("completedSummary", summary?.encode()).putBoolean("showCompletion", true).commit())
    }
    fun showsCompletion() = prefs.getBoolean("showCompletion", false) && completion() != null
    fun leaveCompletion() { prefs.edit().putBoolean("showCompletion", false).apply() }
    fun overview(): JSONObject? = prefs.getString("overview", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun receiveOverview(payload: String): Boolean = synchronized(lock) {
        runCatching {
            require(payload.length <= 8_192)
            val json = JSONObject(payload)
            require(json.getInt("schemaVersion") == 1)
            val stamp = json.getLong("generatedAtMs")
            require(stamp > 0 && stamp <= System.currentTimeMillis() + 300_000L)
            LocalDate.parse(json.getString("weekStartDate"))
            require(json.getInt("weekWorkoutCount") in 0..10_000)
            if (!json.isNull("recent")) {
                val recent = json.getJSONObject("recent")
                require(validSession(recent.getString("sessionId")))
                require(recent.getString("routineName").length in 1..120)
                require(recent.getLong("endedAtMs") in 1..stamp)
            }
            require(stamp >= (overview()?.optLong("generatedAtMs") ?: 0L))
            check(prefs.edit().putString("overview", json.toString()).commit())
        }.isSuccess
    }
    fun weekCount(today: LocalDate = LocalDate.now()): Int? = overview()?.takeIf {
        it.optString("weekStartDate") == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
    }?.let { overview ->
        val count = overview.opt("weekWorkoutCount")
        if (count is Int && count in 0..10_000) count else {
            android.util.Log.w("WatchHomeStore", "Invalid cached week count; showing unavailable")
            null
        }
    }
    companion object {
        const val PATH_OVERVIEW = "/kcoach/watch/overview"
        const val ACTION_OVERVIEW = "dev.kyungmin.kcoach.WATCH_OVERVIEW"
        private val lock = Any()
        fun validSession(value: String) = value.matches(Regex("[A-Za-z0-9._-]{1,160}"))
    }
}
