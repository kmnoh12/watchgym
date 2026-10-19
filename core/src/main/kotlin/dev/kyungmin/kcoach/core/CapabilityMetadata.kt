package dev.kyungmin.kcoach.core

/** Product capability tier; this is a contract, not a proven Watch8 accuracy claim. */
enum class CapabilityTier {
    A_WRIST_DIRECT_AUTO,
    B_AUTO_COUNT_LIMITED_METRICS,
    C_PERSONALIZED_AUTO_COUNT_BETA,
    D_MANUAL_REQUIRED,
}

enum class CountMode {
    AUTO,
    AUTO_LIMITED_METRICS,
    PERSONALIZED_AUTO_BETA,
    MANUAL_REQUIRED,
}

data class ExerciseVariantMetadata(
    val exerciseId: String,
    val variantId: String,
    val protocolId: String,
    val capabilityTier: CapabilityTier,
    val countMode: CountMode,
    val watchSide: String = "either_same_side",
    val wearCondition: String = "wrist_bone_above_snug",
    val grip: String = "exercise_default_recorded",
    val contactCondition: String = "none",
    val supportedMetrics: Set<String>,
    val unsupportedMetrics: Set<String>,
    val fallback: String,
    val requiredSetup: Set<String>,
    val validationMethod: String,
)

/**
 * Variant-level contract for the user's exercise catalog.
 * Standard rows are intentionally conservative. Conditional rows must be
 * selected explicitly; resolve() never silently upgrades a standard protocol.
 */
object ExerciseCapabilityMatrix {
    private val wristMetrics = setOf(
        "rep_count",
        "rep_confidence",
        "wrist_motion_speed",
        "wrist_motion_range",
        "tempo_consistency",
        "signal_quality",
    )
    private val prohibitedMetrics = setOf(
        "joint_rom",
        "actual_load_travel_distance",
        "chin_over_bar",
        "knee_angle",
        "back_neutrality",
        "scapular_alignment",
        "elbow_alignment",
        "strictness",
    )

    private fun standard(
        exerciseId: String,
        tier: CapabilityTier,
        grip: String = "exercise_default_recorded",
        setup: Set<String> = setOf("same_watch_side", "same_wear_position", "same_grip_or_attachment"),
        fallback: String = "manual_phone_or_watch_input",
    ) = ExerciseVariantMetadata(
        exerciseId = exerciseId,
        variantId = "standard",
        protocolId = "standard_wrist_imu",
        capabilityTier = tier,
        countMode = when (tier) {
            CapabilityTier.A_WRIST_DIRECT_AUTO -> CountMode.AUTO
            CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS -> CountMode.AUTO_LIMITED_METRICS
            CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA -> CountMode.PERSONALIZED_AUTO_BETA
            CapabilityTier.D_MANUAL_REQUIRED -> CountMode.MANUAL_REQUIRED
        },
        grip = grip,
        supportedMetrics = if (tier == CapabilityTier.D_MANUAL_REQUIRED) {
            setOf("signal_quality")
        } else {
            wristMetrics
        },
        unsupportedMetrics = prohibitedMetrics,
        fallback = fallback,
        requiredSetup = setup,
        validationMethod = "Watch8 raw IMU + manual/video ground truth; split by side, cadence, load, fatigue, and null segments",
    )

    private val standardRows = ExerciseMotionCatalog.explicitBindings.map { binding ->
        standard(binding.exerciseId, binding.capabilityTier, grip = binding.grip)
    }

    private val conditionalRows = listOf(
        standard(
            exerciseId = "leg_press",
            tier = CapabilityTier.D_MANUAL_REQUIRED,
            setup = setOf("same_watch_side", "same_wear_position", "hand_on_knee_contact", "calibration_5_to_10_reps"),
            fallback = "manual_phone_or_watch_input",
        ).copy(
            variantId = "hand_on_knee_proxy",
            protocolId = "leg_press_contact_proxy",
            contactCondition = "light_hand_on_same_knee_throughout_set",
            supportedMetrics = setOf("signal_quality"),
            fallback = "manual_phone_or_watch_input",
            requiredSetup = setOf("same_machine_setup", "same_watch_side", "same_wear_position", "hand_on_knee_contact", "5_to_10_controlled_reps", "contact_loss_detection"),
            validationMethod = "Watch8 raw IMU proxy + manual/video count; hands-free control must remain MANUAL_REQUIRED",
        ),
    )

    val all: List<ExerciseVariantMetadata> = standardRows + conditionalRows

    fun resolve(
        exerciseId: String,
        variantId: String = "standard",
        protocolId: String = "",
        exerciseName: String = "",
    ): ExerciseVariantMetadata {
        val normalizedExerciseId = ExerciseMotionCatalog.normalizeExerciseId(exerciseId)
        val normalizedVariantId = variantId.trim().lowercase().ifBlank { "standard" }
        val normalizedProtocolId = protocolId.trim().lowercase()
        return all.firstOrNull {
            it.exerciseId == normalizedExerciseId &&
                it.variantId == normalizedVariantId &&
                (normalizedProtocolId.isBlank() || it.protocolId == normalizedProtocolId)
        } ?: standardRows.firstOrNull { it.exerciseId == normalizedExerciseId }
            ?: ExerciseMotionCatalog.resolve(normalizedExerciseId, exerciseName)?.let { inferred ->
                standard(inferred.exerciseId, inferred.capabilityTier, grip = inferred.grip).copy(
                    validationMethod = "Shared ${inferred.family.name} family fallback; Watch8 validation still required",
                )
            }
            ?: standard("unknown", CapabilityTier.D_MANUAL_REQUIRED).copy(
                exerciseId = normalizedExerciseId.ifBlank { "unknown" },
                fallback = "manual_phone_or_watch_input",
                validationMethod = "No motion-family match; manual input required",
            )
    }

    fun standardExerciseIds(): Set<String> = standardRows.mapTo(linkedSetOf()) { it.exerciseId }
}

/** Output guard shared by phone analysis and any future watch-side analyzer. */
object CapabilityOutputPolicy {
    fun sanitize(result: SetAnalysisResult): SetAnalysisResult = when (result.capabilityTier) {
        CapabilityTier.A_WRIST_DIRECT_AUTO,
        CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS,
        CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA -> result
        CapabilityTier.D_MANUAL_REQUIRED -> result.copy(
            watchDetectedReps = null,
            phoneReanalyzedReps = null,
            confirmedReps = null,
            repSource = RepSource.MANUAL,
            repConfidence = null,
            wristMotionSpeed = null,
            wristMotionRange = null,
            tempoConsistency = null,
            motionMetrics = null,
        )
    }
}
