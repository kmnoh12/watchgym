package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutDataTest {
    @Test
    fun `home routines preserve the requested four split order`() {
        assertEquals(
            listOf("어깨전문", "등·이두", "가슴·삼두", "팔·하체"),
            WorkoutData.defaultRoutines().map { it.name },
        )
    }

    @Test
    fun `routine exercises preserve the observed workout order`() {
        val routines = WorkoutData.defaultRoutines().associateBy { it.id }
        assertEquals(
            listOf(
                "라잉 덤벨 풀오버",
                "바벨 플랫 벤치 프레스",
                "바벨 인클라인 벤치 프레스",
                "머신 플라이",
                "케이블 스트레이트바 트라이셉 푸시다운",
                "케이블 스트레이트바 오버헤드 트라이셉 익스텐션",
            ),
            routines.getValue("chest_triceps").exercises.map { it.exercise.name },
        )
        assertEquals("풀업", routines.getValue("arms_legs").exercises.first().exercise.name)
        assertEquals("레그 익스텐션", routines.getValue("arms_legs").exercises.last().exercise.name)
    }

    @Test
    fun `exercise catalog is normalized and category ready`() {
        val ids = WorkoutData.catalog.map { it.id }
        assertEquals(252, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        WorkoutData.catalog.forEach { exercise ->
            assertTrue(exercise.id, exercise.bodyCategory in dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyOrder)
            assertTrue(exercise.id, exercise.equipment in setOf("맨몸", "덤벨", "바벨", "머신", "케이블", "기타"))
            assertTrue(exercise.id, dev.kyungmin.kcoach.core.ExerciseTaxonomy.section(
                exercise.bodyCategory, exercise.pattern).first != dev.kyungmin.kcoach.core.ExerciseTaxonomy.OTHER_ORDER)
        }
        assertTrue(WorkoutData.catalog.all { it.bodyCategory.isNotBlank() && it.equipment.isNotBlank() })
        assertFalse(WorkoutData.catalog.any { it.name == "풀 업" })
        assertTrue(WorkoutData.catalog.any { it.name == "풀업" })
    }

    @Test
    fun `bilateral dumbbell row stays separate from legacy one arm row records`() {
        val bilateral = WorkoutData.catalog.single { it.name == "덤벨 로우" }
        val oneArm = WorkoutData.catalog.single { it.id == "one_arm_dumbbell_row" }
        assertEquals("row", bilateral.pattern)
        assertEquals("덤벨", bilateral.equipment)
        assertFalse(bilateral.id == oneArm.id)
        assertEquals(dev.kyungmin.kcoach.core.MeasurementRecipe.ROW,
            dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.resolve(bilateral.id).recipe)
        assertEquals(dev.kyungmin.kcoach.core.MotionFamily.CYCLE_AXIS_RETURN,
            dev.kyungmin.kcoach.core.ExerciseTrackingProfiles.resolve(bilateral.id).family)
        assertTrue(dev.kyungmin.kcoach.core.UnilateralExercises.initial(bilateral.id) == null)
        // Existing historical alias must keep its original unilateral meaning.
        assertEquals(oneArm.id, dev.kyungmin.kcoach.core.ExerciseMotionCatalog.normalizeExerciseId("dumbbell_row"))
        assertTrue(dev.kyungmin.kcoach.core.UnilateralExercises.initial("dumbbell_row") != null)
    }

    @Test
    fun `popular unilateral variants are searchable and hold rest until side decision`() {
        val ids = listOf("single_arm_cable_external_rotation", "single_arm_cable_reverse_fly",
            "single_arm_machine_seated_row", "single_arm_high_row_machine", "single_arm_plate_loaded_row",
            "single_arm_machine_pulldown_pin", "single_arm_machine_pulldown_plate", "single_arm_reverse_pec_deck", "single_arm_cable_overhead_extension",
            "single_arm_cable_curl", "single_arm_dumbbell_preacher_curl", "one_arm_cable_lateral",
            "single_arm_bayesian_curl", "kickback_cable",
            "single_arm_dumbbell_kickback",
            "single_arm_chest_supported_dumbbell_row",
            "single_arm_machine_lateral_raise",
            "single_arm_dumbbell_lateral_raise",
            "single_arm_cable_front_raise")
        for (id in ids) {
            val definition = WorkoutData.catalog.single { it.id == id }
            if (id != "kickback_cable") assertTrue(id, definition.name.replace(" ", "").contains("원암"))
            assertTrue(id, definition.pattern.isNotBlank() && definition.family.isNotBlank())
            assertFalse(id, ExerciseMeasurementContracts.resolve(id).recipe == MeasurementRecipe.MANUAL)
            assertFalse(id, ExerciseTrackingProfiles.resolve(id).family == MotionFamily.UNOBSERVABLE_MANUAL)
            val set = PlannedSetSnapshot(0, id, 0, 10, 5.0, false, setId = "set", attemptId = "a")
            val initial = WorkoutSnapshot(sessionId = "s", routineId = "r", exerciseIndex = 0, setIndex = 0,
                plannedSets = listOf(set), performedReps = emptyMap(), phase = WorkoutPhase.WORKING,
                restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false, startedAtMs = 1,
                pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
            val result = WorkoutSessionReducer.completeSet(initial,
                SetCompletionEvent("s", id, 0, 10, 90_000, 1, SetRepSource.WATCH, "set", "a"))
            assertTrue(id, result is SetCompletionResult.Applied)
            assertEquals(id, UnilateralStage.AWAITING_OTHER_SIDE, result.snapshot.plannedSets.single().unilateral?.stage)
            assertEquals(id, 0L, result.snapshot.restEndAtMs)
            assertFalse(id, result.snapshot.plannedSets.single().completed)
        }
        assertEquals(StartWristOrientationPolicy.candidate("external_rotation"),
            StartWristOrientationPolicy.candidate("single_arm_cable_external_rotation"))
        assertEquals("kickback_cable", ExerciseMotionCatalog.normalizeExerciseId("single_arm_cable_kickback"))
        assertEquals("케이블 트라이셉스 킥백", WorkoutData.catalog.single { it.id == "kickback_cable" }.name)
        val bases = listOf("bayesian_curl", "kickback_dumbbell", "chest_supported_dumbbell_row",
            "machine_lateral_raise", "dumbbell_lateral_raise", "cable_front_raise")
        val added = listOf("single_arm_bayesian_curl", "single_arm_dumbbell_kickback",
            "single_arm_chest_supported_dumbbell_row", "single_arm_machine_lateral_raise",
            "single_arm_dumbbell_lateral_raise", "single_arm_cable_front_raise")
        bases.zip(added).forEach { (base, variant) ->
            assertTrue(WorkoutData.catalog.any { it.id == base })
            assertTrue(UnilateralExercises.initial(base) == null)
            assertEquals(ExerciseTrackingProfiles.resolve(base).family, ExerciseTrackingProfiles.resolve(variant).family)
            assertEquals(ExerciseMeasurementContracts.resolve(base).recipe, ExerciseMeasurementContracts.resolve(variant).recipe)
        }
        assertEquals(StartWristOrientationPolicy.candidate("dumbbell_lateral_raise"),
            StartWristOrientationPolicy.candidate("single_arm_dumbbell_lateral_raise"))
        // Existing bilateral choices retain immediate whole-set completion, rather than a side prompt.
        for (id in listOf("seated_row", "high_row_machine", "machine_reverse_fly", "cable_rear_delt_fly")) {
            assertTrue(id, UnilateralExercises.initial(id) == null)
        }
    }

    @Test
    fun `machine pulldowns distinguish pin plate and unilateral without rewriting old records`() {
        val names = mapOf("machine_pulldown_pin" to "머신 풀다운(핀식)",
            "machine_pulldown_plate" to "머신 풀다운(플레이트식)",
            "single_arm_machine_pulldown_pin" to "원암 머신 풀다운(핀식)",
            "single_arm_machine_pulldown_plate" to "원암 머신 풀다운(플레이트식)")
        for ((id, name) in names) {
            val entry = WorkoutData.catalog.single { it.id == id }
            assertEquals(name, entry.name)
            assertEquals("머신", entry.equipment)
            assertEquals("vertical_pull", entry.pattern)
            assertEquals("머신 풀다운", entry.family)
            assertEquals(MeasurementRecipe.VERTICAL_PULL, ExerciseMeasurementContracts.resolve(id).recipe)
            assertEquals(MotionFamily.PULL_RETURN, ExerciseTrackingProfiles.resolve(id).family)
            assertEquals(id.startsWith("single_arm_"), UnilateralExercises.initial(id) != null)
        }
        for (id in listOf("machine_pulldown_pin", "machine_pulldown_plate")) {
            val initial = WorkoutSnapshot(sessionId = "s", routineId = "r", exerciseIndex = 0, setIndex = 0,
                plannedSets = listOf(PlannedSetSnapshot(0, id, 0, 10, 20.0, false, setId = "set", attemptId = "a")),
                performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0,
                pausedRestRemainingMs = 0, paused = false, startedAtMs = 1, pausedSinceMs = 0,
                totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
            val done = WorkoutSessionReducer.completeSet(initial, SetCompletionEvent("s", id, 0, 10, 90_000, 1,
                SetRepSource.WATCH, "set", "a")).snapshot
            assertTrue(done.plannedSets.single().completed)
            assertEquals(WorkoutPhase.RESTING, done.phase)
        }
        val legacy = "single_arm_machine_lat_pulldown"
        assertFalse(WorkoutData.catalog.any { it.id == legacy })
        assertEquals(legacy, ExerciseMotionCatalog.normalizeExerciseId(legacy))
        assertEquals(MotionFamily.PULL_RETURN, ExerciseTrackingProfiles.resolve(legacy).family)
        assertEquals(MeasurementRecipe.VERTICAL_PULL, ExerciseMeasurementContracts.resolve(legacy).recipe)
        assertTrue(UnilateralExercises.initial(legacy) != null)
    }

    @Test
    fun `torso rotation machine requires manual input rather than borrowing wrist rotation counting`() {
        val entry = WorkoutData.catalog.single { it.id == "machine_torso_rotation" }
        assertEquals("머신 토르소 로테이션", entry.name)
        assertEquals("코어", entry.bodyCategory)
        assertEquals("core_rotation", entry.pattern)
        assertEquals("머신", entry.equipment)
        assertEquals(TrackingMode.MANUAL_REQUIRED, ExerciseTrackingProfiles.resolve(entry.id).mode)
        assertEquals(CapabilityTier.D_MANUAL_REQUIRED, ExerciseMotionCatalog.resolve(entry.id)!!.capabilityTier)
        assertEquals(MeasurementRecipe.MANUAL, ExerciseMeasurementContracts.resolve(entry.id).recipe)
        assertEquals(StartWristCandidate.MANUAL, StartWristOrientationPolicy.candidate(entry.id))
        assertTrue(UnilateralExercises.initial(entry.id) == null)
    }

    @Test
    fun `warmups use W and do not consume work set numbers`() {
        val sets = listOf(
            PlannedSet(5.0, 20, setType = SetType.WARMUP),
            PlannedSet(10.0, 12),
            PlannedSet(10.0, 10, setType = SetType.DROP),
            PlannedSet(5.0, 20, setType = SetType.WARMUP),
            PlannedSet(12.0, 8, completed = true),
        )

        assertEquals(listOf("W", "1", "2", "W", "3"), sets.indices.map(sets::workSetLabel))
        assertEquals(3, sets.workSetCount())
        assertEquals(1, sets.completedWorkSetCount())
    }

    @Test
    fun `removed legacy set types fall back to normal`() {
        assertEquals(SetType.NORMAL, SetType.fromStorage("컴파운드"))
        assertEquals(SetType.NORMAL, SetType.fromStorage("트라이"))
        assertEquals(SetType.NORMAL, SetType.fromStorage("암랩"))
    }

    @Test
    fun `duplicate inserts an unrecorded copy directly after the chosen set`() {
        val sets = mutableListOf(
            PlannedSet(20.0, 10, completed = true, performedReps = 9, repSource = SetRepSource.MANUAL,
                gripType = "언더", restSeconds = 45),
            PlannedSet(25.0, 8),
            PlannedSet(30.0, 6),
        )
        val sourceId = sets[0].setId
        val copy = sets.duplicateAfter(0)!!

        assertEquals(4, sets.size)
        assertEquals(sourceId, sets[0].setId)
        assertEquals(copy.setId, sets[1].setId)
        assertEquals(25.0, sets[2].weightKg)
        assertEquals(20.0, copy.weightKg)
        assertEquals(10, copy.targetReps)
        assertEquals("언더", copy.gripType)
        assertEquals(45, copy.restSeconds)
        assertFalse(copy.completed)
        assertEquals(null, copy.performedReps)
        assertEquals(null, copy.repSource)
        assertTrue(copy.setId != sourceId)
        assertEquals(null, sets.duplicateAfter(9))
        assertEquals(4, sets.size)
    }

    @Test
    fun `moving one exercise shifts every intervening index as a block`() {
        assertEquals(2, adjustedIndexAfterMove(current = 0, from = 0, to = 2))
        assertEquals(0, adjustedIndexAfterMove(current = 1, from = 0, to = 2))
        assertEquals(1, adjustedIndexAfterMove(current = 2, from = 0, to = 2))

        assertEquals(0, adjustedIndexAfterMove(current = 2, from = 2, to = 0))
        assertEquals(1, adjustedIndexAfterMove(current = 0, from = 2, to = 0))
        assertEquals(2, adjustedIndexAfterMove(current = 1, from = 2, to = 0))
    }
}
