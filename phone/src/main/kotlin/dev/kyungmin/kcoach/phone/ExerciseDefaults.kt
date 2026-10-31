package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.ExerciseLog
import dev.kyungmin.kcoach.core.ProgressionDefaults

/** Product starting estimates, not measured strength norms. Dumbbells use one dumbbell's mass. */
internal object ExerciseDefaults {
    private val compound = setOf("chest_press", "row", "vertical_pull", "shoulder_press", "squat",
        "hinge", "leg_press", "push_up", "dip", "hip", "pullover")

    fun restSeconds(exercise: ExerciseDefinition): Int = if (exercise.pattern in compound) 120 else 105

    fun startingWeight(exercise: ExerciseDefinition, profile: WorkoutProfile? = null): Double? {
        if (exercise.equipment == "맨몸" && exercise.id !in setOf("weighted_dip", "weighted_pull_up")) return null
        val base = when {
            exercise.id in setOf("weighted_dip", "weighted_pull_up") -> 5.0
            exercise.equipment == "덤벨" -> when (exercise.pattern) {
                "lateral_raise", "front_raise", "rear_delt", "rotation" -> 4.0
                "curl", "triceps_extension", "chest_fly" -> 6.0
                "squat", "hinge", "row", "shrug", "pullover" -> 12.0
                else -> 8.0
            }
            exercise.equipment == "바벨" -> when (exercise.pattern) {
                "squat", "hinge", "row", "shrug" -> 30.0
                "curl", "triceps_extension", "rotation" -> 10.0
                else -> 20.0
            }
            exercise.equipment in setOf("머신", "핀 머신", "케이블") -> when (exercise.pattern) {
                "leg_press" -> 60.0
                "squat", "hinge", "row", "vertical_pull", "chest_press", "hip", "shrug" -> 30.0
                "rotation", "lateral_raise", "front_raise", "rear_delt" -> 10.0
                else -> 20.0
            }
            else -> 10.0
        }
        val gender = if (profile?.gender == "FEMALE") 0.65 else 1.0
        val age = when (profile?.ageYears) { in 1..17 -> 0.75; in 60..120 -> 0.8; else -> 1.0 }
        val body = ((profile?.weightKg ?: 75.0) / 75.0).coerceIn(0.6, 1.4)
        return ProgressionDefaults.roundWeight(base * gender * age * body, exercise.equipment)
    }

    fun newExercise(exercise: ExerciseDefinition, profile: WorkoutProfile? = null,
        history: List<ExerciseLog> = emptyList()): RoutineExercise {
        val last = history.filter { it.exerciseId == exercise.id &&
            (it.equipment == exercise.equipment || it.equipment.isEmpty() ||
                (exercise.id in setOf("weighted_dip", "weighted_pull_up") && it.equipment == "맨몸")) }
            .sortedWith(compareByDescending<ExerciseLog> { it.epochDay }.thenByDescending { it.startedAtMs })
            .firstNotNullOfOrNull { log -> log.sets.lastOrNull { it.completed && it.repsEntered &&
                !it.isWarmup && it.reps > 0 && it.weightKg?.let { kg -> kg.isFinite() && kg >= 0 } == true } }
        return RoutineExercise(exercise, mutableListOf(PlannedSet(
            last?.weightKg ?: startingWeight(exercise, profile), 10)), restSeconds(exercise))
    }
}
