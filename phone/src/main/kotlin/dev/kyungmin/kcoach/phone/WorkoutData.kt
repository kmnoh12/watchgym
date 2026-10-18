package dev.kyungmin.kcoach.phone

enum class SetType(
    val storageValue: String,
    val displayName: String,
) {
    NORMAL("NORMAL", "일반"),
    WARMUP("WARMUP", "웜업"),
    SUPER("SUPER", "슈퍼"),
    DROP("DROP", "드랍"),
    FAILURE("FAILURE", "실패"),
    ASSISTED("ASSISTED", "보조");

    companion object {
        fun fromStorage(value: String?): SetType = entries.firstOrNull {
            it.storageValue == value || it.displayName == value
        } ?: NORMAL
    }
}

data class PlannedSet(
    var weightKg: Double?,
    var targetReps: Int,
    var completed: Boolean = false,
    var setType: SetType = SetType.NORMAL,
    var gripType: String = "노멀",
    var rpe: String? = null,
    /** Rest after this set; null uses the exercise default. */
    var restSeconds: Int? = null,
    var performedReps: Int? = null,
    var repSource: dev.kyungmin.kcoach.core.SetRepSource? = null,
    val setId: String = java.util.UUID.randomUUID().toString(),
    var attemptId: String = java.util.UUID.randomUUID().toString(),
    var identityRequired: Boolean = true,
)

fun List<PlannedSet>.workSetLabel(index: Int): String {
    val set = getOrNull(index) ?: return ""
    if (set.setType == SetType.WARMUP) return "W"
    return take(index + 1).count { it.setType != SetType.WARMUP }.toString()
}

fun List<PlannedSet>.workSetCount(): Int = count { it.setType != SetType.WARMUP }

fun List<PlannedSet>.completedWorkSetCount(): Int = count {
    it.setType != SetType.WARMUP && it.completed
}

fun adjustedIndexAfterMove(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < to && current in (from + 1)..to -> current - 1
    to < from && current in to until from -> current + 1
    else -> current
}

data class ExerciseDefinition(
    val id: String,
    val name: String,
    val muscles: String,
    val bodyCategory: String,
    val equipment: String,
    val favorite: Boolean = false,
    /** Movement family used to suggest replacements; empty for custom exercises. */
    val pattern: String = "",
    /** Exercises of the same family (all lat pulldown grips) are folded into one row in the library. */
    val family: String = "",
)

data class RoutineExercise(
    val exercise: ExerciseDefinition,
    val sets: MutableList<PlannedSet>,
    var restSeconds: Int = 90,
)

data class WorkoutRoutine(
    val id: String,
    val name: String,
    val muscles: String,
    var lastPerformed: String,
    val exercises: MutableList<RoutineExercise>,
    val lastPerformedEpochDay: Long? = null,
)

data class WorkoutRecord(
    val date: String,
    val title: String,
    val minutes: Int,
    val sets: Int,
    val volumeKg: Int,
)

object WorkoutData {
    private fun exercise(
        id: String,
        name: String,
        muscles: String,
        body: String,
        equipment: String,
        favorite: Boolean = false,
    ) = ExerciseDefinition(id, name, muscles, body, equipment, favorite, patterns[id].orEmpty(), families[id].orEmpty())

    private val patterns = mapOf(
        "pull_up" to "vertical_pull", "assisted_pull_up" to "vertical_pull", "lat_pull_down" to "vertical_pull",
        "wide_lat_pull_down" to "vertical_pull", "close_lat_pull_down" to "vertical_pull", "reverse_lat_pull_down" to "vertical_pull", "one_arm_lat_pull_down" to "vertical_pull",
        "lying_tbar_row" to "row", "seated_row" to "row", "barbell_row" to "row", "one_arm_dumbbell_row" to "row", "cable_seated_row" to "row",
        "ez_biceps_curl" to "curl", "barbell_biceps_curl" to "curl", "preacher_curl" to "curl", "incline_curl" to "curl",
        "incline_hammer_curl" to "curl", "dumbbell_curl" to "curl",
        "flat_bench" to "chest_press", "incline_bench" to "chest_press", "chest_press" to "chest_press", "dumbbell_bench" to "chest_press",
        "machine_fly" to "chest_fly", "dumbbell_pullover" to "pullover",
        "cable_pushdown" to "triceps_pushdown", "rope_extension" to "triceps_pushdown",
        "cable_overhead_extension" to "triceps_extension", "rope_overhead_extension" to "triceps_extension",
        "dumbbell_triceps_extension" to "triceps_extension", "ez_lying_extension" to "triceps_extension",
        "smith_shoulder_press" to "shoulder_press", "machine_shoulder_press" to "shoulder_press", "dumbbell_shoulder_press" to "shoulder_press",
        "dumbbell_lateral_raise" to "lateral_raise", "one_arm_cable_lateral" to "lateral_raise",
        "crossover_reverse_fly" to "rear_delt", "machine_reverse_fly" to "rear_delt",
        "external_rotation" to "rotation",
        "push_up" to "push_up", "sit_up" to "sit_up",
        "squat" to "squat", "barbell_squat" to "squat", "front_squat" to "squat", "goblet_squat" to "squat", "smith_squat" to "squat",
        "leg_press" to "leg_press", "seated_leg_curl" to "leg_curl", "leg_extension" to "leg_extension",
        "hip_adduction" to "hip", "hip_abduction" to "hip",
    )

    private val families = mapOf(
        "lat_pull_down" to "랫 풀 다운", "wide_lat_pull_down" to "랫 풀 다운", "close_lat_pull_down" to "랫 풀 다운",
        "reverse_lat_pull_down" to "랫 풀 다운", "one_arm_lat_pull_down" to "랫 풀 다운",
        "pull_up" to "풀업", "assisted_pull_up" to "풀업",
        "seated_row" to "시티드 로우", "cable_seated_row" to "시티드 로우",
        "flat_bench" to "벤치 프레스", "incline_bench" to "벤치 프레스", "dumbbell_bench" to "벤치 프레스",
        "smith_shoulder_press" to "숄더 프레스", "machine_shoulder_press" to "숄더 프레스", "dumbbell_shoulder_press" to "숄더 프레스",
        "ez_biceps_curl" to "바이셉 컬", "barbell_biceps_curl" to "바이셉 컬", "dumbbell_curl" to "바이셉 컬",
        "incline_curl" to "바이셉 컬", "incline_hammer_curl" to "바이셉 컬", "preacher_curl" to "바이셉 컬",
        "cable_pushdown" to "트라이셉 푸시다운", "rope_extension" to "트라이셉 푸시다운",
    )

    val catalog = mutableListOf(
        exercise("pull_up", "풀업", "등, 이두", "등", "맨몸", true),
        exercise("assisted_pull_up", "어시스티드 머신 풀업", "등, 이두", "등", "머신"),
        exercise("lying_tbar_row", "머신 라잉 티바 로우", "등, 이두", "등", "머신", true),
        exercise("lat_pull_down", "랫 풀 다운", "등, 이두", "등", "머신", true),
        exercise("wide_lat_pull_down", "와이드 그립 랫 풀 다운", "등, 이두", "등", "머신"),
        exercise("close_lat_pull_down", "내로우 그립 랫 풀 다운", "등, 이두", "등", "머신"),
        exercise("reverse_lat_pull_down", "언더 그립 랫 풀 다운", "등, 이두", "등", "머신"),
        exercise("one_arm_lat_pull_down", "원 암 랫 풀 다운", "등, 이두", "등", "케이블"),
        exercise("seated_row", "머신 시티드 로우", "등, 이두", "등", "머신", true),
        exercise("cable_seated_row", "케이블 시티드 로우", "등, 이두", "등", "케이블"),
        exercise("barbell_row", "바벨 로우", "등, 이두", "등", "바벨"),
        exercise("one_arm_dumbbell_row", "원 암 덤벨 로우", "등, 이두", "등", "덤벨"),
        exercise("ez_biceps_curl", "이지바 바이셉 컬", "이두", "이두", "바벨", true),
        exercise("barbell_biceps_curl", "바벨 바이셉 컬", "이두", "이두", "바벨"),
        exercise("preacher_curl", "머신 프리처 컬", "이두", "이두", "머신", true),
        exercise("incline_curl", "덤벨 인클라인 컬", "이두", "이두", "덤벨", true),
        exercise("incline_hammer_curl", "덤벨 인클라인 해머 컬", "이두, 전완", "이두", "덤벨", true),
        exercise("dumbbell_curl", "덤벨 바이셉 컬", "이두", "이두", "덤벨"),
        exercise("dumbbell_pullover", "라잉 덤벨 풀오버", "가슴, 등", "가슴", "덤벨", true),
        exercise("flat_bench", "바벨 플랫 벤치 프레스", "가슴, 삼두", "가슴", "바벨", true),
        exercise("incline_bench", "바벨 인클라인 벤치 프레스", "가슴, 삼두", "가슴", "바벨", true),
        exercise("chest_press", "머신 체스트 프레스", "가슴, 삼두", "가슴", "머신"),
        exercise("dumbbell_bench", "덤벨 벤치 프레스", "가슴, 삼두", "가슴", "덤벨"),
        exercise("machine_fly", "머신 플라이", "가슴", "가슴", "머신", true),
        exercise("cable_pushdown", "케이블 트라이셉스 푸시 다운", "삼두", "삼두", "케이블", true),
        exercise("cable_overhead_extension", "케이블 오버헤드 트라이셉 익스텐션", "삼두", "삼두", "케이블", true),
        exercise("rope_extension", "케이블 로프 트라이셉 익스텐션", "삼두", "삼두", "케이블", true),
        exercise("rope_overhead_extension", "케이블 로프 오버헤드 트라이셉 익스텐션", "삼두", "삼두", "케이블"),
        exercise("dumbbell_triceps_extension", "덤벨 트라이셉스 익스텐션", "삼두", "삼두", "덤벨", true),
        exercise("ez_lying_extension", "이지바 라잉 트라이셉스 익스텐션", "삼두", "삼두", "바벨"),
        exercise("external_rotation", "케이블 익스터널 로테이션", "어깨", "어깨", "케이블"),
        exercise("crossover_reverse_fly", "케이블 크로스오버 리버스 플라이", "어깨, 등", "어깨", "케이블"),
        exercise("smith_shoulder_press", "스미스 머신 오버헤드 숄더 프레스", "어깨, 삼두", "어깨", "머신", true),
        exercise("machine_shoulder_press", "머신 숄더 프레스", "어깨, 삼두", "어깨", "머신", true),
        exercise("dumbbell_shoulder_press", "덤벨 숄더 프레스", "어깨, 삼두", "어깨", "덤벨"),
        exercise("dumbbell_lateral_raise", "덤벨 레터럴 레이즈", "어깨", "어깨", "덤벨", true),
        exercise("one_arm_cable_lateral", "원 암 케이블 레터럴 레이즈", "어깨", "어깨", "케이블", true),
        exercise("machine_reverse_fly", "머신 리버스 플라이", "어깨, 등", "어깨", "머신", true),
        exercise("leg_press", "머신 레그 프레스", "하체", "하체", "머신", true),
        exercise("seated_leg_curl", "시티드 레그 컬", "하체", "하체", "머신", true),
        exercise("leg_extension", "레그 익스텐션", "하체", "하체", "머신", true),
        exercise("hip_adduction", "힙 어덕션", "하체", "하체", "머신"),
        exercise("hip_abduction", "힙 어브덕션", "하체", "하체", "머신"),
        exercise("push_up", "푸시업", "가슴, 삼두", "가슴", "맨몸"),
        exercise("sit_up", "싯업", "복근", "복근", "맨몸"),
        exercise("squat", "스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "맨몸"),
        exercise("barbell_squat", "백 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "바벨"),
        exercise("front_squat", "프런트 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "바벨"),
        exercise("goblet_squat", "고블릿 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "덤벨"),
        exercise("smith_squat", "스미스 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "머신"),
    )

    val builtInExerciseIds: Set<String> = catalog.map { it.id }.toSet()

    private fun ex(id: String) = checkNotNull(catalog.find { it.id == id })

    private fun sets(vararg values: Pair<Double?, Int>) =
        values.mapTo(mutableListOf()) { PlannedSet(weightKg = it.first, targetReps = it.second) }

    fun defaultRoutines(): MutableList<WorkoutRoutine> = mutableListOf(
        WorkoutRoutine(
            id = "shoulders",
            name = "어깨전문",
            muscles = "어깨, 등",
            lastPerformed = "기록 없음",
            exercises = mutableListOf(
                RoutineExercise(ex("external_rotation"), sets(5.0 to 25, 5.0 to 25), 45),
                RoutineExercise(ex("crossover_reverse_fly"), sets(10.0 to 20), 60),
                RoutineExercise(ex("smith_shoulder_press"), sets(30.0 to 20, 30.0 to 10, 30.0 to 10), 120),
                RoutineExercise(ex("machine_shoulder_press"), sets(27.0 to 20, 45.0 to 12, 45.0 to 10, 54.0 to 7), 120),
                RoutineExercise(ex("dumbbell_lateral_raise"), sets(10.0 to 15, 7.0 to 20, 5.0 to 20, 7.0 to 10, 5.0 to 15, 3.0 to 40), 60),
                RoutineExercise(ex("one_arm_cable_lateral"), sets(5.0 to 20, 5.0 to 20, 5.0 to 20), 60),
                RoutineExercise(ex("machine_reverse_fly"), sets(20.0 to 20, 30.0 to 15, 30.0 to 15, 40.0 to 10), 75),
            ),
        ),
        WorkoutRoutine(
            id = "back_biceps",
            name = "등·이두",
            muscles = "등, 이두",
            lastPerformed = "기록 없음",
            exercises = mutableListOf(
                RoutineExercise(ex("pull_up"), sets(null to 10, null to 8), 120),
                RoutineExercise(ex("assisted_pull_up"), sets(50.0 to 10, 30.0 to 10), 90),
                RoutineExercise(ex("lying_tbar_row"), sets(40.0 to 12, 40.0 to 12, 40.0 to 12, 55.0 to 7, 55.0 to 7), 120),
                RoutineExercise(ex("lat_pull_down"), sets(40.0 to 12, 47.0 to 10, 55.0 to 10, 55.0 to 8, 40.0 to 12), 90),
                RoutineExercise(ex("seated_row"), sets(50.0 to 12, 60.0 to 10, 68.0 to 10), 90),
                RoutineExercise(ex("ez_biceps_curl"), sets(20.0 to 12, 20.0 to 10, 25.0 to 5), 75),
                RoutineExercise(ex("preacher_curl"), sets(22.0 to 12, 18.0 to 12), 60),
            ),
        ),
        WorkoutRoutine(
            id = "chest_triceps",
            name = "가슴·삼두",
            muscles = "가슴, 삼두",
            lastPerformed = "기록 없음",
            exercises = mutableListOf(
                RoutineExercise(ex("dumbbell_pullover"), sets(15.0 to 10, 15.0 to 10), 60),
                RoutineExercise(ex("flat_bench"), sets(20.0 to 15, 60.0 to 12, 60.0 to 12, 60.0 to 12, 60.0 to 12), 150),
                RoutineExercise(ex("incline_bench"), sets(50.0 to 12, 50.0 to 12, 50.0 to 12, 50.0 to 12), 120),
                RoutineExercise(ex("machine_fly"), sets(25.0 to 15, 30.0 to 12, 40.0 to 10, 40.0 to 10), 75),
                RoutineExercise(ex("cable_pushdown"), sets(35.0 to 12, 45.0 to 10, 45.0 to 10, 45.0 to 10), 75),
                RoutineExercise(ex("cable_overhead_extension"), sets(25.0 to 12, 25.0 to 12, 30.0 to 10), 75),
            ),
        ),
        WorkoutRoutine(
            id = "arms_legs",
            name = "팔·하체",
            muscles = "이두, 삼두, 하체",
            lastPerformed = "기록 없음",
            exercises = mutableListOf(
                RoutineExercise(ex("pull_up"), sets(null to 10, null to 8, null to 8), 120),
                RoutineExercise(ex("ez_biceps_curl"), sets(20.0 to 12, 25.0 to 10, 30.0 to 7, 20.0 to 12), 75),
                RoutineExercise(ex("incline_curl"), sets(10.0 to 12, 10.0 to 12), 60),
                RoutineExercise(ex("incline_hammer_curl"), sets(10.0 to 12, 10.0 to 12), 60),
                RoutineExercise(ex("dumbbell_triceps_extension"), sets(18.0 to 15, 18.0 to 12, 20.0 to 10, 20.0 to 10), 75),
                RoutineExercise(ex("rope_extension"), sets(25.0 to 12, 20.0 to 15, 20.0 to 15), 60),
                RoutineExercise(ex("leg_press"), sets(77.1 to 12, 104.3 to 10, 113.4 to 10, 131.5 to 7, 131.5 to 7), 150),
                RoutineExercise(ex("seated_leg_curl"), sets(36.0 to 12, 45.0 to 10, 45.0 to 10), 90),
                RoutineExercise(ex("leg_extension"), sets(40.0 to 15, 59.0 to 10, 68.0 to 8), 90),
            ),
        ),
    )
}

/** Recompute relative dates when rendering instead of persisting a stale relative label. */
internal fun WorkoutRoutine.lastPerformedLabel(today: java.time.LocalDate): String {
    val days = lastPerformedEpochDay?.let { today.toEpochDay() - it }
    return when {
        days == null -> "기록 없음"
        days <= 0 -> "오늘"
        else -> "${days}일 전"
    }
}
