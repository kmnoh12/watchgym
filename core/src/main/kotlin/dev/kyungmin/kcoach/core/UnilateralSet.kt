package dev.kyungmin.kcoach.core

/** First always means the watch-wearing arm, not necessarily the left arm. */
enum class UnilateralStage { WATCH_SIDE_WORKING, AWAITING_OTHER_SIDE, OTHER_SIDE_WORKING, WATCH_SIDE_ONLY, BOTH_COMPLETE }
enum class UnilateralAction { START_OTHER_SIDE, FINISH_WATCH_SIDE_ONLY, COMPLETE_OTHER_SIDE }

data class UnilateralProgress(
    val stage: UnilateralStage = UnilateralStage.WATCH_SIDE_WORKING,
    val watchReps: Int? = null,
    val watchRepSource: SetRepSource? = null,
    val otherReps: Int? = null,
) {
    init {
        require(watchReps == null || watchReps in 0..9_999)
        require(otherReps == null || otherReps in 0..9_999)
        require((watchReps == null) == (watchRepSource == null))
        require((stage == UnilateralStage.WATCH_SIDE_WORKING) == (watchReps == null))
        require((stage == UnilateralStage.BOTH_COMPLETE) == (otherReps != null))
    }
    val holdsRest: Boolean get() = stage in setOf(UnilateralStage.AWAITING_OTHER_SIDE, UnilateralStage.OTHER_SIDE_WORKING)
    fun encode(): String = listOf(stage.name, watchReps?.toString().orEmpty(), watchRepSource?.name.orEmpty(), otherReps?.toString().orEmpty()).joinToString(",")
    companion object {
        fun decode(value: String): UnilateralProgress? {
            if (value.isEmpty()) return null
            val fields = value.split(',')
            require(fields.size == 4)
            return UnilateralProgress(UnilateralStage.valueOf(fields[0]), fields[1].takeIf { it.isNotEmpty() }?.toInt(),
                fields[2].takeIf { it.isNotEmpty() }?.let(SetRepSource::valueOf), fields[3].takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}

/** Only explicit one-arm variants opt in. Bilateral rows/machines must not be inferred from wrist silence. */
object UnilateralExercises {
    val ids: Set<String> = setOf("one_arm_lat_pull_down", "one_arm_dumbbell_row", "one_arm_cable_lateral",
        "one_arm_cable_row", "single_arm_dumbbell_bench_press", "single_arm_dumbbell_shoulder_press",
        "single_arm_cable_pushdown", "single_arm_cable_fly", "concentration_curl",
        "single_arm_cable_external_rotation",
        "single_arm_cable_reverse_fly",
        "single_arm_machine_seated_row",
        "single_arm_high_row_machine",
        "single_arm_plate_loaded_row",
        "single_arm_machine_lat_pulldown", // Historical unspecified machine; never migrate it to pin/plate automatically.
        "single_arm_machine_pulldown_pin",
        "single_arm_machine_pulldown_plate",
        "single_arm_reverse_pec_deck",
        "single_arm_cable_overhead_extension",
        "single_arm_cable_curl",
        "single_arm_dumbbell_preacher_curl",
        "single_arm_bayesian_curl",
        "single_arm_cable_kickback",
        "single_arm_dumbbell_kickback",
        "single_arm_chest_supported_dumbbell_row",
        "single_arm_machine_lateral_raise",
        "single_arm_dumbbell_lateral_raise",
        "single_arm_cable_front_raise",
        "meadows_row", // Always one arm at a time (landmine bar sleeve), so the other side follows after the watch side.
    )
    fun initial(exerciseId: String, completed: Boolean = false): UnilateralProgress? =
        if (!completed && ExerciseMotionCatalog.normalizeExerciseId(exerciseId) in ids) UnilateralProgress() else null
}

object UnilateralSetReducer {
    /** Explicit user intent is mandatory. A repeated sensor completion can never finish the other arm. */
    fun apply(snapshot: WorkoutSnapshot, sessionId: String, setId: String, attemptId: String,
              action: UnilateralAction, nowMs: Long, otherReps: Int? = null): SetCompletionResult {
        fun reject(reason: String) = SetCompletionResult.Rejected(snapshot, reason)
        if (snapshot.sessionId != sessionId) return reject("unknown_session")
        if (nowMs < 0 || otherReps != null && otherReps !in 0..9_999) return reject("invalid_unilateral_fields")
        val set = snapshot.plannedSets.singleOrNull { it.setId == setId && it.attemptId == attemptId && setId.isNotBlank() }
            ?: return reject("unmatched_set_identity")
        val progress = set.unilateral ?: return reject("not_unilateral")
        if (set.completed) return SetCompletionResult.Duplicate(snapshot)
        if (snapshot.paused || snapshot.phase != WorkoutPhase.WORKING ||
            snapshot.exerciseIndex != set.exerciseIndex || snapshot.setIndex != set.setIndex) return reject("inactive_unilateral_set")
        if (action == UnilateralAction.START_OTHER_SIDE && progress.stage == UnilateralStage.OTHER_SIDE_WORKING)
            return SetCompletionResult.Duplicate(snapshot)
        val stage = when (action) {
            UnilateralAction.START_OTHER_SIDE -> UnilateralStage.OTHER_SIDE_WORKING
            UnilateralAction.FINISH_WATCH_SIDE_ONLY -> UnilateralStage.WATCH_SIDE_ONLY
            UnilateralAction.COMPLETE_OTHER_SIDE -> UnilateralStage.BOTH_COMPLETE
        }
        val expected = if (action == UnilateralAction.COMPLETE_OTHER_SIDE) UnilateralStage.OTHER_SIDE_WORKING else UnilateralStage.AWAITING_OTHER_SIDE
        if (progress.stage != expected || action != UnilateralAction.COMPLETE_OTHER_SIDE && otherReps != null)
            return reject("invalid_unilateral_transition")
        // A complete press confirms the suggested matching count; it is manual evidence, never a sensor count.
        val updated = progress.copy(stage = stage, otherReps = if (stage == UnilateralStage.BOTH_COMPLETE) otherReps ?: progress.watchReps else null)
        val candidate = snapshot.copy(plannedSets = snapshot.plannedSets.map { if (it === set) it.copy(unilateral = updated) else it })
        if (action == UnilateralAction.START_OTHER_SIDE) return SetCompletionResult.Applied(candidate)
        return WorkoutSessionReducer.completeSet(candidate, SetCompletionEvent(sessionId, set.exerciseId, set.setIndex,
            requireNotNull(progress.watchReps), Math.addExact(nowMs, Math.multiplyExact(set.restSeconds.toLong(), 1_000L)), 0,
            requireNotNull(progress.watchRepSource), setId, attemptId))
    }
}
