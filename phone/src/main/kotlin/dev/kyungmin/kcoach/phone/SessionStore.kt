package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class ManualSetApplication(val ack: ManualSetAck, val snapshot: WorkoutSnapshot?, val restStarted: Boolean = false)
data class WatchWorkoutApplication(val ack: WatchWorkoutControlAck, val snapshot: WorkoutSnapshot?)

class SessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val capturePrefs = context.getSharedPreferences("calibration-captures", Context.MODE_PRIVATE)

    private var observedRevision: Long? = null

    /** Prevent an old Activity from overwriting a completion committed by a service. */
    fun saveActive(snapshot: WorkoutSnapshot): Boolean = synchronized(lock) {
        if (capturePrefs.getString("active", null) != null || isSessionClosed(snapshot.sessionId)) return@synchronized false
        val revision = prefs.getLong(KEY_REVISION, 0)
        if (observedRevision != revision && (observedRevision != null || prefs.contains(KEY_ACTIVE_SNAPSHOT))) return@synchronized false
        commit(snapshot)
    }

    /** Return exactly the committed revision, including store-owned rest version fields. */
    fun savePlan(snapshot: WorkoutSnapshot): Result<WorkoutSnapshot> = synchronized(lock) {
        runCatching {
            check(saveActive(snapshot)) { "Plan save rejected (revision conflict or session unavailable)" }
            requireNotNull(read())
        }
    }

    fun loadActive(): Result<WorkoutSnapshot?> = synchronized(lock) { runCatching { read() } }

    /** Preview is a separate durable offer. It is never an active session or an elapsed-time origin. */
    fun prepareForWatch(snapshot: WorkoutSnapshot, nowMs: Long): Result<WatchWorkoutOffer> = synchronized(lock) {
        runCatching {
            require(nowMs >= 0 && capturePrefs.getString("active", null) == null && read() == null)
            require(snapshot.startedAtMs == 0L && snapshot.phase == WorkoutPhase.WORKING && !snapshot.paused &&
                snapshot.restEndAtMs == 0L && snapshot.performedReps.isEmpty() &&
                snapshot.plannedSets.all { !it.completed && it.performedReps == null && it.repSource == null && it.identityRequired })
            val target = requireNotNull(WorkoutSessionReducer.nextPendingSet(snapshot))
            val encoded = WorkoutSnapshotCodec.encode(snapshot)
            val offer = WatchWorkoutOffer(snapshot.sessionId, snapshot.routineName, target.exerciseName,
                target.setId, target.attemptId, SensorCaptureFormat.sha256(encoded.toByteArray()), Math.addExact(nowMs, 30 * 60_000L))
            check(prefs.edit().putString(KEY_PREPARED, JSONObject().put("snapshot", encoded).put("offer", offer.encode()).toString()).commit())
            offer
        }
    }

    fun preparedForWatch(nowMs: Long): Result<WatchWorkoutOffer?> = synchronized(lock) {
        runCatching {
            val json = prefs.getString(KEY_PREPARED, null)?.let(::JSONObject) ?: return@runCatching null
            val offer = WatchWorkoutOffer.decode(json.getString("offer"))
            require(SensorCaptureFormat.sha256(json.getString("snapshot").toByteArray()) == offer.planHash)
            offer.takeIf { nowMs >= 0 && nowMs <= it.expiresAtMs && read() == null && capturePrefs.getString("active", null) == null }
        }
    }

    /** Receipt and state transition are committed together; old NEXT retries cannot skip a later rest. */
    fun applyWatchControl(command: WatchWorkoutControl, nowMs: Long): Result<WatchWorkoutApplication> = synchronized(lock) {
        runCatching {
            require(nowMs >= 0)
            val current = read()
            fun answer(status: WatchWorkoutControlStatus, snapshot: WorkoutSnapshot? = current) =
                WatchWorkoutApplication(WatchWorkoutControlAck(command.commandId, command.sessionId, command.setId, command.attemptId, status), snapshot)
            val receipts = JSONArray(prefs.getString(KEY_WATCH_CONTROLS, "[]")!!)
            for (index in 0 until receipts.length()) {
                val row = receipts.getJSONObject(index)
                if (row.getString("id") == command.commandId) {
                    if (row.getString("fingerprint") != controlFingerprint(command)) return@runCatching answer(WatchWorkoutControlStatus.REJECTED)
                    val ack = WatchWorkoutControlAck.decode(row.getString("ack"))
                    return@runCatching WatchWorkoutApplication(if (ack.status == WatchWorkoutControlStatus.APPLIED)
                        ack.copy(status = WatchWorkoutControlStatus.DUPLICATE) else ack, current)
                }
            }
            fun rejected(status: WatchWorkoutControlStatus): WatchWorkoutApplication {
                val result = answer(status)
                check(prefs.edit().putString(KEY_WATCH_CONTROLS, controlReceipts(command, result.ack)).commit())
                return result
            }
            if (capturePrefs.getString("active", null) != null) return@runCatching rejected(WatchWorkoutControlStatus.BUSY)
            val next = when (command.action) {
                WatchWorkoutAction.START_PREPARED -> {
                    if (current != null) return@runCatching rejected(WatchWorkoutControlStatus.BUSY)
                    val json = prefs.getString(KEY_PREPARED, null)?.let(::JSONObject)
                        ?: return@runCatching rejected(WatchWorkoutControlStatus.NO_PLAN)
                    val offer = preparedForWatch(nowMs).getOrThrow() ?: return@runCatching rejected(WatchWorkoutControlStatus.NO_PLAN)
                    if (command.sessionId != offer.sessionId || command.setId != offer.setId || command.attemptId != offer.attemptId ||
                        command.planHash != offer.planHash) return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    WorkoutSnapshotCodec.decode(json.getString("snapshot")).copy(startedAtMs = nowMs)
                }
                WatchWorkoutAction.REST_ADJUST, WatchWorkoutAction.REST_PAUSE, WatchWorkoutAction.REST_RESUME, WatchWorkoutAction.EDIT_SET -> {
                    val editingCurrent = command.action == WatchWorkoutAction.EDIT_SET && command.which == "CURRENT"
                    val requiredPhase = if (editingCurrent) WorkoutPhase.WORKING else WorkoutPhase.RESTING
                    val restControl = command.action != WatchWorkoutAction.EDIT_SET
                    val resting = current?.phase == WorkoutPhase.RESTING || current?.let { it.paused && it.pausedRestRemainingMs > 0 } == true
                    // Rest expiry and a wrist pause can cross in transit. Apply that intent to the
                    // same next attempt instead of rejecting it just because the timer won the race.
                    val boundaryPause = restControl && command.action in setOf(WatchWorkoutAction.REST_PAUSE, WatchWorkoutAction.REST_RESUME) &&
                        current?.lastCompletedSetKey != null && current.restEndAtMs == 0L && current.pausedRestRemainingMs == 0L &&
                        current.phase in setOf(WorkoutPhase.WORKING, WorkoutPhase.PAUSED)
                    if (current?.sessionId != command.sessionId || (if (restControl) !resting && !boundaryPause else current.phase != requiredPhase || current.paused))
                        return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    if (restControl && command.seq <= current.watchRestSeq) return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    val target = WorkoutSessionReducer.nextPendingSet(current)
                    // REST/SYNC identifies the next set, including an empty identity after the final set.
                    if (target?.setId.orEmpty() != command.setId || target?.attemptId.orEmpty() != command.attemptId)
                        return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    val previous = current.plannedSets.singleOrNull {
                        SetIdentity.completionKey(current.sessionId, it) == current.lastCompletedSetKey && it.completed
                    }
                    if (!editingCurrent && previous == null) return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    if (restControl) {
                        requireNotNull(previous)
                        val seconds = requireNotNull(command.restSeconds)
                        // Watch rest changes are one-off: only the timer deadline below changes, never the sets' rest values.
                        val sets = current.plannedSets
                        val pause = command.action == WatchWorkoutAction.REST_PAUSE ||
                            command.action == WatchWorkoutAction.REST_ADJUST && command.pausedRestRemainingMs != null
                        val remaining = if (pause) (command.pausedRestRemainingMs ?: 0L).coerceIn(1, 1_800_000L) else 0L
                        if (boundaryPause) current.copy(watchRestSeq = command.seq, paused = pause,
                            phase = if (pause) WorkoutPhase.PAUSED else WorkoutPhase.WORKING,
                            pausedSinceMs = if (pause) current.pausedSinceMs.takeIf { current.paused } ?: nowMs else 0,
                            totalPausedMs = current.totalPausedMs + if (current.paused && !pause) (nowMs - current.pausedSinceMs).coerceAtLeast(0) else 0)
                        else current.copy(plannedSets = sets, watchRestSeq = command.seq,
                            restEndAtMs = if (pause) 0 else requireNotNull(command.restEndAtMs).coerceIn(0, nowMs + 1_800_000L),
                            paused = pause, pausedRestRemainingMs = if (pause) remaining else 0,
                            phase = if (pause) WorkoutPhase.PAUSED else WorkoutPhase.RESTING,
                            pausedSinceMs = if (pause) current.pausedSinceMs.takeIf { current.paused } ?: nowMs else 0,
                            totalPausedMs = current.totalPausedMs + if (current.paused && !pause) (nowMs - current.pausedSinceMs).coerceAtLeast(0) else 0)

                    } else {
                        val edited = if (command.which == "PREVIOUS") previous else target
                            ?: return@runCatching rejected(WatchWorkoutControlStatus.REJECTED)
                        val sets = current.plannedSets.map { set ->
                            if (set !== edited) set else set.copy(
                                weightKg = command.weightKg ?: set.weightKg,
                                targetReps = if (!set.completed && command.reps >= 0) command.reps else set.targetReps,
                                performedReps = if (set.completed && command.reps >= 0) command.reps else set.performedReps,
                                repSource = if (set.completed && command.reps >= 0) SetRepSource.MANUAL else set.repSource,
                                unilateral = if (set.completed && command.reps >= 0) set.unilateral?.copy(
                                    watchReps = command.reps, watchRepSource = SetRepSource.MANUAL) else set.unilateral)
                        }
                        current.copy(plannedSets = sets, performedReps = if (editingCurrent) current.performedReps else
                            sets.filter { it.completed }.groupBy { it.exerciseId }
                                .mapValues { (_, values) -> values.sumOf { it.performedReps ?: 0 } })
                    }
                }
                WatchWorkoutAction.START_OTHER_SIDE, WatchWorkoutAction.FINISH_WATCH_SIDE_ONLY, WatchWorkoutAction.COMPLETE_OTHER_SIDE -> {
                    if (current == null) return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    val result = UnilateralSetReducer.apply(current, command.sessionId, command.setId, command.attemptId,
                        UnilateralAction.valueOf(command.action.name), nowMs, command.reps.takeIf { it >= 0 })
                    when (result) {
                        is SetCompletionResult.Applied -> result.snapshot
                        is SetCompletionResult.Duplicate -> return@runCatching rejected(WatchWorkoutControlStatus.DUPLICATE)
                        is SetCompletionResult.Rejected -> return@runCatching rejected(WatchWorkoutControlStatus.REJECTED)
                    }
                }
                WatchWorkoutAction.NEXT_SET -> {
                    if (current?.sessionId != command.sessionId) return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    val target = WorkoutSessionReducer.nextPendingSet(current)
                    // Skipping works while resting and also while that rest is paused.
                    val resting = current.phase == WorkoutPhase.RESTING && !current.paused || current.paused && current.pausedRestRemainingMs > 0
                    if (target?.setId != command.setId || target.attemptId != command.attemptId || !resting)
                        return@runCatching rejected(WatchWorkoutControlStatus.STALE)
                    WorkoutSessionReducer.advanceAfterRest(current, nowMs, skip = true)
                        ?: return@runCatching rejected(WatchWorkoutControlStatus.REJECTED)
                }
            }
            val result = answer(WatchWorkoutControlStatus.APPLIED, next)
            check(commit(next, watchCommand = command, watchAck = result.ack))
            result.copy(snapshot = read())
        }
    }

    /** One-off rest change: only the running timer moves; no set, exercise or routine rest value is touched. */
    fun adjustRestDuration(sessionId: String, deltaSeconds: Int, nowMs: Long, toSeconds: Int? = null): Result<WorkoutSnapshot?> = synchronized(lock) {
        runCatching {
            val current = read()?.takeIf { it.sessionId == sessionId } ?: return@runCatching null
            if (current.restEndAtMs <= 0 && current.pausedRestRemainingMs <= 0) return@runCatching current
            val remainingMs = if (current.restEndAtMs > 0) (current.restEndAtMs - nowMs).coerceAtLeast(0) else current.pausedRestRemainingMs
            val deltaMs = if (toSeconds != null) toSeconds.toLong() * 1000 - remainingMs else deltaSeconds.toLong() * 1000
            val next = current.copy(
                restEndAtMs = if (current.restEndAtMs > 0) (current.restEndAtMs + deltaMs).coerceIn(nowMs, nowMs + 1_800_000L) else 0,
                pausedRestRemainingMs = if (current.pausedRestRemainingMs > 0) (current.pausedRestRemainingMs + deltaMs).coerceIn(1, 1_800_000L) else 0)
            check(commit(next)); read()
        }
    }

    fun setPaused(sessionId: String, pause: Boolean, nowMs: Long): Result<WorkoutSnapshot?> = synchronized(lock) {
        runCatching {
            val current = read()?.takeIf { it.sessionId == sessionId } ?: return@runCatching null
            if (current.paused == pause) return@runCatching current
            val remaining = if (pause) (current.restEndAtMs - nowMs).coerceAtLeast(
                if (current.phase == WorkoutPhase.RESTING) 1L else 0L) else current.pausedRestRemainingMs
            val next = current.copy(paused = pause,
                phase = if (pause) WorkoutPhase.PAUSED else if (remaining > 0) WorkoutPhase.RESTING else WorkoutPhase.WORKING,
                restEndAtMs = if (pause) 0 else if (remaining > 0) nowMs + remaining else 0,
                pausedRestRemainingMs = if (pause) remaining else 0,
                pausedSinceMs = if (pause) nowMs else 0,
                totalPausedMs = current.totalPausedMs + if (pause) 0 else (nowMs - current.pausedSinceMs).coerceAtLeast(0))
            check(commit(next)); read()
        }
    }

    fun manualSetRevision(sessionId: String, setId: String, attemptId: String): Result<Long?> = synchronized(lock) {
        runCatching { read()?.takeIf { it.sessionId == sessionId }?.plannedSets
            ?.singleOrNull { it.setId == setId && it.attemptId == attemptId }?.let(::manualRevision) }
    }

    /** The actual count, revision and receipt are one durable transaction. */
    fun applyManualSet(command: ManualSetCommand, nowMs: Long): Result<ManualSetApplication> = synchronized(lock) {
        runCatching {
            require(nowMs >= 0)
            val current = read()
            val set = current?.takeIf { it.sessionId == command.sessionId }?.let {
                SetIdentity.resolve(it, command.exerciseId, command.setIndex, command.setId, command.attemptId)
                    ?.takeIf { resolved -> resolved.setIndex == command.setIndex }
            }
            fun answer(status: ManualSetStatus, revision: Long = set?.let(::manualRevision) ?: 0,
                       reps: Int? = set?.performedReps) = ManualSetApplication(
                ManualSetAck(command.commandId, command.sessionId, command.setId, command.attemptId,
                    status, revision, reps), current)
            val fingerprint = fingerprint(command)
            val receipts = JSONArray(prefs.getString(KEY_MANUAL_RECEIPTS, "[]")!!)
            for (index in 0 until receipts.length()) {
                val receipt = receipts.getJSONObject(index)
                if (receipt.getString("id") != command.commandId) continue
                if (receipt.getString("fingerprint") != fingerprint) return@runCatching answer(ManualSetStatus.REJECTED)
                val saved = ManualSetAck.decode(receipt.getString("ack"))
                return@runCatching if (set != null) answer(ManualSetStatus.DUPLICATE)
                    else ManualSetApplication(saved.copy(status = ManualSetStatus.DUPLICATE), current)
            }
            if (current == null || current.sessionId != command.sessionId) return@runCatching answer(ManualSetStatus.NO_SESSION)
            if (set == null) return@runCatching answer(ManualSetStatus.STALE)
            val revision = manualRevision(set)
            if (command.expectedRevision != revision) return@runCatching answer(ManualSetStatus.CONFLICT)
            val next: WorkoutSnapshot
            val restStarted: Boolean
            if (set.completed) {
                val sets = current.plannedSets.map {
                    if (it === set) it.copy(performedReps = command.performedReps, repSource = SetRepSource.MANUAL,
                        unilateral = it.unilateral?.copy(watchReps = command.performedReps, watchRepSource = SetRepSource.MANUAL)) else it
                }
                next = current.copy(plannedSets = sets, performedReps = sets.filter { it.completed }
                    .groupBy { it.exerciseId }.mapValues { (_, values) -> values.sumOf { it.performedReps ?: 0 } })
                restStarted = false
            } else {
                if (command.operation != ManualSetOperation.COMPLETE || current.phase == WorkoutPhase.RESTING || current.pausedRestRemainingMs > 0)
                    return@runCatching answer(ManualSetStatus.REJECTED)
                val restMs = Math.multiplyExact(set.restSeconds.toLong(), 1_000L)
                val result = if (set.unilateral?.stage == UnilateralStage.OTHER_SIDE_WORKING)
                    UnilateralSetReducer.apply(current, command.sessionId, command.setId, command.attemptId,
                        UnilateralAction.COMPLETE_OTHER_SIDE, nowMs, command.performedReps)
                else WorkoutSessionReducer.completeSet(current, SetCompletionEvent(command.sessionId,
                    command.exerciseId, command.setIndex, command.performedReps, Math.addExact(nowMs, restMs), 0,
                    SetRepSource.MANUAL, command.setId, command.attemptId))
                if (result !is SetCompletionResult.Applied) return@runCatching answer(ManualSetStatus.REJECTED)
                next = if (current.paused && result.snapshot.phase == WorkoutPhase.RESTING) result.snapshot.copy(paused = true, phase = WorkoutPhase.PAUSED,
                    restEndAtMs = 0, pausedRestRemainingMs = restMs) else result.snapshot
                restStarted = result.snapshot.phase == WorkoutPhase.RESTING
            }
            check(commit(next, manualCommand = command)) { "Failed to commit manual set" }
            val updated = SetIdentity.resolve(next, command.exerciseId, command.setIndex, command.setId, command.attemptId)!!
            ManualSetApplication(ManualSetAck(command.commandId, command.sessionId, command.setId, command.attemptId,
                ManualSetStatus.APPLIED, manualRevision(updated), updated.performedReps), read(), restStarted)
        }
    }

    fun completeSet(event: SetCompletionEvent): Result<SetCompletionResult> = synchronized(lock) {
        runCatching { complete(read() ?: error("Missing active workout snapshot"), event) }
    }

    fun completeWatchSet(event: SetCompletionEvent, nowMs: Long, completionId: String? = null): Result<SetCompletionResult> = synchronized(lock) {
        runCatching {
            val current = read() ?: error("Missing active workout snapshot")
            val set = SetIdentity.resolve(current, event.exerciseId, event.setIndex, event.setId, event.attemptId)
            if (event.sessionId == current.sessionId && completionId != null && set?.completed == true) {
                check(prefs.edit().putString(KEY_RECEIPTS, receiptList(event.sessionId, completionId)).commit())
                return@runCatching SetCompletionResult.Duplicate(current)
            }
            val restMs = (set?.restSeconds ?: 90) * 1_000L
            complete(current, event.copy(restEndAtMs = nowMs + restMs, repSource = SetRepSource.WATCH), completionId, if (current.paused) restMs else null)
        }
    }

    /** Only one timer can claim a rest transition, including after a service restart. */
    fun advanceAfterRest(nowMs: Long, skip: Boolean = false): Result<WorkoutSnapshot?> = synchronized(lock) {
        runCatching {
            val current = read() ?: return@runCatching null
            val next = WorkoutSessionReducer.advanceAfterRest(current, nowMs, skip) ?: return@runCatching null
            check(commit(next)) { "Failed to commit rest transition" }
            read()
        }
    }

    internal fun confirmReps(sessionId: String, analyses: Map<String, SetAnalysisUi>): Result<WorkoutSnapshot?> = synchronized(lock) {
        runCatching {
            val current = read()?.takeIf { it.sessionId == sessionId } ?: return@runCatching null
            val updated = analyses.values.fold(current) { snapshot, analysis ->
                WorkoutSessionReducer.confirmReps(snapshot, analysis.exerciseId, analysis.setIndex, analysis.confirmedReps, analysis.setId, analysis.attemptId)
            }
            if (updated != current) check(commit(updated)) { "Failed to persist confirmed reps" }
            updated
        }
    }

    fun clearActive(expectedSessionId: String? = null): Boolean = synchronized(lock) {
        val current = runCatching { read() }.getOrNull()
        if (expectedSessionId != null) {
            if (current?.sessionId != expectedSessionId) return@synchronized false
        } else if (observedRevision != prefs.getLong(KEY_REVISION, 0)) return@synchronized false
        val revision = prefs.getLong(KEY_REVISION, 0) + 1
        val session = runCatching {
            prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let(WorkoutSnapshotCodec::decode)?.sessionId
        }.getOrNull()
        val edit = prefs.edit().remove(KEY_ACTIVE_SNAPSHOT).putLong(KEY_REVISION, revision)
        if (session != null) edit.putString(KEY_CLOSED_SESSIONS, closedList(session))
        edit.remove(KEY_PREPARED).remove(KEY_MANUAL_REVISIONS)
        edit.commit().also { if (it) observedRevision = revision }
    }

    fun rememberCompletionReceipt(sessionId: String, completionId: String): Boolean = synchronized(lock) {
        prefs.edit().putString(KEY_RECEIPTS, receiptList(sessionId, completionId)).commit()
    }

    fun hasCompletionReceipt(sessionId: String, completionId: String): Boolean = synchronized(lock) {
        prefs.getString(KEY_RECEIPTS, "").orEmpty().lineSequence().any { it == "$sessionId:$completionId" }
    }

    fun isSessionClosed(sessionId: String): Boolean = synchronized(lock) {
        prefs.getString(KEY_CLOSED_SESSIONS, "").orEmpty().lineSequence().any { it == sessionId }
    }

    private fun receiptList(sessionId: String, completionId: String) =
        (prefs.getString(KEY_RECEIPTS, "").orEmpty().lines().filter { it.isNotBlank() } + "$sessionId:$completionId").distinct().takeLast(256).joinToString("\n")

    private fun closedList(sessionId: String) =
        (prefs.getString(KEY_CLOSED_SESSIONS, "").orEmpty().lines().filter { it.isNotBlank() } + sessionId).distinct().takeLast(32).joinToString("\n")

    private fun read(): WorkoutSnapshot? {
        observedRevision = prefs.getLong(KEY_REVISION, 0)
        return prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let(WorkoutSnapshotCodec::decode)
    }

    private fun complete(current: WorkoutSnapshot, event: SetCompletionEvent, completionId: String? = null, pausedRestMs: Long? = null): SetCompletionResult {
        val reduced = WorkoutSessionReducer.completeSet(current, event)
        val result = if (reduced is SetCompletionResult.Applied && reduced.snapshot.phase == WorkoutPhase.RESTING && pausedRestMs != null) {
            reduced.copy(snapshot = reduced.snapshot.copy(paused = true, phase = WorkoutPhase.PAUSED, restEndAtMs = 0, pausedRestRemainingMs = pausedRestMs))
        } else reduced
        if (result is SetCompletionResult.Applied) check(commit(result.snapshot, completionId)) { "Failed to commit set completion snapshot" }
        return if (result is SetCompletionResult.Applied) result.copy(snapshot = requireNotNull(read())) else result
    }

    private fun commit(candidate: WorkoutSnapshot, completionId: String? = null, manualCommand: ManualSetCommand? = null,
        watchCommand: WatchWorkoutControl? = null, watchAck: WatchWorkoutControlAck? = null): Boolean {
        val old = prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let { runCatching { WorkoutSnapshotCodec.decode(it) }.getOrNull() }?.takeIf { it.sessionId == candidate.sessionId }
        val changed = old?.let { it.restEndAtMs != candidate.restEndAtMs || it.paused != candidate.paused ||
            it.pausedRestRemainingMs != candidate.pausedRestRemainingMs || it.watchRestSeq != candidate.watchRestSeq } ?: (candidate.restEndAtMs > 0 || candidate.paused)
        // A different next set while resting must also bump the revision, or the watch drops the resync as a duplicate.
        val nextSetChanged = old != null && (candidate.restEndAtMs > 0 || candidate.pausedRestRemainingMs > 0) &&
            WorkoutSessionReducer.nextPendingSet(old)?.let { Triple(it.exerciseId, it.setId, it.attemptId) } !=
            WorkoutSessionReducer.nextPendingSet(candidate)?.let { Triple(it.exerciseId, it.setId, it.attemptId) }
        val unilateralChanged = old != null && old.plannedSets.map { it.unilateral } != candidate.plannedSets.map { it.unilateral }
        val snapshot = candidate.copy(restRev = (old?.restRev ?: 0) + if (changed || nextSetChanged || unilateralChanged) 1 else 0,
            watchRestSeq = maxOf(old?.watchRestSeq ?: 0, candidate.watchRestSeq))
        val revision = prefs.getLong(KEY_REVISION, 0) + 1
        val edit = prefs.edit().putString(KEY_ACTIVE_SNAPSHOT, WorkoutSnapshotCodec.encode(snapshot)).putLong(KEY_REVISION, revision)
        if (completionId != null) edit.putString(KEY_RECEIPTS, receiptList(snapshot.sessionId, completionId))
        if (watchCommand != null) {
            edit.putString(KEY_WATCH_CONTROLS, controlReceipts(watchCommand, requireNotNull(watchAck)))
            if (watchCommand.action == WatchWorkoutAction.START_PREPARED) edit.remove(KEY_PREPARED)
        }
        val previous = prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let { runCatching { WorkoutSnapshotCodec.decode(it) }.getOrNull() }
        if (previous != null && previous.sessionId != snapshot.sessionId) edit.putString(KEY_CLOSED_SESSIONS, closedList(previous.sessionId))
        val revisions = JSONObject()
        snapshot.plannedSets.forEach { set ->
            val old = previous?.takeIf { it.sessionId == snapshot.sessionId }?.plannedSets?.singleOrNull {
                it.setId == set.setId && it.attemptId == set.attemptId && it.exerciseId == set.exerciseId && it.setIndex == set.setIndex
            }
            val before = old?.let(::manualRevision) ?: 0L
            val changed = old != null && old.unilateral != set.unilateral || set.repSource == SetRepSource.MANUAL && set.performedReps != null &&
                (old?.repSource != SetRepSource.MANUAL || old.performedReps != set.performedReps)
            revisions.put(revisionKey(set), before + if (changed) 1 else 0)
        }
        edit.putString(KEY_MANUAL_REVISIONS, revisions.toString())
        if (manualCommand != null) {
            val set = SetIdentity.resolve(snapshot, manualCommand.exerciseId, manualCommand.setIndex, manualCommand.setId, manualCommand.attemptId)!!
            val ack = ManualSetAck(manualCommand.commandId, snapshot.sessionId, set.setId, set.attemptId,
                ManualSetStatus.APPLIED, revisions.getLong(revisionKey(set)), set.performedReps)
            val oldReceipts = JSONArray(prefs.getString(KEY_MANUAL_RECEIPTS, "[]")!!)
            val receipts = JSONArray()
            for (index in maxOf(0, oldReceipts.length() - 255) until oldReceipts.length()) receipts.put(oldReceipts.getJSONObject(index))
            receipts.put(JSONObject().put("id", manualCommand.commandId).put("fingerprint", fingerprint(manualCommand))
                .put("ack", ack.encode(0, 0)))
            edit.putString(KEY_MANUAL_RECEIPTS, receipts.toString())
        }
        return edit.commit().also { if (it) { observedRevision = revision; runCatching { WorkoutTimeStore(context).record(snapshot, System.currentTimeMillis()) } } }
    }

    private fun revisionKey(set: PlannedSetSnapshot) = SetIdentity.key(set.exerciseId, set.setIndex, set.setId, set.attemptId)

    private fun manualRevision(set: PlannedSetSnapshot): Long = JSONObject(prefs.getString(KEY_MANUAL_REVISIONS, "{}")!!)
        .optLong(revisionKey(set), if (set.repSource == SetRepSource.MANUAL && set.performedReps != null) 1L else 0L)

    private fun fingerprint(command: ManualSetCommand): String = MessageDigest.getInstance("SHA-256")
        .digest(WireCodec.encode(command.toEnvelope(0, 0)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun controlFingerprint(command: WatchWorkoutControl) = SensorCaptureFormat.sha256(command.encode().toByteArray())
    private fun controlReceipts(command: WatchWorkoutControl, ack: WatchWorkoutControlAck): String {
        val old = JSONArray(prefs.getString(KEY_WATCH_CONTROLS, "[]")!!)
        val rows = JSONArray()
        for (index in maxOf(0, old.length() - 255) until old.length()) rows.put(old.getJSONObject(index))
        rows.put(JSONObject().put("id", command.commandId).put("fingerprint", controlFingerprint(command)).put("ack", ack.encode()))
        return rows.toString()
    }

    companion object {
        private val lock = WorkoutRuntimeLock.monitor
        private const val PREFS_NAME = "workout-session"
        private const val KEY_ACTIVE_SNAPSHOT = "activeSnapshot"
        private const val KEY_REVISION = "revision"
        private const val KEY_RECEIPTS = "completionReceipts"
        private const val KEY_CLOSED_SESSIONS = "closedSessions"
        private const val KEY_MANUAL_REVISIONS = "manualRevisions"
        private const val KEY_MANUAL_RECEIPTS = "manualReceipts"
        private const val KEY_PREPARED = "watchPreparedWorkout"
        private const val KEY_WATCH_CONTROLS = "watchControlReceipts"
    }
}
