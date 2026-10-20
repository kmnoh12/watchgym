package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WearSync

/** Journal the chosen policy before I/O; retry it before any session restoration. */
internal class WorkoutTermination(private val context: Context) {
    private val prefs = context.getSharedPreferences("workout-termination", Context.MODE_PRIVATE)
    fun pending(): Boolean = prefs.contains("session")

    fun consumeHomeReset(): Boolean {
        val reset = prefs.getBoolean("homeReset", false)
        if (reset) {
            // A failed cleanup must not kill the launch; the flag remains for the next attempt.
            if (!prefs.edit().remove("homeReset").commit()) {
                android.util.Log.w("KCoachEvent", "home reset flag retained; storage write deferred")
            }
        }
        return reset
    }

    fun stop(saveCompleted: Boolean = true, nowMs: Long = System.currentTimeMillis()): Result<Unit> = synchronized(WorkoutRuntimeLock.monitor) {
        runCatching {
            if (!pending()) {
                val snapshot = SessionStore(context).loadActive().getOrThrow() ?: return@runCatching
                check(prefs.edit().putString("session", snapshot.sessionId).putBoolean("homeReset", true)
                    .putBoolean("saveCompleted", saveCompleted).commit())
            } else if (!saveCompleted) {
                // A later explicit discard (including a hard exit) cancels an unfinished save.
                check(prefs.edit().putBoolean("saveCompleted", false).putBoolean("homeReset", true).commit())
            }
            finishAndClear(nowMs)
        }
    }

    fun recover(nowMs: Long = System.currentTimeMillis()): Result<Unit> = synchronized(WorkoutRuntimeLock.monitor) {
        runCatching { if (pending()) finishAndClear(nowMs) }
    }

    private fun finishAndClear(nowMs: Long) {
        val id = prefs.getString("session", null) ?: return
        // Local discard must not depend on watch availability or outbox write success.
        val queued = runCatching {
            PhoneCommandOutbox(context).enqueue(WearSync.command("STOP", id, "", 0, 0))
        }
        val store = SessionStore(context)
        val saveCompleted = prefs.getBoolean("saveCompleted", true)
        val snapshot = store.loadActive().getOrThrow()
        if (snapshot?.sessionId == id) {
            if (saveCompleted) {
                val pausedMs = snapshot.totalPausedMs + if (snapshot.pausedSinceMs > 0)
                    (nowMs - snapshot.pausedSinceMs).coerceAtLeast(0) else 0
                val minutes = ((nowMs - snapshot.startedAtMs - pausedMs).coerceAtLeast(0) / 60_000)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                // The existing finished store skips empty sessions and projects only completed sets.
                FinishedWorkoutStore(context).finish(snapshot, snapshot.routineName.ifBlank { "운동" }, minutes)
            }
            check(store.clearActive(id)) { "Failed to close stopped workout" }
        }
        if (!saveCompleted || snapshot?.plannedSets?.none { it.completed } == true) WorkoutTimeStore(context).discard(id)
        queued.getOrThrow() // Leave the journal for durable STOP retry if enqueue failed.
        check(prefs.edit().remove("session").remove("saveCompleted").commit())
    }
}
