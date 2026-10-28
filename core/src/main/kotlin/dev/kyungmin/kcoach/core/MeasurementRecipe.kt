package dev.kyungmin.kcoach.core

/** Shared signal-processing engines. Recipes describe observable movement, not measured anatomy. */
enum class MeasurementEngine { TRANSLATION, ARC, ROTATION, MANUAL }
enum class MusclePhase { CONCENTRIC, ECCENTRIC, UNKNOWN }
enum class StartEvidence { CAMERA_BODY_POSE, WRIST_BASELINE, USER_START }

enum class MeasurementRecipe(
    val label: String,
    val engine: MeasurementEngine,
    val startDescription: String,
    val cycleDescription: String,
    val departurePhase: MusclePhase,
    val cameraLandmarks: Set<Int>,
    val startEvidence: StartEvidence = StartEvidence.WRIST_BASELINE,
) {
    VERTICAL_PULL("랫풀다운", MeasurementEngine.TRANSLATION,
        "팔을 위로 뻗은 기준 위치", "당김 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    ROW("로우", MeasurementEngine.TRANSLATION,
        "선택한 몸통 자세에서 팔을 뻗은 위치", "몸통 쪽 당김 → 전환 → 팔 뻗음 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    CURL("컬", MeasurementEngine.ARC,
        "팔꿈치를 편 기준 위치", "굽힘 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    CHEST_PRESS("가슴 프레스", MeasurementEngine.TRANSLATION,
        "랙 이동 후 팔을 편 기준 위치", "내림 → 전환 → 밀어 기준 위치 복귀", MusclePhase.ECCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    SHOULDER_PRESS("숄더프레스", MeasurementEngine.TRANSLATION,
        "손이나 중량이 어깨 근처인 기준 위치", "올림 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    LATERAL_RAISE("레터럴레이즈", MeasurementEngine.ARC,
        "선택한 기구 조건에서 팔을 내린 위치", "옆으로 벌림 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    FLY("플라이", MeasurementEngine.ARC,
        "선택한 기구의 열림/닫힘 기준 끝점", "반대 끝점 이동 → 전환 → 기준 끝점 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    PULLOVER("풀오버", MeasurementEngine.ARC,
        "팔이나 덤벨이 가슴 위인 기준 위치", "머리 뒤로 이동 → 전환 → 가슴 위 복귀", MusclePhase.ECCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    TRICEPS_EXTENSION("삼두 익스텐션", MeasurementEngine.ARC,
        "선택한 위팔 자세에서 팔꿈치를 굽힌 위치", "팔꿈치 폄 → 전환 → 굽힌 위치 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    EXTERNAL_ROTATION("외회전", MeasurementEngine.ROTATION,
        "팔꿈치를 굽혀 확인한 기준 위치", "바깥 회전 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16)),
    MANUAL("수동 입력", MeasurementEngine.MANUAL,
        "사용자가 세트 시작", "수행 횟수 입력 → 세트 완료", MusclePhase.UNKNOWN,
        emptySet(), StartEvidence.USER_START),
    SQUAT("스쿼트", MeasurementEngine.TRANSLATION,
        "카메라로 확인한 선 자세", "내려감 → 하단 전환 → 선 자세 복귀", MusclePhase.ECCENTRIC,
        setOf(11, 12, 23, 24, 25, 26, 27, 28), StartEvidence.CAMERA_BODY_POSE),
    PUSH_UP("푸시업", MeasurementEngine.TRANSLATION,
        "팔을 편 플랭크 자세", "내려감 → 하단 전환 → 팔을 편 자세 복귀", MusclePhase.ECCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    SIT_UP("싯업", MeasurementEngine.TRANSLATION,
        "누운 자세", "상체 일으킴 → 전환 → 누운 자세 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 23, 24, 25, 26)),
    // Watch-only: the gripped wrist rides with the body, so no camera guide exists for it yet.
    PULL_UP("풀업", MeasurementEngine.TRANSLATION,
        "바를 잡고 팔을 편 매달린 자세", "끌어올림 → 정점 → 매달린 자세 복귀", MusclePhase.CONCENTRIC,
        setOf(11, 12, 13, 14, 15, 16, 23, 24)),
    // Wrist-only catalog recipes: no camera guide or pose counter is available.
    LOADED_HINGE("힙 힌지", MeasurementEngine.TRANSLATION,
        "중량을 잡은 기준 위치", "몸통 숙임 → 전환 → 기준 위치 복귀", MusclePhase.ECCENTRIC, emptySet()),
    LOADED_LUNGE("런지·스텝업", MeasurementEngine.TRANSLATION,
        "양발을 지지한 기준 위치", "한쪽 다리 이동·굽힘 → 전환 → 기준 위치 복귀", MusclePhase.UNKNOWN, emptySet()),
    HIP_EXTENSION("힙쓰러스트", MeasurementEngine.TRANSLATION,
        "골반을 낮춘 기준 위치", "골반 올림 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC, emptySet()),
    CORE_FLEXION("코어 굽힘", MeasurementEngine.TRANSLATION,
        "몸통을 편 기준 위치", "몸통 굽힘 → 전환 → 기준 위치 복귀", MusclePhase.CONCENTRIC, emptySet()),
    CORE_ROTATION("코어 회전", MeasurementEngine.TRANSLATION,
        "선택한 방향의 기준 위치", "반대 방향 회전 → 전환 → 기준 위치 복귀", MusclePhase.UNKNOWN, emptySet()),
    CORE_EXTENSION("코어 왕복", MeasurementEngine.TRANSLATION,
        "팔을 몸통 가까이 둔 기준 위치", "팔 뻗음 → 전환 → 기준 위치 복귀", MusclePhase.UNKNOWN, emptySet()),
    DIP("딥스", MeasurementEngine.TRANSLATION,
        "팔을 편 지지 위치", "내려감 → 전환 → 팔을 편 위치 복귀", MusclePhase.ECCENTRIC, emptySet()),
    SQUAT_PRESS("스쿼트 프레스", MeasurementEngine.TRANSLATION,
        "중량을 어깨에 둔 선 자세", "스쿼트 → 일어서며 밀기 → 기준 위치 복귀", MusclePhase.UNKNOWN, emptySet()),

    ;

    /** Counting one observed wrist does not require the other arm or every posture metric. */
    fun referenceLandmarks(left: Boolean): Set<Int> {
        val shoulder = if (left) 11 else 12
        val elbow = if (left) 13 else 14
        val wrist = if (left) 15 else 16
        val hip = if (left) 23 else 24
        return when (this) {
            SQUAT -> cameraLandmarks
            LATERAL_RAISE -> setOf(shoulder, elbow, wrist, hip)
            MANUAL, LOADED_HINGE, LOADED_LUNGE, HIP_EXTENSION, CORE_FLEXION, CORE_ROTATION, CORE_EXTENSION, DIP, SQUAT_PRESS -> emptySet()
            else -> setOf(shoulder, elbow, wrist)
        }
    }
}

data class ExerciseMeasurementContract(
    val recipe: MeasurementRecipe,
    val conditionDescription: String,
    val departurePhase: MusclePhase = recipe.departurePhase,
    val startDescription: String = recipe.startDescription,
    val cycleDescription: String = recipe.cycleDescription,
)

/** Explicit reviewed contracts. Unknown names never become a validated automatic recipe. */
object ExerciseMeasurementContracts {
    internal val standard: Map<String, ExerciseMeasurementContract> = reviewedContracts()

    fun explanation(exerciseId: String, exerciseName: String = ""): String {
        val contract = resolve(exerciseId)
        val manual = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName).mode == TrackingMode.MANUAL_REQUIRED
        return buildString {
            appendLine(if (manual) "측정 방식 · 수동 입력" else "측정 방식 · ${contract.recipe.label} 공통 동작")
            appendLine()
            if (manual) {
                appendLine("손목 신호로 이 운동의 수행 횟수를 확인하지 않아요. 실제 수행 횟수를 입력하고 세트를 완료해 주세요.")
            } else {
                appendLine("시작 기준 · ${contract.startDescription}")
                appendLine("1회 기준 · ${contract.cycleDescription}")
                appendLine("손목 신호의 자동 추정이며 실제 수행 횟수 확인이 필요해요.")
            }
            appendLine()
            appendLine("동작 조건 · ${contract.conditionDescription}")
            append("손목만으로 전신 자세의 정확도를 판정하지 않아요.")
        }
    }

    fun resolve(exerciseId: String): ExerciseMeasurementContract {
        val id = ExerciseMotionCatalog.normalizeExerciseId(exerciseId)
        return standard[id] ?: when (id) {
            "squat", "barbell_squat", "front_squat", "goblet_squat", "smith_squat" -> ExerciseMeasurementContract(
                MeasurementRecipe.SQUAT, "전신 카메라 확인 필요. 워치는 손목 주기 신호로 자동 추정해요.")
            "push_up" -> ExerciseMeasurementContract(MeasurementRecipe.PUSH_UP,
                "맨몸 푸시업. 손목이 바닥에 고정돼 워치는 전완 기울기 변화를 읽어요. 카메라 보정으로 횟수를 확인하세요.")
            "sit_up" -> ExerciseMeasurementContract(MeasurementRecipe.SIT_UP,
                "누운 자세에서 상체를 일으키는 싯업. 카메라 보정으로 횟수를 확인하세요.")
            "high_row_machine", "standing_tbar_row" -> ExerciseMeasurementContract(
                MeasurementRecipe.ROW, "기구의 손잡이 방향·피벗·몸통 지지를 확인해야 하는 추가 검증 대상.")
            "barbell_shoulder_press" -> ExerciseMeasurementContract(
                MeasurementRecipe.SHOULDER_PRESS, "바벨 그립·서기/앉기 조건. 자동 측정은 별도 검증 필요.")
            else -> ExerciseMeasurementContract(MeasurementRecipe.MANUAL,
                "명시적인 운동 레시피와 검증 자료가 없어 수동으로 입력해 주세요.")
        }
    }
}
