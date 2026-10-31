package dev.kyungmin.kcoach.core

enum class ProgressionSpeed(val confirmations: Int) { SLOW(3), NORMAL(2), FAST(1) }
data class ProgressionSettings(val enabled: Boolean = true, val speed: ProgressionSpeed = ProgressionSpeed.NORMAL)
enum class ProgressionLoad { EXTERNAL, BODYWEIGHT, WEIGHTED_BODYWEIGHT, ASSISTANCE, TIME }
data class ProgressionKey(val routineId: String, val exerciseId: String, val exerciseIndex: Int, val equipment: String)
data class ProgressionSetTarget(
    val positionKey: String, val weightKg: Double?, val targetReps: Int,
    val isWarmup: Boolean = false, val upperReps: Int? = null, val lowerReps: Int? = null,
)
data class ProgressionPlan(
    val key: ProgressionKey, val sets: List<ProgressionSetTarget>, val pattern: String,
    val load: ProgressionLoad = ProgressionLoad.EXTERNAL,
)
data class IncrementCandidate(val kg: Double?, val needsConfirmation: Boolean, val observedSteps: List<Double> = emptyList(), val confirmed: Boolean = false) {
    init {
        require(kg == null || (kg.isFinite() && kg > 0))
        require(observedSteps.isEmpty() || (observedSteps.size >= 2 &&
            observedSteps.all { it.isFinite() && it >= 0 } && observedSteps.zipWithNext().all { (a, b) -> b > a }))
    }
}

object ProgressionDefaults {
    // Uses the movement-pattern vocabulary shared with ExerciseTaxonomy. Unknown patterns hold.
    private val compound = setOf("vertical_pull", "row", "chest_press", "shoulder_press", "leg_press", "squat", "deadlift", "lunge")
    private val isolation = setOf("chest_fly", "lateral_raise", "rear_delt", "curl", "triceps_pushdown", "triceps_extension", "leg_curl", "leg_extension", "hip", "pullover", "rotation")
    fun upper(pattern: String): Int? = when (pattern) { in compound -> 10; in isolation -> 12; else -> null }
    fun lower(pattern: String, upper: Int): Int = (upper - if (pattern in compound) 4 else 6).coerceAtLeast(1)
    fun increment(equipment: String, observedWeights: List<Double> = emptyList(), confirmedKg: Double? = null): IncrementCandidate {
        if (confirmedKg != null) {
            require(confirmedKg.isFinite() && confirmedKg > 0)
            return IncrementCandidate(confirmedKg, false, confirmed = true)
        }
        return when (equipment) {
            "바벨" -> IncrementCandidate(2.0, false)
            "덤벨" -> IncrementCandidate(2.0, false)
            "머신", "케이블", "핀 머신" -> {
                val steps = observedWeights.filter { it.isFinite() && it >= 0 }.distinct().sorted()
                val gaps = steps.zipWithNext { a, b -> b - a }
                val irregular = gaps.size > 1 && gaps.any { kotlin.math.abs(it - gaps.first()) > 0.001 }
                IncrementCandidate(gaps.minOrNull() ?: 5.0, false, if (irregular) steps else emptyList())
            }
            else -> IncrementCandidate(null, true)
        }
    }

    /** Only generated weights are snapped; actual records and explicit edits keep their precision. */
    fun roundWeight(weightKg: Double, equipment: String): Double {
        val step = if (equipment in setOf("머신", "케이블", "핀 머신")) 5.0 else if (equipment in setOf("바벨", "덤벨")) 2.0 else 1.0
        return (kotlin.math.floor(weightKg / step + 0.5) * step).coerceAtLeast(step)
    }

    fun nextWeight(weightKg: Double, increment: IncrementCandidate, assistance: Boolean): Double {
        val steps = increment.observedSteps
        // Do not invent hardware stages beyond the recorded range.
        if (steps.isNotEmpty()) {
            if (assistance) return steps.lastOrNull { it < weightKg }
                ?: weightKg
            return steps.firstOrNull { it > weightKg } ?: weightKg
        }
        val step = increment.kg ?: return weightKg
        val raw = if (assistance) (weightKg - step).coerceAtLeast(0.0) else weightKg + step
        if (increment.confirmed) return raw
        val rounded = kotlin.math.floor(raw / step + 0.5) * step
        return if (assistance) minOf(rounded, weightKg) else maxOf(rounded, weightKg)
    }
}

/** Auxiliary only; callers must supply total mass for weighted pull-ups. */
object EstimatedOneRepMax {
    fun epley(weightKg: Double?, reps: Int): Double? =
        weightKg?.takeIf { it.isFinite() && it > 0 && reps in 2..10 }?.let { it * (1 + reps / 30.0) }
}
