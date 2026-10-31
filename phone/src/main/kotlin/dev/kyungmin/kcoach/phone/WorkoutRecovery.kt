package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.WorkoutSnapshot

/** Rebuild the saved plan, including free workouts, substitutions, custom exercises and edited set counts. */
internal fun restoreWorkoutRoutine(snapshot: WorkoutSnapshot, template: WorkoutRoutine?): WorkoutRoutine {
    val groups = snapshot.plannedSets.groupBy { it.exerciseIndex }.toSortedMap()
    require(groups.isNotEmpty() && groups.keys.toList() == (0 until groups.size).toList()) { "Invalid saved exercise indices" }
    val exercises = groups.map { (index, unsorted) ->
        val sets = unsorted.sortedBy { it.setIndex }
        require(sets.map { it.setIndex } == sets.indices.toList() && sets.map { it.exerciseId }.distinct().size == 1) { "Invalid saved set indices" }
        val first = sets.first()
        val original = WorkoutData.catalog.firstOrNull { it.id == first.exerciseId }
            ?: template?.exercises?.getOrNull(index)?.exercise?.takeIf { it.id == first.exerciseId }
        val definition = ExerciseDefinition(
            id = first.exerciseId, name = first.exerciseName.ifBlank { original?.name ?: first.exerciseId },
            muscles = first.exerciseMuscles.ifBlank { original?.muscles.orEmpty() },
            bodyCategory = first.bodyCategory.ifBlank { original?.bodyCategory ?: "기타" },
            equipment = if (first.exerciseId in setOf("weighted_dip", "weighted_pull_up")) "기타" else first.equipment.ifBlank { original?.equipment ?: "기타" },
            favorite = original?.favorite ?: false, pattern = first.pattern.ifBlank { original?.pattern.orEmpty() },
            family = first.family.ifBlank { original?.family.orEmpty() },
        )
        RoutineExercise(definition, sets.map { saved ->
            PlannedSet(saved.weightKg, saved.targetReps, saved.completed, SetType.fromStorage(saved.setType),
                saved.gripType, saved.rpe, saved.restOverrideSeconds, saved.performedReps, saved.repSource,
                setId = saved.setId.ifBlank { dev.kyungmin.kcoach.core.SetIdentity.migratedId(snapshot.sessionId, saved.exerciseIndex, saved.setIndex) },
                attemptId = saved.attemptId.ifBlank { dev.kyungmin.kcoach.core.SetIdentity.migratedId(snapshot.sessionId, saved.exerciseIndex, saved.setIndex, true) },
                identityRequired = saved.identityRequired, unilateral = saved.unilateral)
        }.toMutableList(), if (first.exerciseName.isNotBlank()) first.exerciseRestSeconds else template?.exercises?.getOrNull(index)?.restSeconds ?: first.exerciseRestSeconds)
    }.toMutableList()
    return WorkoutRoutine(snapshot.routineId,
        snapshot.routineName.ifBlank { template?.name ?: if (snapshot.routineId == "free") "자유운동" else "운동" },
        template?.muscles ?: "선택 운동", "오늘", exercises)
}

/** A saved cursor can be past the end after the last set or after an edit; keep it inside the rebuilt plan. */
internal fun restoredCursor(snapshot: WorkoutSnapshot, routine: WorkoutRoutine): Pair<Int, Int> {
    val exerciseIndex = if (routine.exercises.isEmpty()) 0
        else snapshot.exerciseIndex.coerceIn(0, routine.exercises.lastIndex)
    val setIndex = routine.exercises.getOrNull(exerciseIndex)?.sets?.lastIndex?.coerceAtLeast(0)
        ?.let { snapshot.setIndex.coerceIn(0, it) } ?: 0
    return exerciseIndex to setIndex
}
