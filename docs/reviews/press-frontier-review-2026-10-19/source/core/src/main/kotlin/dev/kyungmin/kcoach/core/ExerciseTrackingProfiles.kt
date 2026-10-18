package dev.kyungmin.kcoach.core

/** What can honestly be inferred from a wrist-worn IMU for an exercise. */
enum class TrackingMode {
    WRIST_IMU,
    WRIST_IMU_CAUTION,
    MANUAL_REQUIRED,
}

enum class TrackingSignal {
    ROTATION_ENERGY,
    DYNAMIC_ACCELERATION,
    COMBINED_MOTION,
    FOREARM_ROTATION,
    UNOBSERVABLE_FROM_WRIST,
}

data class ExerciseTrackingProfile(
    val exerciseId: String,
    val id: String,
    val mode: TrackingMode,
    val signal: TrackingSignal,
    val minimumRepMs: Long,
    val maximumRepMs: Long,
    val minimumSeparationMs: Long,
    val thresholdFraction: Double,
    val minimumRelativeRom: Double,
    val label: String,
    val capabilityTier: CapabilityTier,
    val countMode: CountMode,
    val family: MotionFamily = MotionFamily.UNOBSERVABLE_MANUAL,
    val minimumFeatureRate: Double = 0.20,
    val minimumLobeDisplacement: Double = 0.10,
    val minimumLobeMs: Long = 120L,
    val maximumLobeMs: Long = 1_800L,
    val minimumTrajectorySpread: Double = 0.0,
    val preferredAxis: Int? = null,
    val preferredDirection: Int? = null,
    val gyroNoiseFloor: Double = 0.0,
    val calibrationProfileVersion: Int? = null,
)

/**
 * Resolves an exercise to a shared motion-family template. Exercise entries own
 * identity/capability only; detector thresholds live in MotionFamilyTemplates.
 */
object ExerciseTrackingProfiles {
    fun resolve(exerciseId: String, exerciseName: String = ""): ExerciseTrackingProfile =
        resolveVariant(exerciseId, exerciseName)

    fun resolveVariant(
        exerciseId: String,
        exerciseName: String = "",
        variantId: String = "standard",
        protocolId: String = "",
    ): ExerciseTrackingProfile {
        val normalizedId = ExerciseMotionCatalog.normalizeExerciseId(exerciseId).ifBlank { "unknown" }
        val binding = ExerciseMotionCatalog.resolve(normalizedId, exerciseName)
            ?: return manualProfile(normalizedId, exerciseName.ifBlank { "알 수 없는 운동" })
        if (binding.family == MotionFamily.UNOBSERVABLE_MANUAL) {
            return manualProfile(normalizedId, binding.label)
        }

        val metadata = ExerciseCapabilityMatrix.resolve(
            exerciseId = normalizedId,
            variantId = variantId,
            protocolId = protocolId,
            exerciseName = exerciseName,
        )
        if (metadata.capabilityTier == CapabilityTier.D_MANUAL_REQUIRED) {
            return manualProfile(normalizedId, binding.label)
        }
        return fromFamily(
            exerciseId = normalizedId,
            label = binding.label,
            family = binding.family,
            tier = metadata.capabilityTier,
            countMode = metadata.countMode,
        )
    }

    fun metadataFor(
        exerciseId: String,
        variantId: String = "standard",
        protocolId: String = "",
        exerciseName: String = "",
    ): ExerciseVariantMetadata = ExerciseCapabilityMatrix.resolve(
        exerciseId, variantId, protocolId, exerciseName,
    )

    private fun fromFamily(
        exerciseId: String,
        label: String,
        family: MotionFamily,
        tier: CapabilityTier,
        countMode: CountMode,
    ): ExerciseTrackingProfile {
        val template = MotionFamilyTemplates.get(family)
        return ExerciseTrackingProfile(
            exerciseId = exerciseId,
            id = family.sharedAlgorithmId,
            mode = TrackingMode.WRIST_IMU_CAUTION,
            signal = template.signal,
            minimumRepMs = template.minimumRepMs,
            maximumRepMs = template.maximumRepMs,
            minimumSeparationMs = template.minimumSeparationMs,
            thresholdFraction = 0.0,
            minimumRelativeRom = template.minimumRelativeRom,
            label = "${label} · ${family.displayLabel}",
            capabilityTier = tier,
            countMode = countMode,
            family = family,
            minimumFeatureRate = template.minimumFeatureRate,
            minimumLobeDisplacement = template.minimumLobeDisplacement,
            minimumLobeMs = template.minimumLobeMs,
            maximumLobeMs = template.maximumLobeMs,
            minimumTrajectorySpread = template.minimumTrajectorySpread,
        )
    }

    private fun manualProfile(exerciseId: String, label: String): ExerciseTrackingProfile {
        val template = MotionFamilyTemplates.get(MotionFamily.UNOBSERVABLE_MANUAL)
        return ExerciseTrackingProfile(
            exerciseId = exerciseId,
            id = MotionFamily.UNOBSERVABLE_MANUAL.sharedAlgorithmId,
            mode = TrackingMode.MANUAL_REQUIRED,
            signal = template.signal,
            minimumRepMs = template.minimumRepMs,
            maximumRepMs = template.maximumRepMs,
            minimumSeparationMs = template.minimumSeparationMs,
            thresholdFraction = 0.0,
            minimumRelativeRom = template.minimumRelativeRom,
            label = "$label · 실물 검증/관측 한계로 수동 입력",
            capabilityTier = CapabilityTier.D_MANUAL_REQUIRED,
            countMode = CountMode.MANUAL_REQUIRED,
            family = MotionFamily.UNOBSERVABLE_MANUAL,
            minimumFeatureRate = template.minimumFeatureRate,
            minimumLobeDisplacement = template.minimumLobeDisplacement,
            minimumLobeMs = template.minimumLobeMs,
            maximumLobeMs = template.maximumLobeMs,
            minimumTrajectorySpread = template.minimumTrajectorySpread,
        )
    }
}
