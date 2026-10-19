package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityMetadataTest {
    @Test
    fun standardMatrixCoversAllFiftyCatalogExercises() {
        assertEquals(205, ExerciseCapabilityMatrix.standardExerciseIds().size)
        assertEquals(205, ExerciseCapabilityMatrix.all.count { it.variantId == "standard" })
    }

    @Test
    fun lowerBodyStandardProtocolsRemainManual() {
        listOf("leg_press", "seated_leg_curl", "leg_extension", "hip_adduction", "hip_abduction").forEach { id ->
            val metadata = ExerciseCapabilityMatrix.resolve(id)
            assertEquals(CapabilityTier.D_MANUAL_REQUIRED, metadata.capabilityTier, id)
            assertEquals(CountMode.MANUAL_REQUIRED, metadata.countMode, id)
            assertTrue("rep_count" !in metadata.supportedMetrics, id)
            assertEquals(TrackingMode.MANUAL_REQUIRED, ExerciseTrackingProfiles.resolve(id).mode, id)
        }
    }

    @Test
    fun oldContactProxyCannotPromoteLegPressToAutomatic() {
        val metadata = ExerciseCapabilityMatrix.resolve("leg_press", "hand_on_knee_proxy")
        assertEquals(CapabilityTier.D_MANUAL_REQUIRED, metadata.capabilityTier)
        assertEquals(CountMode.MANUAL_REQUIRED, metadata.countMode)
        assertTrue("contact_validity" !in metadata.supportedMetrics)
        assertTrue("joint_rom" in metadata.unsupportedMetrics)
        assertEquals("manual-required-v3", ExerciseTrackingProfiles.resolveVariant("leg_press", variantId = "hand_on_knee_proxy").id)
        assertEquals(TrackingMode.MANUAL_REQUIRED, ExerciseTrackingProfiles.resolveVariant("leg_press", variantId = "hand_on_knee_proxy").mode)
    }

    @Test
    fun exerciseProfilesExposeTheirMotionSpecificCapabilityTier() {
        assertEquals(CapabilityTier.A_WRIST_DIRECT_AUTO, ExerciseTrackingProfiles.resolve("ez_biceps_curl").capabilityTier)
        assertEquals(CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS, ExerciseTrackingProfiles.resolve("flat_bench").capabilityTier)
        assertEquals(CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS, ExerciseTrackingProfiles.resolve("pull_up").capabilityTier)
        assertEquals(CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA, ExerciseTrackingProfiles.resolve("external_rotation").capabilityTier)
        assertEquals(CapabilityTier.D_MANUAL_REQUIRED, ExerciseTrackingProfiles.resolve("leg_extension").capabilityTier)
        assertEquals(TrackingMode.WRIST_IMU_CAUTION, ExerciseTrackingProfiles.resolve("dumbbell_lateral_raise").mode)
    }

    @Test
    fun outputPolicyRemovesUnsupportedWholeBodyClaims() {
        val unsafe = SetAnalysisResult(
            watchDetectedReps = 12,
            phoneReanalyzedReps = 11,
            confirmedReps = 11,
            repSource = RepSource.CUSTOM_IMU,
            averageRepDurationMs = 800.0,
            tempoVariance = 10.0,
            amplitudeVariance = 0.1,
            stabilityScore = 90,
            consistencyScore = 90,
            lateSetDegradation = 0.0,
            averageHeartRate = 120,
            peakHeartRate = 130,
            analysisConfidence = 0.9,
            algorithmVersion = "test",
            capabilityTier = CapabilityTier.D_MANUAL_REQUIRED,
            repConfidence = 0.9,
            wristMotionSpeed = 2.0,
            wristMotionRange = 0.7,
            tempoConsistency = 90,
            signalQuality = 0.9,
        )
        val safe = CapabilityOutputPolicy.sanitize(unsafe)
        assertEquals(RepSource.MANUAL, safe.repSource)
        assertEquals(null, safe.watchDetectedReps)
        assertEquals(null, safe.phoneReanalyzedReps)
        assertEquals(null, safe.confirmedReps)
        assertEquals(null, safe.repConfidence)
        assertEquals(null, safe.wristMotionSpeed)
        assertEquals(null, safe.tempoConsistency)
        assertEquals(0.9, safe.signalQuality)
    }

    @Test
    fun limitedTierKeepsOnlyObservedMotionMetrics() {
        val result = SetAnalysisResult(
            watchDetectedReps = 1,
            phoneReanalyzedReps = 1,
            confirmedReps = 1,
            repSource = RepSource.CUSTOM_IMU,
            averageRepDurationMs = 800.0,
            tempoVariance = 10.0,
            amplitudeVariance = 0.1,
            stabilityScore = 80,
            consistencyScore = 80,
            lateSetDegradation = 0.0,
            averageHeartRate = 120,
            peakHeartRate = 130,
            analysisConfidence = 0.8,
            algorithmVersion = "test",
            capabilityTier = CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS,
            wristMotionSpeed = 2.0,
            wristMotionRange = 0.7,
            tempoConsistency = 80,
            signalQuality = 0.85,
        )
        val safe = CapabilityOutputPolicy.sanitize(result)
        assertEquals(2.0, safe.wristMotionSpeed)
        assertEquals(0.7, safe.wristMotionRange)
        assertEquals(80, safe.tempoConsistency)
        assertEquals(0.85, safe.signalQuality)
    }
}
