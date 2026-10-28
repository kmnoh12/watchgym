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
        // Translation-dominant bench press and fixed-wrist pushdown. The detector owns its
        // displacement/span floors; these are the shared repetition timing bounds.
        MotionFamilyTemplate(
            MotionFamily.TRANSLATION_PRESS_RETURN, TrackingSignal.DYNAMIC_ACCELERATION,
            600L, 8_000L, 0L, 0.0, 0.05, 0.04, 150L, 5_000L,
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
        binding("pull_up", "풀업", MotionFamily.CYCLE_AXIS_RETURN, grip = "same_bar_grip"),
        binding("assisted_pull_up", "어시스티드 풀업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("lying_tbar_row", "라잉 티바 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("lat_pull_down", "랫 풀다운", MotionFamily.PULL_RETURN),
        binding("machine_pulldown_pin", "머신 풀다운(핀식)", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_machine_pulldown_pin", "원암 머신 풀다운(핀식)", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_pulldown_plate", "머신 풀다운(플레이트식)", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_front_pulldown_plate", "머신 프론트 풀다운(플레이트식)", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_front_pulldown_pin", "머신 프론트 풀다운(핀식)", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_machine_pulldown_plate", "원암 머신 풀다운(플레이트식)", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("close_lat_pull_down", "내로우 그립 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("reverse_lat_pull_down", "언더 그립 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("one_arm_lat_pull_down", "원암 랫 풀다운", MotionFamily.PULL_RETURN),
        binding("seated_row", "시티드 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("single_arm_machine_seated_row", "원암 머신 시티드 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_seated_row", "케이블 시티드 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("barbell_row", "바벨 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("bent_over_dumbbell_row", "덤벨 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("one_arm_dumbbell_row", "원암 덤벨 로우", MotionFamily.CYCLE_AXIS_RETURN),
        binding("ez_biceps_curl", "이지바 바이셉 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "same_bar_grip"),
        binding("barbell_biceps_curl", "바벨 바이셉 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "same_bar_grip"),
        binding("preacher_curl", "프리처 컬", MotionFamily.FLEXION_EXTENSION),
        binding("incline_curl", "인클라인 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO),
        binding("incline_hammer_curl", "인클라인 해머 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "neutral_grip"),
        binding("dumbbell_curl", "덤벨 바이셉 컬", MotionFamily.FLEXION_EXTENSION),
        binding("dumbbell_pullover", "덤벨 풀오버", MotionFamily.MULTI_AXIS_RETURN),
        binding("flat_bench", "플랫 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, grip = "same_grip_width"),
        binding("incline_bench", "인클라인 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, grip = "same_grip_width"),
        binding("chest_press", "체스트프레스", MotionFamily.PRESS_RETURN),
        binding("dumbbell_bench", "덤벨 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN),
        binding("machine_fly", "머신 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("cable_pushdown", "케이블 스트레이트바 푸시다운", MotionFamily.FLEXION_EXTENSION),
        binding("cable_overhead_extension", "케이블 스트레이트바 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("single_arm_cable_overhead_extension", "원암 케이블 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("rope_extension", "케이블 로프 푸시다운", MotionFamily.FLEXION_EXTENSION),
        binding("rope_overhead_extension", "케이블 로프 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("dumbbell_triceps_extension", "덤벨 트라이셉스 익스텐션", MotionFamily.CYCLE_AXIS_RETURN),
        binding("ez_lying_extension", "이지바 라이잉 트라이셉 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("external_rotation", "케이블 외회전", MotionFamily.FOREARM_ROTATION, CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA),
        binding("single_arm_cable_external_rotation", "원암 케이블 익스터널 로테이션", MotionFamily.FOREARM_ROTATION, CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA),
        binding("crossover_reverse_fly", "크로스오버 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("smith_shoulder_press", "스미스 숄더프레스", MotionFamily.VERTICAL_PRESS_RETURN),
        binding("machine_shoulder_press", "머신 숄더프레스", MotionFamily.PRESS_RETURN),
        binding("dumbbell_shoulder_press", "덤벨 숄더프레스", MotionFamily.VERTICAL_PRESS_RETURN),
        binding("dumbbell_lateral_raise", "덤벨 레터럴 레이즈", MotionFamily.CYCLE_AXIS_RETURN),
        binding("single_arm_dumbbell_lateral_raise", "원암 덤벨 레터럴 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("one_arm_cable_lateral", "원암 케이블 레터럴", MotionFamily.CYCLE_AXIS_RETURN),
        binding("machine_reverse_fly", "머신 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("single_arm_reverse_pec_deck", "원암 머신 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("push_up", "푸시업", MotionFamily.CYCLE_AXIS_RETURN),
        binding("sit_up", "싯업", MotionFamily.CYCLE_AXIS_RETURN),
        binding("squat", "스쿼트", MotionFamily.CYCLE_AXIS_RETURN),
        binding("barbell_squat", "백 스쿼트", MotionFamily.CYCLE_AXIS_RETURN),
        binding("front_squat", "프런트 스쿼트", MotionFamily.CYCLE_AXIS_RETURN),
        binding("goblet_squat", "고블릿 스쿼트", MotionFamily.CYCLE_AXIS_RETURN),
        binding("smith_squat", "스미스 스쿼트", MotionFamily.CYCLE_AXIS_RETURN),
        manual("leg_press", "레그프레스"),
        manual("seated_leg_curl", "시티드 레그컬"),
        manual("leg_extension", "레그 익스텐션"),
        manual("hip_adduction", "힙 어덕션"),
        manual("hip_abduction", "힙 어브덕션"),

        // 가슴 — approved 2026-10-19 catalog
        binding("decline_barbell_bench_press", "디클라인 바벨 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("incline_dumbbell_bench_press", "인클라인 덤벨 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("decline_dumbbell_bench_press", "디클라인 덤벨 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("smith_flat_bench_press", "스미스 플랫 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("smith_incline_bench_press", "스미스 인클라인 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_chest_press_plate", "체스트 프레스(플레이트식)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("incline_machine_chest_press_pin", "인클라인 체스트 프레스(핀식)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("incline_machine_chest_press_plate", "인클라인 체스트 프레스(플레이트식)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("decline_machine_chest_press", "디클라인 체스트 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("flat_machine_bench_press", "머신 플랫 벤치 프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("incline_machine_bench_press_plate", "머신 인클라인 벤치 프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("decline_machine_bench_press_plate", "머신 디클라인 벤치 프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_fly_high", "케이블 플라이(하이)", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_fly_mid", "케이블 플라이(미들)", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_fly_low", "케이블 플라이(로우)", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("incline_dumbbell_fly", "인클라인 덤벨 플라이", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("incline_push_up", "인클라인 푸쉬업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("decline_push_up", "디클라인 푸쉬업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("chest_dip", "딥스", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("weighted_dip", "중량 딥스", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("floor_press_barbell", "플로어 프레스(바벨)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("hex_press", "헥스 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("landmine_press", "랜드마인 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("guillotine_press", "길로틴 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_dumbbell_bench_press", "원암 덤벨 벤치프레스", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_cable_fly", "원암 케이블 플라이", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),

        // 등 — approved 2026-10-19 catalog
        binding("chin_up", "친업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("neutral_grip_pull_up", "뉴트럴그립 풀업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("straight_arm_pulldown", "스트레이트 암 풀다운", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("seated_cable_row_close", "와이드 그립 케이블 시티드 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("one_arm_cable_row", "원암 케이블 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("tbar_row_landmine", "랜드마인 티바 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("chest_supported_dumbbell_row", "체스트 서포티드 덤벨 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_chest_supported_dumbbell_row", "원암 체스트 서포티드 덤벨 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_row_plate", "플레이트 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_plate_loaded_row", "원암 플레이트 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("high_row_machine", "머신 하이 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_high_row_machine", "원암 머신 하이 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("seal_row", "씰 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("meadows_row", "메도우스 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("inverted_row", "인버티드 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("hyperextension", "하이퍼익스텐션"),
        binding("good_morning", "굿모닝", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("rack_pull", "랙풀", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_pullover", "케이블 풀오버", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("v_bar_lat_pulldown", "뉴트럴 그립 랫풀다운", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("mag_grip_lat_pulldown", "맥 그립 랫풀다운", MotionFamily.PULL_RETURN),
        binding("machine_pullover", "머신 풀오버", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("dumbbell_deadlift", "덤벨 데드리프트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("behind_neck_lat_pulldown", "비하인드넥 랫풀다운", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_tbar_row", "머신 티바 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("rope_lat_pulldown", "로프 랫풀다운", MotionFamily.PULL_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),

        // 어깨 — approved 2026-10-19 catalog
        binding("barbell_overhead_press", "바벨 오버헤드 프레스", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("arnold_press", "아놀드 프레스", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_shoulder_press_plate", "머신 숄더 프레스(플레이트식)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_lateral_raise", "머신 레터럴 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_machine_lateral_raise", "원암 머신 레터럴 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("dumbbell_front_raise", "덤벨 프론트 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_front_raise", "케이블 프론트 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_cable_front_raise", "원암 케이블 프론트 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("plate_front_raise", "원판 프론트 레이즈", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("dumbbell_rear_delt_fly", "덤벨 리어델트 플라이", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_rear_delt_fly", "케이블 리어델트 플라이", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_cable_reverse_fly", "원암 케이블 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("face_pull", "페이스풀", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("barbell_shrug", "바벨 슈러그"),
        manual("dumbbell_shrug", "덤벨 슈러그"),
        manual("machine_shrug", "머신 슈러그"),
        binding("upright_row_barbell", "바벨 업라이트 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("upright_row_dumbbell", "덤벨 업라이트 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_upright_row", "케이블 업라이트 로우", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("landmine_shoulder_press", "랜드마인 숄더프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("viking_press", "바이킹 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("push_press", "푸시 프레스"),
        binding("bradford_press", "브래드포드 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cuban_press", "큐반 프레스", MotionFamily.MULTI_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_dumbbell_shoulder_press", "원암 덤벨 숄더프레스", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),

        // 이두 — approved 2026-10-19 catalog
        binding("hammer_curl", "해머 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("preacher_curl_dumbbell", "프리처 컬(덤벨)", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_dumbbell_preacher_curl", "원암 덤벨 프리처 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("concentration_curl", "컨센트레이션 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_curl", "케이블 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_cable_curl", "원암 케이블 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("rope_hammer_curl", "로프 해머 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("reverse_curl", "리버스 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("spider_curl", "스파이더 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("drag_curl", "드래그 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("zottman_curl", "조트맨 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("bayesian_curl", "베이시안 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_bayesian_curl", "원암 베이시안 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cross_body_hammer_curl", "크로스바디 해머 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),

        // 삼두 — approved 2026-10-19 catalog
        binding("reverse_grip_pushdown", "케이블 리버스그립 푸시다운", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_cable_pushdown", "원암 케이블 푸시다운", MotionFamily.TRANSLATION_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("skullcrusher_barbell", "스컬크러셔(바벨)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("skullcrusher_dumbbell", "스컬크러셔(덤벨)", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("close_grip_bench_press", "클로즈그립 벤치프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("diamond_push_up", "다이아몬드 푸쉬업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("kickback_dumbbell", "덤벨 킥백", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_dumbbell_kickback", "원암 덤벨 킥백", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("kickback_cable", "케이블 킥백", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("single_arm_cable_kickback", "원암 케이블 킥백", MotionFamily.FLEXION_EXTENSION, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("jm_press", "JM 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("machine_dip", "머신 딥스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("assisted_dip", "어시스티드 딥스", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("tate_press", "테이트 프레스", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("rolling_triceps_extension", "롤링 트라이셉스 익스텐션", MotionFamily.PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),

        // 하체 — approved 2026-10-19 catalog
        binding("dumbbell_squat", "덤벨 스쿼트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("hack_squat", "핵스쿼트"),
        manual("pendulum_squat", "펜듈럼 스쿼트"),
        binding("box_squat", "박스 스쿼트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("leg_press_horizontal", "수평 레그프레스(핀식)"),
        manual("single_leg_press", "싱글 레그프레스"),
        manual("belt_squat", "벨트 스쿼트"),
        binding("bulgarian_split_squat", "불가리안 스플릿 스쿼트", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("walking_lunge_dumbbell", "워킹 런지(덤벨)", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("walking_lunge_barbell", "워킹 런지(바벨)", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("reverse_lunge", "리버스 런지", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("lateral_lunge", "레터럴 런지", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("curtsy_lunge", "커티시 런지", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("step_up", "스텝업", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("sissy_squat", "시시 스쿼트"),
        binding("zercher_squat", "저처 스쿼트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("overhead_squat", "오버헤드 스쿼트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cossack_squat", "코사크 스쿼트", MotionFamily.VERTICAL_PRESS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("romanian_deadlift_barbell", "루마니안 데드리프트(바벨)", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("romanian_deadlift_dumbbell", "루마니안 데드리프트(덤벨)", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("stiff_leg_deadlift", "스티프 레그 데드리프트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("conventional_deadlift", "컨벤셔널 데드리프트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("sumo_deadlift", "스모 데드리프트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("trap_bar_deadlift", "트랩바 데드리프트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("lying_leg_curl", "라잉 레그컬"),
        manual("standing_leg_curl", "스탠딩 레그컬"),
        manual("nordic_curl", "노르딕 컬"),
        binding("hip_thrust_barbell", "바벨 힙쓰러스트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("hip_thrust_machine", "머신 힙쓰러스트"),
        manual("glute_bridge", "글루트 브릿지"),
        manual("cable_kickback_glute", "케이블 킥백(둔근)"),
        manual("standing_calf_raise", "스탠딩 카프레이즈"),
        manual("seated_calf_raise", "시티드 카프레이즈"),
        manual("leg_press_calf_raise", "레그프레스 카프레이즈"),

        // 코어 — approved 2026-10-19 catalog
        binding("crunch", "크런치", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("cable_crunch", "케이블 크런치", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("hanging_leg_raise", "행잉 레그레이즈"),
        manual("hanging_knee_raise", "행잉 니레이즈"),
        manual("lying_leg_raise", "라잉 레그레이즈"),
        binding("decline_sit_up", "디클라인 싯업", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("russian_twist", "러시안 트위스트", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("machine_torso_rotation", "머신 토르소 로테이션(핀식)"),
        manual("plank", "플랭크"),
        manual("side_plank", "사이드 플랭크"),
        binding("ab_wheel_rollout", "앱휠 롤아웃", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("woodchopper_cable", "케이블 우드찹", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("pallof_press", "팔로프 프레스", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("dead_bug", "데드버그"),
        manual("bicycle_crunch", "바이시클 크런치"),
        manual("hanging_windshield_wiper", "행잉 윈드실드 와이퍼"),
        manual("hollow_hold", "할로우 홀드"),

        // 전완 — approved 2026-10-19 catalog
        manual("wrist_curl", "리스트 컬"),
        manual("reverse_wrist_curl", "리버스 리스트 컬"),
        manual("plate_pinch", "플레이트 핀치"),
        manual("farmers_carry", "파머 캐리"),

        // 전신 — approved 2026-10-19 catalog
        manual("power_clean", "파워 클린"),
        manual("hang_clean", "행 클린"),
        manual("snatch", "스내치"),
        manual("clean_and_press", "클린 앤 프레스"),
        binding("thruster", "쓰러스터", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        binding("kettlebell_swing", "케틀벨 스윙", MotionFamily.CYCLE_AXIS_RETURN, CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS),
        manual("turkish_get_up", "터키시 겟업"),
    )

    private val byExercise = explicitBindings.associateBy { it.exerciseId }
    private val aliases = mapOf(
        "cable_external_rotation" to "external_rotation",
        "cable_external_rot" to "external_rotation",
        "dumbbell_biceps_curl" to "dumbbell_curl",
        "dumbbell_row" to "one_arm_dumbbell_row",
        "rope_pushdown" to "rope_extension",
    )

    fun normalizeExerciseId(exerciseId: String): String {
        val normalized = exerciseId.trim().lowercase()
        return aliases[normalized] ?: normalized
    }

    fun resolve(exerciseId: String, exerciseName: String = ""): ExerciseMotionBinding? {
        val normalizedId = normalizeExerciseId(exerciseId)
        // Older saved workouts did not distinguish pin/plate. Preserve that identity without guessing the machine.
        if (normalizedId == "single_arm_machine_lat_pulldown") return binding(normalizedId, "원암 머신 랫풀다운", MotionFamily.PULL_RETURN)
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
