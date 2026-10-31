package dev.kyungmin.kcoach.core

/**
 * How the library is browsed: body part, then a movement section inside it
 * (vertical pulls, horizontal rows, ...). Exercises whose pattern is unknown
 * land in the last section, "기타".
 */
object ExerciseTaxonomy {
    const val OTHER_ORDER = 99
    const val OTHER_TITLE = "기타"

    private val sections: Map<String, List<Pair<String, String>>> = mapOf(
        "등" to listOf("vertical_pull" to "수직 당기기", "row" to "수평 당기기", "pullover" to "풀오버", "hinge" to "힌지"),
        "가슴" to listOf("chest_press" to "프레스", "chest_fly" to "플라이", "push_up" to "푸시업", "dip" to "딥스", "pullover" to "풀오버"),
        "어깨" to listOf("shoulder_press" to "프레스", "lateral_raise" to "사이드 레이즈", "rear_delt" to "후면 어깨", "front_raise" to "프론트 레이즈", "shrug" to "슈러그", "row" to "업라이트 로우", "rotation" to "회전"),
        "이두" to listOf("curl" to "컬"),
        "삼두" to listOf("triceps_pushdown" to "푸시다운", "triceps_extension" to "익스텐션", "chest_press" to "프레스", "dip" to "딥스", "push_up" to "푸시업"),
        "하체" to listOf("leg_press" to "프레스", "leg_curl" to "컬", "leg_extension" to "익스텐션", "hip" to "힙", "squat" to "스쿼트", "lunge" to "런지·스텝업", "hinge" to "힌지", "calf" to "카프"),
        "코어" to listOf("sit_up" to "싯업", "core_flexion" to "몸통 굽힘", "core_rotation" to "회전", "core_stability" to "코어 안정"),
        "전완" to listOf("forearm" to "전완 운동"),
        "전신" to listOf("full_body" to "전신 운동", "hinge" to "힌지·스윙"),
    )

    /** Body parts in the order the filter chips show them. */
    val bodyOrder = listOf("가슴", "등", "어깨", "삼두", "이두", "하체", "코어", "전완", "전신")

    /** Order and title of the section an exercise belongs to inside its body part. */
    fun section(bodyCategory: String, pattern: String): Pair<Int, String> {
        val list = sections[bodyCategory].orEmpty()
        val index = list.indexOfFirst { it.first == pattern }
        return if (index >= 0) index to list[index].second else OTHER_ORDER to OTHER_TITLE
    }

    /** Movements that count as a main exercise for two body parts and are listed under both. */
    private val sharedBodies = mapOf("dip" to listOf("가슴", "삼두"), "pullover" to listOf("가슴", "등"))

    /** Single exercises that are also listed under another body part (diamond push-ups: triceps tab alone, chest tab with the push-up group). */
    private val sharedExerciseBodies = mapOf("diamond_push_up" to listOf("가슴"), "landmine_press" to listOf("어깨"), "single_arm_landmine_press" to listOf("어깨")) + listOf("romanian_deadlift_barbell", "romanian_deadlift_dumbbell", "stiff_leg_deadlift", "conventional_deadlift", "sumo_deadlift", "trap_bar_deadlift", "dumbbell_deadlift").associateWith { listOf("등") }

    /** Every body part an exercise is listed under, its own first. */
    fun bodies(bodyCategory: String, pattern: String, exerciseId: String = ""): List<String> =
        (listOf(bodyCategory) + sharedBodies[pattern].orEmpty() + sharedExerciseBodies[exerciseId].orEmpty()).distinct()

    fun bodyRank(bodyCategory: String): Int = bodyOrder.indexOf(bodyCategory).let { if (it < 0) bodyOrder.size else it }
}
