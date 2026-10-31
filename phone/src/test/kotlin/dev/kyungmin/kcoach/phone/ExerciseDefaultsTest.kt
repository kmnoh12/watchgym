package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.ExerciseLog
import dev.kyungmin.kcoach.core.LoggedSet
import org.junit.Assert.*
import org.junit.Test

class ExerciseDefaultsTest {
    private fun exercise(pattern: String, equipment: String = "머신") =
        ExerciseDefinition("test", "테스트", "", "", equipment, pattern = pattern)

    @Test fun restUsesAllSpecifiedPatternsAndUnknownFallsBackToIsolation() {
        for (pattern in listOf("chest_press", "row", "vertical_pull", "shoulder_press", "squat", "hinge",
            "leg_press", "push_up", "dip", "hip", "pullover")) {
            assertEquals(120, RoutineExercise(exercise(pattern), mutableListOf()).restSeconds)
        }
        for (pattern in listOf("curl", "triceps_extension", "triceps_pushdown", "lateral_raise", "front_raise",
            "rear_delt", "chest_fly", "shrug", "leg_extension", "leg_curl", "sit_up", "rotation", "", "unknown")) {
            assertEquals(105, ExerciseDefaults.newExercise(exercise(pattern)).restSeconds)
        }
    }

    @Test fun estimatesDependOnEquipmentPatternAndAvailableProfileFields() {
        assertEquals(60.0, ExerciseDefaults.startingWeight(exercise("leg_press"))!!, 0.0)
        assertEquals(30.0, ExerciseDefaults.startingWeight(exercise("row", "바벨"))!!, 0.0)
        assertEquals(4.0, ExerciseDefaults.startingWeight(exercise("lateral_raise", "덤벨"))!!, 0.0)
        assertEquals(20.0, ExerciseDefaults.startingWeight(exercise("row"), WorkoutProfile(gender = "FEMALE"))!!, 0.0)
        assertEquals(25.0, ExerciseDefaults.startingWeight(exercise("row"), WorkoutProfile(ageYears = 65))!!, 0.0)
        assertEquals(20.0, ExerciseDefaults.startingWeight(exercise("row"), WorkoutProfile(weightKg = 50.0, weightMeasuredOn = "2026-10-30"))!!, 0.0)
        assertEquals(30.0, ExerciseDefaults.startingWeight(exercise("row"), WorkoutProfile())!!, 0.0)
    }

    @Test fun onlyBodyweightStartsWithoutExternalWeightAndWeightedDipIsFixed() {
        assertNull(ExerciseDefaults.newExercise(exercise("push_up", "맨몸")).sets.single().weightKg)
        val dip = WorkoutData.catalog.single { it.id == "weighted_dip" }
        assertNotEquals("맨몸", dip.equipment)
        assertEquals(5.0, ExerciseDefaults.newExercise(dip).sets.single().weightKg!!, 0.0)
        for (e in WorkoutData.catalog.filter { it.equipment != "맨몸" }) {
            val kg = ExerciseDefaults.startingWeight(e, WorkoutProfile(gender = "FEMALE", ageYears = 65, weightKg = 50.0, weightMeasuredOn = "2026-10-30"))!!
            val step = if (e.equipment in setOf("머신", "케이블", "핀 머신")) 5.0
                else if (e.equipment in setOf("바벨", "덤벨")) 2.0 else 1.0
            assertTrue(e.id, kg > 0 && kg % step == 0.0)
        }
    }

    @Test fun latestCompletedWorkingWeightWinsWithoutRoundingOrOtherEquipmentLeaking() {
        val logs = listOf(
            ExerciseLog("test", 1, listOf(LoggedSet(27.0, 10)), equipment = "머신"),
            ExerciseLog("test", 2, listOf(LoggedSet(35.0, 10), LoggedSet(54.0, 0, completed = false)), startedAtMs = 1, equipment = "머신"),
            ExerciseLog("test", 2, listOf(LoggedSet(45.0, 10), LoggedSet(10.0, 10, isWarmup = true)), startedAtMs = 2, equipment = "머신"),
            ExerciseLog("test", 3, listOf(LoggedSet(99.0, 10)), equipment = "바벨"))
        val item = ExerciseDefaults.newExercise(exercise("row"), WorkoutProfile(gender = "FEMALE"), logs)
        assertEquals(45.0, item.sets.single().weightKg!!, 0.0)
        assertEquals(10, item.sets.single().targetReps)
        assertEquals(120, item.restSeconds)
        assertFalse(item.sets.single().completed)
    }

    @Test fun defaultTemplatesUpgradeOnlyNinetySecondRest() {
        val routines = WorkoutData.defaultRoutines()
        assertTrue(routines.flatMap { it.exercises }.none { it.restSeconds == 90 })
        assertEquals(45, routines.first().exercises.first().restSeconds)
        assertEquals(150, routines.first { it.id == "chest_triceps" }.exercises.first { it.exercise.id == "flat_bench" }.restSeconds)
    }
}
