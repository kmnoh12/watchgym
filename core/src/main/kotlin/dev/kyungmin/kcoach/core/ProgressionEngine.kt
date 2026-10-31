package dev.kyungmin.kcoach.core

sealed interface ProgressionDecision {
    data class AddReps(val targetTotalRepIncrease: Int, val reason: String) : ProgressionDecision
    data class IncreaseWeight(val incrementKg: Double, val reason: String) : ProgressionDecision
    data class Hold(val reason: String) : ProgressionDecision
    data class ReduceLoad(val percent: Int, val reason: String) : ProgressionDecision
}

class ProgressionEngine {
    /** Pure: only confirmed historical sets drive a next plan. Never mutates its input or lowers a load. */
    fun nextTargets(
        plan: ProgressionPlan, history: List<ExerciseLog>,
        settings: ProgressionSettings = ProgressionSettings(), increment: IncrementCandidate = ProgressionDefaults.increment(plan.key.equipment),
    ): List<ProgressionSetTarget>? {
        if (!settings.enabled || plan.load == ProgressionLoad.TIME) return null
        val defaultUpper = ProgressionDefaults.upper(plan.pattern) ?: return null
        val sessions = history.filter {
            it.routineId == plan.key.routineId && it.exerciseId == plan.key.exerciseId &&
                it.exerciseIndex == plan.key.exerciseIndex && it.equipment == plan.key.equipment
        }.sortedWith(compareByDescending<ExerciseLog> { it.epochDay }.thenByDescending { it.startedAtMs })
        fun matches(log: ExerciseLog): Boolean = log.sets.size == plan.sets.size && log.sets.zip(plan.sets).all { (a,b) ->
            a.positionKey.isNotEmpty() && a.positionKey == b.positionKey && a.isWarmup == b.isWarmup
        }
        // A changed layout breaks the chain, even if an older layout happens to match again.
        val comparable = sessions.takeWhile(::matches)
        val latest = comparable.firstOrNull() ?: return null
        val targets = plan.sets.mapIndexed { i, set ->
            val previous = latest.sets[i]
            set.copy(weightKg = previous.weightKg, targetReps = previous.targetReps ?: set.targetReps,
                upperReps = set.upperReps ?: previous.upperReps ?: maxOf(defaultUpper, set.targetReps))
        }.toMutableList()
        val groups = targets.indices.filter { !targets[it].isWarmup }.groupBy { targets[it].weightKg }
            .entries.sortedByDescending { if (plan.load == ProgressionLoad.ASSISTANCE) -(it.key ?: 0.0) else it.key ?: 0.0 }
        for ((weight, indices) in groups) {
            val current = indices.map { latest.sets[it] }
            // Missing and unfinished sets are neither success nor a zero-rep failure.
            if (current.any { !it.completed || !it.repsEntered || it.targetReps == null }) continue
            if (current.any { it.reps < it.targetReps!! }) continue
            if (current.any { !it.progressionEligible }) return targets
            val below = indices.firstOrNull { targets[it].targetReps < targets[it].upperReps!! }
            if (below != null) {
                targets[below] = targets[below].copy(targetReps = targets[below].targetReps + 1)
                return targets
            }
            var streak = 0
            for (log in comparable) {
                val historicalIndices = if (weight == groups.first().key) {
                    val main = log.sets.indices.filter { !log.sets[it].isWarmup }.groupBy { log.sets[it].weightKg }
                        .entries.maxByOrNull { if (plan.load == ProgressionLoad.ASSISTANCE) -(it.key ?: 0.0) else it.key ?: 0.0 }
                    if (main == null || main.key != weight) break
                    main.value
                } else {
                    val sameWeight = log.sets.indices.filter { !log.sets[it].isWarmup && log.sets[it].weightKg == weight }
                    if (sameWeight != indices) break
                    sameWeight
                }
                val rows = historicalIndices.map { log.sets[it] }
                if (rows.any { !it.completed || !it.repsEntered || it.targetReps == null }) continue
                if (rows.any { !it.progressionEligible }) continue
                if (historicalIndices.any { log.sets[it].reps < targets[it].upperReps!! || log.sets[it].reps < log.sets[it].targetReps!! }) break
                streak++
            }
            // Waiting at the heaviest successful group blocks progression of lighter groups.
            if (streak < settings.speed.confirmations) return targets
            if (plan.load == ProgressionLoad.BODYWEIGHT || increment.needsConfirmation || increment.kg == null || weight == null) return targets
            val nextWeight = ProgressionDefaults.nextWeight(weight, increment, plan.load == ProgressionLoad.ASSISTANCE)
            if (nextWeight == weight) return targets
            indices.forEach { i -> targets[i] = targets[i].copy(weightKg = nextWeight,
                targetReps = targets[i].lowerReps ?: ProgressionDefaults.lower(plan.pattern, targets[i].upperReps!!)) }
            return targets
        }
        return targets
    }

    @Deprecated("Use nextTargets with routine-scoped confirmed history")
    fun decide(
        exercise: ExercisePlan,
        workingSets: List<CompletedSet>,
        minimumIncrementKg: Double,
    ): ProgressionDecision {
        require(workingSets.isNotEmpty())
        val reps = workingSets.map { it.reps }
        val averageRir = workingSets.mapNotNull { it.rir }.takeIf { it.isNotEmpty() }?.average()

        val allAtTop = reps.all { it >= exercise.repRange.last }
        if (allAtTop && (averageRir == null || averageRir >= 1.0)) {
            return ProgressionDecision.IncreaseWeight(
                incrementKg = minimumIncrementKg,
                reason = "모든 작업 세트를 목표 횟수까지 채웠어요",
            )
        }

        if (reps.any { it < exercise.repRange.first }) {
            return ProgressionDecision.Hold("무게를 유지하고 모든 작업 세트를 최소 횟수 이상으로 맞춰 보세요")
        }

        return ProgressionDecision.AddReps(
            targetTotalRepIncrease = 1,
            reason = "지금 무게에서 전체 반복을 1회만 늘려 보세요",
        )
    }
}
