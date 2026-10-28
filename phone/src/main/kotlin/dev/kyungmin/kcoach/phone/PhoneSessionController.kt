package dev.kyungmin.kcoach.phone

import android.util.Log
import dev.kyungmin.kcoach.core.*

/** Plan changes use immutable candidates; a rejected save cannot leak a partial UI edit. */
sealed interface PhonePlanEdit {
    data class AddSet(val exerciseIndex: Int) : PhonePlanEdit
    data class DuplicateSet(val exerciseIndex: Int, val setIndex: Int) : PhonePlanEdit
    data class RemoveSet(val exerciseIndex: Int, val setIndex: Int) : PhonePlanEdit
    data class RestoreSet(val set: PlannedSetSnapshot) : PhonePlanEdit
    data class SetRest(val exerciseIndex: Int, val setIndex: Int, val seconds: Int?) : PhonePlanEdit
    data class ExerciseRest(val exerciseIndex: Int, val seconds: Int) : PhonePlanEdit
}

data class PhonePlanEditResult(val snapshot: WorkoutSnapshot, val syncFailure: Throwable?)

/** Session transitions depend on persisted state, never on whether a screen is open. */
class PhoneSessionController(private val store: SessionStore, private val send: (String, String) -> Unit) {
    /** Rebase explicit edits by identity; an older screen must never replace a newer result. */
    fun editPlan(buildSnapshot: () -> WorkoutSnapshot, edit: PhonePlanEdit): Result<PhonePlanEditResult> = runCatching {
        val before = buildSnapshot()
        val latest = requireNotNull(store.loadActive().getOrThrow()) { "Session no longer active" }
        require(latest.sessionId == before.sessionId) { "Session changed" }
        val candidate = reducePlan(latest, rebaseEdit(before, latest, edit))
        val saved = store.savePlan(candidate).getOrThrow()
        // Persistence has succeeded. A transport error must not roll the UI back to an unsaved plan.
        val syncFailure = runCatching { syncSnapshot(saved) }.exceptionOrNull()
        if (syncFailure != null) Log.e("KCoachEvent", "saved plan watch sync failed", syncFailure)
        PhonePlanEditResult(saved, syncFailure)
    }.onFailure { Log.e("KCoachEvent", "plan edit rejected; previous state retained", it) }

    private fun rebaseEdit(before: WorkoutSnapshot, latest: WorkoutSnapshot, edit: PhonePlanEdit): PhonePlanEdit {
        if (before == latest) return edit
        fun currentSet(exerciseIndex: Int, setIndex: Int): PlannedSetSnapshot {
            val original = before.plannedSets.single { it.exerciseIndex == exerciseIndex && it.setIndex == setIndex }
            require(original.setId.isNotBlank()) { "Edit needs a stable set identity" }
            return latest.plannedSets.single { it.setId == original.setId && it.exerciseId == original.exerciseId }
        }
        fun currentExercise(index: Int): Int {
            val originals = before.plannedSets.filter { it.exerciseIndex == index }
            require(originals.isNotEmpty() && originals.all { it.setId.isNotBlank() })
            val survivors = latest.plannedSets.filter { saved -> originals.any { it.setId == saved.setId && it.exerciseId == saved.exerciseId } }
            return survivors.map { it.exerciseIndex }.distinct().single()
        }
        return when (edit) {
            is PhonePlanEdit.AddSet -> edit.copy(exerciseIndex = currentExercise(edit.exerciseIndex))
            is PhonePlanEdit.ExerciseRest -> edit.copy(exerciseIndex = currentExercise(edit.exerciseIndex))
            is PhonePlanEdit.DuplicateSet -> currentSet(edit.exerciseIndex, edit.setIndex).let { edit.copy(exerciseIndex = it.exerciseIndex, setIndex = it.setIndex) }
            is PhonePlanEdit.RemoveSet -> currentSet(edit.exerciseIndex, edit.setIndex).let { edit.copy(exerciseIndex = it.exerciseIndex, setIndex = it.setIndex) }
            is PhonePlanEdit.SetRest -> currentSet(edit.exerciseIndex, edit.setIndex).let { edit.copy(exerciseIndex = it.exerciseIndex, setIndex = it.setIndex) }
            is PhonePlanEdit.RestoreSet -> {
                val index = currentExercise(edit.set.exerciseIndex)
                // Undo has a positional insertion point: do not guess after another layout edit.
                require(before.plannedSets.filter { it.exerciseIndex == edit.set.exerciseIndex }.map { it.setId } ==
                    latest.plannedSets.filter { it.exerciseIndex == index }.map { it.setId }) { "Undo layout changed" }
                edit.copy(set = edit.set.copy(exerciseIndex = index))
            }
        }
    }

    private fun reducePlan(before: WorkoutSnapshot, edit: PhonePlanEdit): WorkoutSnapshot {
        val sets = before.plannedSets.toMutableList()
        fun exercise(index: Int) = sets.filter { it.exerciseIndex == index }.also { require(it.isNotEmpty()) }
        var cursor = before.setIndex
        when (edit) {
            is PhonePlanEdit.AddSet -> {
                val source = exercise(edit.exerciseIndex).last()
                val added = source.copy(setIndex = source.setIndex + 1, completed = false,
                    performedReps = null, repSource = null, unilateral = UnilateralExercises.initial(source.exerciseId), setType = "NORMAL", rpe = null,
                    setId = java.util.UUID.randomUUID().toString(), attemptId = java.util.UUID.randomUUID().toString(), identityRequired = true)
                sets.add(sets.indexOf(source) + 1, added)
            }
            is PhonePlanEdit.DuplicateSet -> {
                val source = exercise(edit.exerciseIndex).single { it.setIndex == edit.setIndex }
                val insertion = source.setIndex + 1
                val copy = source.copy(setIndex = insertion, completed = false, performedReps = null,
                    repSource = null, rpe = null, unilateral = UnilateralExercises.initial(source.exerciseId),
                    setId = java.util.UUID.randomUUID().toString(), attemptId = java.util.UUID.randomUUID().toString(), identityRequired = true)
                sets.add(sets.indexOf(source) + 1, copy)
                // Rest may schedule the copy next; a measuring/paused working set keeps its identity.
                val resting = before.restEndAtMs > 0L || before.pausedRestRemainingMs > 0L
                if (before.exerciseIndex == edit.exerciseIndex &&
                    (cursor > insertion || cursor == insertion && !resting)) cursor++
            }
            is PhonePlanEdit.RemoveSet -> {
                val group = exercise(edit.exerciseIndex)
                require(group.size > 1) { "Cannot remove the last set" }
                val removed = group.single { it.setIndex == edit.setIndex }
                sets.remove(removed)
                if (before.exerciseIndex == edit.exerciseIndex) cursor = when {
                    cursor > edit.setIndex -> cursor - 1
                    cursor == edit.setIndex -> cursor.coerceAtMost(group.size - 2)
                    else -> cursor
                }
            }
            is PhonePlanEdit.RestoreSet -> {
                val group = exercise(edit.set.exerciseIndex)
                require(group.all { it.exerciseId == edit.set.exerciseId }) { "Exercise changed since removal" }
                require(sets.none { it.setId == edit.set.setId }) { "Set already restored" }
                val index = edit.set.setIndex.coerceIn(0, group.size)
                val insertAt = if (index == group.size) sets.indexOf(group.last()) + 1 else sets.indexOf(group[index])
                sets.add(insertAt, edit.set)
                if (before.exerciseIndex == edit.set.exerciseIndex && cursor >= index) cursor++
            }
            is PhonePlanEdit.SetRest -> {
                require(edit.seconds == null || edit.seconds >= 0)
                val target = exercise(edit.exerciseIndex).single { it.setIndex == edit.setIndex }
                sets[sets.indexOf(target)] = target.copy(restOverrideSeconds = edit.seconds,
                    restSeconds = edit.seconds ?: target.exerciseRestSeconds)
            }
            is PhonePlanEdit.ExerciseRest -> {
                require(edit.seconds >= 0)
                exercise(edit.exerciseIndex).forEach { target ->
                    sets[sets.indexOf(target)] = target.copy(exerciseRestSeconds = edit.seconds,
                        restSeconds = target.restOverrideSeconds ?: edit.seconds)
                }
            }
        }
        val layoutChanged = edit is PhonePlanEdit.AddSet || edit is PhonePlanEdit.DuplicateSet || edit is PhonePlanEdit.RemoveSet || edit is PhonePlanEdit.RestoreSet
        val indices = mutableMapOf<Int, Int>()
        val normalized = sets.map { set ->
            val index = indices.getOrDefault(set.exerciseIndex, 0)
            indices[set.exerciseIndex] = index + 1
            set.copy(setIndex = index, identityRequired = set.identityRequired || layoutChanged)
        }
        return before.copy(plannedSets = normalized, setIndex = cursor,
            performedReps = normalized.groupBy { it.exerciseId }.mapValues { (_, group) ->
                group.filter { it.completed }.sumOf { it.performedReps ?: 0 }
            })
    }

    fun receiveManual(payload: String, nowMs: Long): Result<ManualSetApplication> = runCatching {
        val command = ManualSetCommand.fromEnvelope(WireCodec.decode(payload))
        val applied = store.applyManualSet(command, nowMs).getOrThrow()
        send(WearSync.PATH_MANUAL_ACK, applied.ack.encode(android.os.SystemClock.elapsedRealtime(), nowMs))
        if (applied.ack.status == ManualSetStatus.APPLIED && !applied.restStarted &&
            applied.snapshot?.plannedSets?.any { it.unilateral?.holdsRest == true } == true) resync().getOrThrow()
        if (applied.restStarted) {
            val snapshot = requireNotNull(applied.snapshot)
            val set = SetIdentity.resolve(snapshot, command.exerciseId, command.setIndex, command.setId, command.attemptId)!!
            if (snapshot.paused) resync().getOrThrow()
            else send(WearSync.PATH_COMMAND, command(snapshot, "REST", set.restSeconds))
        }
        applied
    }

    fun receiveCompletion(payload: String, nowMs: Long): Result<SetCompletionResult?> = runCatching {
        val fields = WearSync.parse(payload)
        require(fields.size >= 12 && fields[0] == "2" && fields[9] == "SET_COMPLETE") { "Invalid set completion payload" }
        val completionId = fields.getOrNull(16)?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,80}")) }
        val event = SetCompletionEvent(fields[1], fields[2], fields[4].toInt(), fields[5].toInt(), 0,
            if (completionId == null) fields[11].toLong() else 0L, setId = fields.getOrNull(17).orEmpty(), attemptId = fields.getOrNull(18).orEmpty())
        if (completionId != null && (store.hasCompletionReceipt(event.sessionId, completionId) || store.isSessionClosed(event.sessionId))) {
            send(WearSync.PATH_SET_ACK, WearSync.setAck(event.sessionId, event.exerciseId, event.setIndex, completionId, event.setId, event.attemptId))
            return@runCatching null
        }
        Log.i("KCoachEvent", KCoachEventTrace.format(KCoachEventMarker.SET_COMPLETE_RECEIVED, event.sessionId, event.exerciseId, event.setIndex))
        val result = store.completeWatchSet(event, nowMs, completionId).getOrThrow()
        if (result is SetCompletionResult.Applied) {
            Log.i("KCoachEvent", KCoachEventTrace.format(KCoachEventMarker.SNAPSHOT_SAVED, event.sessionId, event.exerciseId, event.setIndex))
            val completed = SetIdentity.resolve(result.snapshot, event.exerciseId, event.setIndex, event.setId, event.attemptId)!!
            if (result.snapshot.paused || result.snapshot.phase != WorkoutPhase.RESTING) resync().getOrThrow()
            else send(WearSync.PATH_COMMAND, command(result.snapshot, "REST", completed.restSeconds))
            Log.i("KCoachEvent", KCoachEventTrace.format(KCoachEventMarker.REST_COMMAND_SENT, event.sessionId, event.exerciseId, event.setIndex,
                detail = if (result.snapshot.paused) "command=SYNC_PAUSED" else if (result.snapshot.phase != WorkoutPhase.RESTING) "command=SYNC_UNILATERAL" else "command=REST"))
        } else if (result is SetCompletionResult.Rejected) {
            Log.w("KCoachEvent", KCoachEventTrace.format(KCoachEventMarker.EVENT_FAILED, event.sessionId, event.exerciseId, event.setIndex, detail = "set_rejected:${result.reason}"))
        }
        if (completionId != null && !(result is SetCompletionResult.Rejected && result.reason == "unknown_session")) {
            if (result is SetCompletionResult.Rejected) check(store.rememberCompletionReceipt(event.sessionId, completionId))

            send(WearSync.PATH_SET_ACK, WearSync.setAck(event.sessionId, event.exerciseId, event.setIndex, completionId, event.setId, event.attemptId))
            if (result is SetCompletionResult.Rejected) resync().getOrThrow()
        }
        result
    }

    /** UI calls this with the visible set identity. No views, timers or automatic side transitions here. */
    fun unilateralAction(sessionId: String, setId: String, attemptId: String, action: UnilateralAction,
                         nowMs: Long, otherReps: Int? = null, commandId: String = java.util.UUID.randomUUID().toString()): Result<WatchWorkoutApplication> = runCatching {
        val command = WatchWorkoutControl(commandId, WatchWorkoutAction.valueOf(action.name), sessionId, setId, attemptId,
            reps = otherReps ?: -1)
        val applied = store.applyWatchControl(command, nowMs).getOrThrow()
        if (applied.snapshot != null) syncSnapshot(applied.snapshot)
        applied
    }

    fun resync(): Result<Unit> = runCatching {
        val snapshot = store.loadActive().getOrThrow() ?: return@runCatching
        syncSnapshot(snapshot)
    }

    private fun syncSnapshot(snapshot: WorkoutSnapshot) {
        val target = WorkoutSessionReducer.nextPendingSet(snapshot)
        if (target == null && snapshot.phase == WorkoutPhase.WORKING) {
            send(WearSync.PATH_COMMAND, WearSync.command("COMPLETE", snapshot.sessionId, "", 0, 0))
            return
        }
        val restSeconds = snapshot.plannedSets.firstOrNull {
            SetIdentity.completionKey(snapshot.sessionId, it) == snapshot.lastCompletedSetKey
        }?.restSeconds ?: target?.restSeconds ?: 90
        val fields = WearSync.parse(command(snapshot, "SYNC",
            if (snapshot.phase == WorkoutPhase.RESTING || snapshot.pausedRestRemainingMs > 0) restSeconds else target?.restSeconds ?: 90)).toMutableList()
        while (fields.size < 13) fields.add("")
        fields[10] = "0"
        fields[11] = snapshot.phase.name
        fields[12] = snapshot.pausedRestRemainingMs.toString()
        send(WearSync.PATH_COMMAND, fields.joinToString("|"))
    }

    fun advanceRest(nowMs: Long, skip: Boolean = false): Result<WorkoutSnapshot?> = runCatching {
        val next = store.advanceAfterRest(nowMs, skip).getOrThrow() ?: return@runCatching null
        send(WearSync.PATH_COMMAND, command(next, "SET"))
        next
    }

    companion object {
        fun command(snapshot: WorkoutSnapshot, type: String, restSeconds: Int? = null): String {
            val target = WorkoutSessionReducer.nextPendingSet(snapshot)
            if (target == null && type == "SET") return WearSync.command("COMPLETE", snapshot.sessionId, "", 0, 0)
            return WearSync.withUnilateral(WearSync.withRestVersion(WearSync.richCommand(type, snapshot.sessionId, target?.exerciseId.orEmpty(),
                target?.exerciseName?.ifBlank { target.exerciseId } ?: "운동 완료", target?.setIndex ?: 0,
                target?.targetReps ?: 1, target?.weightKg, restSeconds ?: target?.restSeconds ?: 0,
                snapshot.restEndAtMs, target?.setId.orEmpty(), target?.attemptId.orEmpty()), snapshot.restRev, snapshot.watchRestSeq), target?.unilateral)
        }
    }
}
