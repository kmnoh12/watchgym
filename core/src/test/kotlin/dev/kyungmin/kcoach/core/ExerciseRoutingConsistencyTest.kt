package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExerciseRoutingConsistencyTest {
    @Test fun benchPressesUseTheTranslationFamilyRegardlessOfEquipment() {
        listOf(
            "flat_bench", "incline_bench", "dumbbell_bench", "decline_barbell_bench_press",
            "incline_dumbbell_bench_press", "decline_dumbbell_bench_press",
            "smith_flat_bench_press", "smith_incline_bench_press",
            "single_arm_dumbbell_bench_press", "flat_machine_bench_press",
        ).forEach {
            assertEquals(MotionFamily.TRANSLATION_PRESS_RETURN, ExerciseTrackingProfiles.resolve(it).family, it)
            assertEquals(MeasurementRecipe.CHEST_PRESS, ExerciseMeasurementContracts.resolve(it).recipe, it)
        }
    }

    @Test fun fixedWristPushdownsUseTranslationWhileOverheadExtensionsUseRotation() {
        listOf("reverse_grip_pushdown", "single_arm_cable_pushdown").forEach {
            assertEquals(MotionFamily.TRANSLATION_PRESS_RETURN, ExerciseTrackingProfiles.resolve(it).family, it)
        }
        listOf("cable_pushdown", "cable_overhead_extension", "rope_overhead_extension", "rope_extension", "ez_lying_extension").forEach {
            assertEquals(MotionFamily.FLEXION_EXTENSION, ExerciseTrackingProfiles.resolve(it).family, it)
        }
    }

    @Test fun straightBarAndRopePushdownsRecognizeTheSameObservedCycles() {
        val samples = CurlCycleRegressionTest.curlSamples(10, 1.0, 1.0)
        fun boundaries(id: String): List<Long> {
            val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve(id))
            detector.beginCounting()
            return samples.mapNotNull { detector.add(it)?.endTimestampNs }
        }
        val rope = boundaries("rope_extension")
        assertEquals(10, rope.size)
        assertEquals(rope, boundaries("cable_pushdown"))
    }

    @Test fun legacyRopePushdownIdStillResolvesToAWorkingEngineAndHistoryProfile() {
        val legacy = ExerciseTrackingProfiles.resolve("rope_pushdown")
        val canonical = ExerciseTrackingProfiles.resolve("rope_extension")
        assertEquals("rope_extension", ExerciseMotionCatalog.normalizeExerciseId("rope_pushdown"))
        assertEquals(canonical, legacy)
        assertEquals(MotionFamily.FLEXION_EXTENSION, legacy.family)
    }

    @Test fun labelsSeparateStraightBarAndRopeAttachments() {
        val labels = ExerciseMotionCatalog.explicitBindings.associate { it.exerciseId to it.label }
        assertTrue(labels.getValue("cable_pushdown").contains("스트레이트바"))
        assertTrue(labels.getValue("cable_overhead_extension").contains("스트레이트바"))
        assertTrue(labels.getValue("rope_extension").contains("로프"))
        assertTrue(labels.getValue("rope_overhead_extension").contains("로프"))
        assertEquals("머신 플랫 벤치 프레스", labels.getValue("flat_machine_bench_press"))
        assertEquals("체스트 프레스(플레이트식)", labels.getValue("machine_chest_press_plate"))
    }

    @Test fun flatMachineBenchIsADistinctLyingChestPressEntry() {
        val binding = requireNotNull(ExerciseMotionCatalog.resolve("flat_machine_bench_press"))
        assertEquals("머신 플랫 벤치 프레스", binding.label)
        assertEquals(MotionFamily.TRANSLATION_PRESS_RETURN, binding.family)
        assertEquals(CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS, binding.capabilityTier)
        assertEquals(MeasurementRecipe.CHEST_PRESS, binding.measurementContract.recipe)
    }

    @Test fun catalogCountsStayStableAfterLegacyRopePushdownRemoval() {
        assertEquals(252, ExerciseMotionCatalog.explicitBindings.size)
        assertEquals(252, ExerciseCapabilityMatrix.standardExerciseIds().size)
        assertTrue("rope_pushdown" !in ExerciseCapabilityMatrix.standardExerciseIds())
        assertTrue("flat_machine_bench_press" in ExerciseCapabilityMatrix.standardExerciseIds())
    }
}
