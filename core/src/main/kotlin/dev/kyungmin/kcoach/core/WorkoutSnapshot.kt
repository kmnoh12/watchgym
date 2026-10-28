package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

enum class WorkoutPhase {
    WORKING,
    RESTING,
    PAUSED,
}

enum class CaptureState {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    UPLOADING,
    RECEIVED,
    ANALYZING,
    ANALYSIS_FAILED,
    ACKED,
}

enum class SetRepSource { MANUAL, WATCH, PHONE_ANALYSIS }

data class PlannedSetSnapshot(
    val exerciseIndex: Int,
    val exerciseId: String,
    val setIndex: Int,
    val targetReps: Int,
    val weightKg: Double?,
    val completed: Boolean,
    val setType: String = "NORMAL",
    val exerciseName: String = "",
    val restSeconds: Int = 90,
    val exerciseRestSeconds: Int = 90,
    val restOverrideSeconds: Int? = null,
    val performedReps: Int? = null,
    val repSource: SetRepSource? = null,
    val exerciseMuscles: String = "",
    val bodyCategory: String = "",
    val equipment: String = "",
    val pattern: String = "",
    val family: String = "",
    val gripType: String = "노멀",
    val rpe: String? = null,
    val setId: String = "",
    val attemptId: String = "",
    val identityRequired: Boolean = false,
    val unilateral: UnilateralProgress? = UnilateralExercises.initial(exerciseId, completed),
)

data class WorkoutSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sessionId: String,
    val routineId: String,
    val exerciseIndex: Int,
    val setIndex: Int,
    val plannedSets: List<PlannedSetSnapshot>,
    val performedReps: Map<String, Int>,
    val phase: WorkoutPhase,
    val restEndAtMs: Long,
    val pausedRestRemainingMs: Long,
    val paused: Boolean,
    val startedAtMs: Long,
    val pausedSinceMs: Long,
    val totalPausedMs: Long,
    val lastSequence: Long,
    val lastCompletedSetKey: String?,
    val captureStates: Map<String, CaptureState>,
    val routineName: String = "",
    val restRev: Long = 0,
    val watchRestSeq: Long = 0,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported workout snapshot schema" }
        require(restRev >= 0 && watchRestSeq >= 0)
        require(sessionId.isNotBlank()) { "Missing workout session id" }
        require(routineId.isNotBlank()) { "Missing routine id" }
        require(exerciseIndex >= 0 && setIndex >= 0) { "Negative workout index" }
        require(restEndAtMs >= 0L && pausedRestRemainingMs >= 0L && startedAtMs >= 0L) {
            "Negative workout timestamp"
        }
        require(pausedSinceMs >= 0L && totalPausedMs >= 0L && lastSequence >= 0L) {
            "Negative workout state value"
        }
        require(plannedSets.size <= MAX_COLLECTION_SIZE) { "Too many planned sets" }
        require(performedReps.size <= MAX_COLLECTION_SIZE) { "Too many rep entries" }
        require(captureStates.size <= MAX_COLLECTION_SIZE) { "Too many capture states" }
        val identifiedSets = plannedSets.filter { it.setId.isNotBlank() }
        require(identifiedSets.map { it.setId }.distinct().size == identifiedSets.size) { "Duplicate set id" }
        plannedSets.forEach {
            require(it.exerciseIndex >= 0 && it.exerciseId.isNotBlank() && it.setIndex >= 0 && it.targetReps >= 0) {
                "Invalid planned set"
            }
            require((it.setId.isBlank() && it.attemptId.isBlank() && !it.identityRequired) ||
                (it.setId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && it.attemptId.matches(Regex("[A-Za-z0-9._-]{1,160}")))) { "Invalid set identity" }
            require(it.performedReps == null || it.performedReps >= 0) { "Negative set reps" }
            require(it.restSeconds >= 0) { "Negative set rest" }
            require(it.exerciseRestSeconds >= 0 && (it.restOverrideSeconds == null || it.restOverrideSeconds >= 0)) { "Negative rest setting" }
            require(it.weightKg == null || (it.weightKg.isFinite() && it.weightKg >= 0.0)) {
                "Invalid planned weight"
            }
        }
        require(performedReps.values.all { it >= 0 }) { "Negative performed reps" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 8
        internal const val MAX_COLLECTION_SIZE = 10_000
    }
}

object WorkoutSnapshotCodec {
    private const val MAGIC = 0x4B435331 // KCS1
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(snapshot: WorkoutSnapshot): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(snapshot.schemaVersion)
            data.writeString(snapshot.sessionId)
            data.writeString(snapshot.routineId)
            data.writeInt(snapshot.exerciseIndex)
            data.writeInt(snapshot.setIndex)
            data.writeInt(snapshot.plannedSets.size)
            snapshot.plannedSets.forEach { set ->
                data.writeInt(set.exerciseIndex)
                data.writeString(set.exerciseId)
                data.writeInt(set.setIndex)
                data.writeInt(set.targetReps)
                data.writeBoolean(set.weightKg != null)
                set.weightKg?.let(data::writeDouble)
                data.writeBoolean(set.completed)
                data.writeString(set.setType)
                data.writeString(set.exerciseName)
                data.writeInt(set.restSeconds)
                data.writeInt(set.exerciseRestSeconds)
                data.writeBoolean(set.restOverrideSeconds != null)
                set.restOverrideSeconds?.let(data::writeInt)
                data.writeBoolean(set.performedReps != null)
                set.performedReps?.let(data::writeInt)
                data.writeString(set.repSource?.name.orEmpty())
                data.writeString(set.exerciseMuscles); data.writeString(set.bodyCategory); data.writeString(set.equipment)
                data.writeString(set.pattern); data.writeString(set.family); data.writeString(set.gripType)
                data.writeBoolean(set.rpe != null); set.rpe?.let { data.writeString(it) }
                data.writeString(set.setId); data.writeString(set.attemptId); data.writeBoolean(set.identityRequired)
                data.writeString(set.unilateral?.encode().orEmpty())
            }
            data.writeInt(snapshot.performedReps.size)
            snapshot.performedReps.toSortedMap().forEach { (exerciseId, reps) ->
                data.writeString(exerciseId)
                data.writeInt(reps)
            }
            data.writeInt(snapshot.phase.ordinal)
            data.writeLong(snapshot.restEndAtMs)
            data.writeLong(snapshot.pausedRestRemainingMs)
            data.writeBoolean(snapshot.paused)
            data.writeLong(snapshot.startedAtMs)
            data.writeLong(snapshot.pausedSinceMs)
            data.writeLong(snapshot.totalPausedMs)
            data.writeLong(snapshot.lastSequence)
            data.writeBoolean(snapshot.lastCompletedSetKey != null)
            snapshot.lastCompletedSetKey?.let { data.writeString(it) }
            data.writeInt(snapshot.captureStates.size)
            snapshot.captureStates.toSortedMap().forEach { (captureId, state) ->
                data.writeString(captureId)
                data.writeInt(state.ordinal)
            }
            data.writeString(snapshot.routineName)
            data.writeLong(snapshot.restRev)
            data.writeLong(snapshot.watchRestSeq)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    fun decode(encoded: String): WorkoutSnapshot {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid workout snapshot" }
            val schemaVersion = data.readInt()
            require(schemaVersion in 1..WorkoutSnapshot.CURRENT_SCHEMA_VERSION) {
                "Unsupported workout snapshot schema"
            }
            val sessionId = data.readString()
            val routineId = data.readString()
            val exerciseIndex = data.readInt()
            val setIndex = data.readInt()
            val plannedSets = List(data.readCount("planned sets")) {
                PlannedSetSnapshot(
                    exerciseIndex = data.readInt(),
                    exerciseId = data.readString(),
                    setIndex = data.readInt(),
                    targetReps = data.readInt(),
                    weightKg = if (data.readBoolean()) data.readDouble() else null,
                    completed = data.readBoolean(),
                    setType = if (schemaVersion >= 2) data.readString() else "NORMAL",
                    exerciseName = if (schemaVersion >= 3) data.readString() else "",
                    restSeconds = if (schemaVersion >= 3) data.readInt() else 90,
                    exerciseRestSeconds = if (schemaVersion >= 3) data.readInt() else 90,
                    restOverrideSeconds = if (schemaVersion >= 3 && data.readBoolean()) data.readInt() else null,
                    performedReps = if (schemaVersion >= 4 && data.readBoolean()) data.readInt() else null,
                    repSource = if (schemaVersion >= 4) data.readString().let { name ->
                        if (name.isBlank()) null else SetRepSource.valueOf(name)
                    } else null,
                    exerciseMuscles = if (schemaVersion >= 5) data.readString() else "",
                    bodyCategory = if (schemaVersion >= 5) data.readString() else "",
                    equipment = if (schemaVersion >= 5) data.readString() else "",
                    pattern = if (schemaVersion >= 5) data.readString() else "",
                    family = if (schemaVersion >= 5) data.readString() else "",
                    gripType = if (schemaVersion >= 5) data.readString() else "노멀",
                    rpe = if (schemaVersion >= 5 && data.readBoolean()) data.readString() else null,
                    setId = if (schemaVersion >= 6) data.readString() else "",
                    attemptId = if (schemaVersion >= 6) data.readString() else "",
                    identityRequired = schemaVersion >= 6 && data.readBoolean(),
                ).let { set -> if (schemaVersion >= 8) set.copy(unilateral = UnilateralProgress.decode(data.readString())) else set }
            }
            val performedReps = buildMap {
                repeat(data.readCount("performed reps")) {
                    put(data.readString(), data.readInt())
                }
            }
            val phase = WorkoutPhase.entries.getOrNull(data.readInt())
                ?: error("Invalid workout phase")
            val restEndAtMs = data.readLong()
            val pausedRestRemainingMs = data.readLong()
            val paused = data.readBoolean()
            val startedAtMs = data.readLong()
            val pausedSinceMs = data.readLong()
            val totalPausedMs = data.readLong()
            val lastSequence = data.readLong()
            val lastCompletedSetKey = if (data.readBoolean()) data.readString() else null
            val captureStates = buildMap {
                repeat(data.readCount("capture states")) {
                    val captureId = data.readString()
                    val state = CaptureState.entries.getOrNull(data.readInt())
                        ?: error("Invalid capture state")
                    put(captureId, state)
                }
            }
            val routineName = if (schemaVersion >= 5) data.readString() else ""
            val restRev = if (schemaVersion >= 7) data.readLong() else 0L
            val watchRestSeq = if (schemaVersion >= 7) data.readLong() else 0L
            require(data.read() == -1) { "Trailing workout snapshot data" }
            WorkoutSnapshot(
                schemaVersion = WorkoutSnapshot.CURRENT_SCHEMA_VERSION,
                sessionId = sessionId,
                routineId = routineId,
                exerciseIndex = exerciseIndex,
                setIndex = setIndex,
                plannedSets = plannedSets,
                performedReps = performedReps,
                phase = phase,
                restEndAtMs = restEndAtMs,
                pausedRestRemainingMs = pausedRestRemainingMs,
                paused = paused,
                startedAtMs = startedAtMs,
                pausedSinceMs = pausedSinceMs,
                totalPausedMs = totalPausedMs,
                lastSequence = lastSequence,
                lastCompletedSetKey = lastCompletedSetKey,
                captureStates = captureStates,
                routineName = routineName,
                restRev = restRev, watchRestSeq = watchRestSeq,
            )
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Workout snapshot string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid workout snapshot string size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readCount(label: String): Int {
        val count = readInt()
        require(count in 0..WorkoutSnapshot.MAX_COLLECTION_SIZE) { "Invalid $label count" }
        return count
    }
}
