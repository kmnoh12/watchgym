package dev.kyungmin.kcoach.core

/**
 * One detector configuration per observable wrist-motion family.
 *
 * Exercise names select a family; they do not own a private copy of these
 * thresholds. A family value should only change from shared Watch8 evidence.
 */
data class MotionFamilyTemplate(
    val family: MotionFamily,
    val signal: TrackingSignal,
    val minimumRepMs: Long,
    val maximumRepMs: Long,
    val minimumSeparationMs: Long,
    val minimumRelativeRom: Double,
    val minimumFeatureRate: Double,
    val minimumLobeDisplacement: Double,
    val minimumLobeMs: Long,
    val maximumLobeMs: Long,
    val minimumTrajectorySpread: Double = 0.0,
)

data class ExerciseMotionBinding(
    val exerciseId: String,
    val label: String,
    val family: MotionFamily,
    val capabilityTier: CapabilityTier,
    val grip: String = "exercise_default_recorded",
) {
    val recipe: MeasurementRecipe get() = ExerciseMeasurementContracts.resolve(exerciseId).recipe
    val conditionDescription: String get() = ExerciseMeasurementContracts.resolve(exerciseId).conditionDescription
    val measurementContract: ExerciseMeasurementContract get() = ExerciseMeasurementContracts.resolve(exerciseId)
}

object MotionFamilyTemplates {
    private val templates = listOf(
        MotionFamilyTemplate(
            MotionFamily.FLEXION_EXTENSION, TrackingSignal.ROTATION_ENERGY,
            350L, 4_500L, 450L, 0.12, 0.16, 0.08, 120L, 1_700L,
        ),
        MotionFamilyTemplate(
            MotionFamily.PRESS_RETURN, TrackingSignal.COMBINED_MOTION,
            400L, 5_000L, 550L, 0.13, 0.12, 0.07, 110L, 1_800L,
        ),
        MotionFamilyTemplate(
            MotionFamily.VERTICAL_PRESS_RETURN, TrackingSignal.DYNAMIC_ACCELERATION,
            600L, 8_000L, 0L, 0.0, 0.12, 0.04, 150L, 3_000L,
        ),
        MotionFamilyTemplate(
            MotionFamily.PULL_RETURN, TrackingSignal.COMBINED_MOTION,
            500L, 6_000L, 650L, 0.15, 0.14, 0.07, 120L, 2_200L,
        ),
        MotionFamilyTemplate(
            MotionFamily.MULTI_AXIS_RETURN, TrackingSignal.COMBINED_MOTION,
            450L, 5_000L, 550L, 0.14, 0.12, 0.07, 110L, 2_000L, 0.04,
        ),
        MotionFamilyTemplate(
            MotionFamily.FOREARM_ROTATION, TrackingSignal.FOREARM_ROTATION,
            400L, 4_500L, 400L, 0.18, 0.12, 0.22, 100L, 2_100L,
        ),
        // Detector constants live in CycleAxisRepDetector; only the shared rep-duration bounds are used here.
        MotionFamilyTemplate(
            MotionFamily.CYCLE_AXIS_RETURN, TrackingSignal.COMBINED_MOTION,
            600L, 7_000L, 0L, 0.0, 0.0, 0.0, 0L, 0L,
        ),
        MotionFamilyTemplate(
            MotionFamily.UNOBSERVABLE_MANUAL, TrackingSignal.UNOBSERVABLE_FROM_WRIST,
            0L, 0L, 0L, 0.0, 0.0, 0.0, 0L, 0L,
        ),
    ).associateBy { it.family }

    fun get(family: MotionFamily): MotionFamilyTemplate = checkNotNull(templates[family])
}

/**
 * Catalog entries only bind an exercise to a shared family and capability.
 * Repeated detector thresholds deliberately do not belong here.
 */
object ExerciseMotionCatalog {
    val explicitBindings: List<ExerciseMotionBinding> = listOf(
        manual("pull_up", "풀업"),
        manual("assisted_pull_up", "어시스티드 풀업"),
        binding("lying_tbar_row", "라잉 티바 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("lat_pull_down", "랫 풀다운", MotionFamily.PULL_RETURN),
        binding("wide_lat_pull_down", "와이드 그립 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("close_lat_pull_down", "내로우 그립 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("reverse_lat_pull_down", "언더 그립 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("one_arm_lat_pull_down", "원 암 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("seated_row", "시티드 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("cable_seated_row", "케이블 시티드 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("barbell_row", "바벨 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("one_arm_dumbbell_row", "원 암 덤벨 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("ez_biceps_curl", "이지바 바이셉 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "same_bar_grip"),
        binding("barbell_biceps_curl", "바벨 바이셉 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "same_bar_grip"),
        binding("preacher_curl", "프리처 컬", MotionFamily.FLEXION_EXTENSION),
        binding("incline_curl", "인클라인 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO),
        binding("incline_hammer_curl", "인클라인 해머 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "neutral_grip"),
        binding("dumbbell_curl", "덤벨 바이셉 컬", MotionFamily.FLEXION_EXTENSION),
        binding("dumbbell_pullover", "덤벨 풀오버", MotionFamily.MULTI_AXIS_RETURN),
        binding("flat_bench", "플랫 벤치프레스", MotionFamily.PRESS_RETURN, grip = "same_grip_width"),
        binding("incline_bench", "인클라인 벤치프레스", MotionFamily.PRESS_RETURN, grip = "same_grip_width"),
        binding("chest_press", "체스트프레스", MotionFamily.PRESS_RETURN),
        binding("dumbbell_bench", "덤벨 벤치프레스", MotionFamily.PRESS_RETURN),
        binding("machine_fly", "머신 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("cable_pushdown", "케이블 푸시다운", MotionFamily.PRESS_RETURN),
        binding("cable_overhead_extension", "케이블 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("rope_extension", "로프 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("rope_overhead_extension", "로프 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("dumbbell_triceps_extension", "덤벨 트라이셉스 익스텐션", MotionFamily.CYCLE_AXIS_RETURN),
        binding("ez_lying_extension", "이지바 라잉 익스텐션", MotionFamily.PRESS_RETURN),
        binding("external_rotation", "케이블 외회전", MotionFamily.FOREARM_ROTATION, CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA),
        binding("crossover_reverse_fly", "크로스오버 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("smith_shoulder_press", "스미스 숄더프레스", MotionFamily.VERTICAL_PRESS_RETURN),
        binding("machine_shoulder_press", "머신 숄더프레스", MotionFamily.PRESS_RETURN),
        binding("dumbbell_shoulder_press", "덤벨 숄더프레스", MotionFamily.VERTICAL_PRESS_RETURN),
        binding("dumbbell_lateral_raise", "덤벨 레터럴 레이즈", MotionFamily.CYCLE_AXIS_RETURN),
        binding("one_arm_cable_lateral", "원 암 케이블 레터럴", MotionFamily.CYCLE_AXIS_RETURN),
        binding("machine_reverse_fly", "머신 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN),
        manual("leg_press", "레그프레스"),
        manual("seated_leg_curl", "시티드 레그컬"),
        manual("leg_extension", "레그 익스텐션"),
        manual("hip_adduction", "힙 어덕션"),
        manual("hip_abduction", "힙 어브덕션"),
    )

    private val byExercise = explicitBindings.associateBy { it.exerciseId }
    private val aliases = mapOf(
        "cable_external_rotation" to "external_rotation",
        "cable_external_rot" to "external_rotation",
        "dumbbell_biceps_curl" to "dumbbell_curl",
        "dumbbell_row" to "one_arm_dumbbell_row",
    )

    fun normalizeExerciseId(exerciseId: String): String {
        val normalized = exerciseId.trim().lowercase()
        return aliases[normalized] ?: normalized
    }

    fun resolve(exerciseId: String, exerciseName: String = ""): ExerciseMotionBinding? {
        val normalizedId = normalizeExerciseId(exerciseId)
        return byExercise[normalizedId] ?: manual(normalizedId.ifBlank { "unknown" },
            exerciseName.ifBlank { "사용자 운동" })
    }

    private fun binding(
        exerciseId: String,
        label: String,
        family: MotionFamily,
        tier: CapabilityTier = CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS,
        grip: String = "exercise_default_recorded",
    ) = ExerciseMotionBinding(exerciseId, label, family, tier, grip)

    private fun manual(exerciseId: String, label: String) = binding(
        exerciseId, label, MotionFamily.UNOBSERVABLE_MANUAL, CapabilityTier.D_MANUAL_REQUIRED,
    )

}
