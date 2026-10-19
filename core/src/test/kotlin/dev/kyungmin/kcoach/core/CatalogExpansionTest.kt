package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogExpansionTest {
    @Test fun newWristOnlyRecipesNeverFallBackToAnUnrelatedCameraCounter() {
        val expected = mapOf(
            "conventional_deadlift" to MeasurementRecipe.LOADED_HINGE,
            "walking_lunge_dumbbell" to MeasurementRecipe.LOADED_LUNGE,
            "hip_thrust_barbell" to MeasurementRecipe.HIP_EXTENSION,
            "crunch" to MeasurementRecipe.CORE_FLEXION,
            "russian_twist" to MeasurementRecipe.CORE_ROTATION,
            "ab_wheel_rollout" to MeasurementRecipe.CORE_EXTENSION,
            "chest_dip" to MeasurementRecipe.DIP,
            "thruster" to MeasurementRecipe.SQUAT_PRESS,
        )
        expected.forEach { (id, recipe) ->
            assertEquals(recipe, ExerciseMeasurementContracts.resolve(id).recipe, id)
            assertNull(CameraMotion.forExercise(id), id)
            assertNull(CameraCaptureGuide.forExercise(id, true).metric, id)
            assertNull(CameraCaptureGuide.forExercise(id, false).metric, id)
        }
    }

    @Test fun lungesUseTheApprovedVerticalFamilyAndManualExceptionsStayManual() {
        listOf("walking_lunge_dumbbell", "walking_lunge_barbell", "reverse_lunge", "lateral_lunge",
            "curtsy_lunge", "step_up", "bulgarian_split_squat", "cossack_squat", "arnold_press").forEach {
            assertEquals(MotionFamily.VERTICAL_PRESS_RETURN, ExerciseTrackingProfiles.resolve(it).family, it)
        }
        listOf("assisted_pull_up", "barbell_shrug", "dumbbell_shrug", "machine_shrug", "wrist_curl",
            "reverse_wrist_curl").forEach {
            assertEquals(TrackingMode.MANUAL_REQUIRED, ExerciseTrackingProfiles.resolve(it).mode, it)
        }
        val ids = ExerciseMotionCatalog.explicitBindings.map { it.exerciseId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("barbell_back_squat" !in ids && "barbell_front_squat" !in ids)
        assertTrue(CatalogBetaExercises.exerciseIds.all { it in ids })
    }
}
