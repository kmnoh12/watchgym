package dev.kyungmin.kcoach.core

/** Anatomical MediaPipe indices, independent of the mirrored selfie preview. Guides are not accuracy claims. */
data class CameraCaptureGuide(val id: String, val direction: String, val framing: String, val required: Set<Int>,
    val startDescription: String, val metric: CameraMotion?, val readyElbowBand: ClosedFloatingPointRange<Double>? = null,
    val headline: String = direction, val startHint: String? = null,
    val viewTarget: CameraViewTarget? = null,
    /** Joints the pose itself hides (a wrist behind the head); they count as seen at [OCCLUDED_MIN_CONFIDENCE]. */
    val occluded: Set<Int> = emptySet()) {
    private fun minConfidence(index: Int) = if (index in occluded) OCCLUDED_MIN_CONFIDENCE else MIN_CONFIDENCE
    /** How long the start pose must hold before capture may start; without an angle check it needs a steadier hold. */
    val readyHoldMs: Long get() = if (metric == null) 1000L else 200L

    /** Start-pose readiness: the movement metric at rest, or for rotation lifts only the elbow set-up. */
    fun startReady(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean): Boolean =
        diagnose(image, world, left).ready

    /** Elbow angle (shoulder-elbow-wrist) in world coordinates, the start check for rotation lifts that have no movement metric. */
    private fun elbowSetupAngle(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean): Double? {
        if (world.size != 33 || image.size != 33) return null
        val points = world.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z, minOf(image[i].visibility ?: 0.0, image[i].presence ?: 0.0)) }
        return UpperBodyPoseGeometry.angle(points, if (left) 11 else 12, if (left) 13 else 14, if (left) 15 else 16)
    }
    fun visible(points: List<CameraLandmark>): Boolean = required.isNotEmpty() && required.all { index ->
        val point = points.getOrNull(index) ?: return@all false
        minOf(point.visibility ?: 0.0, point.presence ?: 0.0) >= minConfidence(index) && inFrame(point)
    }

    /** Why [startReady] says yes or no, joint by joint, for the on-screen debug overlay and offline replays. */
    fun diagnose(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean,
        orientation: CameraOrientation? = viewTarget?.let { CameraTorsoOrientation.evaluate(image, world, left, it) }): StartDiagnosis {
        val joints = required.sorted().map { index ->
            val point = image.getOrNull(index)
            StartJoint(index, point?.visibility, point?.presence, point?.let(::inFrame) == true,
                point != null && minOf(point.visibility ?: 0.0, point.presence ?: 0.0) >= minConfidence(index) && inFrame(point))
        }
        val seen = image.size == 33 && world.size == 33 && joints.all { it.ok }
        val angle = if (!seen) null else if (metric != null) observedMetric(image, world, left) else if (readyElbowBand != null) elbowSetupAngle(image, world, left) else null
        val atRest = angle?.let { value -> metric?.atRest(value) ?: readyElbowBand?.let { value in it } }
        val ready = seen && (orientation?.accepted != false) && when {
            metric != null || readyElbowBand != null -> atRest == true
            else -> true
        }
        return StartDiagnosis(joints, angle, atRest, ready, readyElbowBand, orientation)
    }

    private fun inFrame(point: CameraLandmark) = point.x in 0.02..0.98 && point.y in 0.02..0.98
    fun observedMetric(image: List<CameraLandmark>, world: List<CameraLandmark>, left: Boolean): Double? {
        val motion = metric ?: return null
        if (image.size != 33 || world.size != 33 || !visible(image)) return null
        // The required joints just passed [visible]; a hidden-by-pose joint is trusted at its lower bar.
        val points = world.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z,
            if (i in occluded) 1.0 else minOf(image[i].visibility ?: 0.0, image[i].presence ?: 0.0,
                p.visibility ?: 1.0, p.presence ?: 1.0)) }
        val value = UpperBodyPoseGeometry.metric(points, motion, left) ?: return null
        if (motion == CameraMotion.SQUAT_KNEE_FLEXION && motion.atRest(value)) {
            val shoulderY = (image[11].y + image[12].y) / 2
            val hipY = (image[23].y + image[24].y) / 2
            if (!(image[0].y < shoulderY && shoulderY < hipY &&
                hipY < image[if (left) 25 else 26].y && image[if (left) 25 else 26].y < image[if (left) 27 else 28].y)) return null
        }
        return value
    }
    companion object {
        /** Minimum min(visibility, presence) for a required joint to count as seen. */
        const val MIN_CONFIDENCE = 0.65
        /** Wrist behind the head in overhead extensions: MediaPipe still places it, only with low confidence. */
        const val OCCLUDED_MIN_CONFIDENCE = 0.25

        fun forExercise(id: String, left: Boolean): CameraCaptureGuide {
            val contract = ExerciseMeasurementContracts.resolve(id)
            val recipe = contract.recipe
            val target = CameraOrientationPolicy.target(id)
            val side = if (left) "왼팔" else "오른팔"
            val shoulder = if (left) 11 else 12; val elbow = if (left) 13 else 14
            val wrist = if (left) 15 else 16; val hip = if (left) 23 else 24
            val required = CameraOrientationPolicy.torsoJoints + when {
                recipe == MeasurementRecipe.MANUAL && id in setOf("leg_press", "seated_leg_curl", "leg_extension") -> setOf(hip, if (left) 25 else 26, if (left) 27 else 28)
                recipe == MeasurementRecipe.MANUAL && id in setOf("hip_adduction", "hip_abduction") -> setOf(23, 24, 25, 26, 27, 28)
                recipe == MeasurementRecipe.MANUAL -> setOf(11, 12, 15, 16, 23, 24, 27, 28)
                recipe == MeasurementRecipe.SQUAT -> recipe.cameraLandmarks + 0
                recipe == MeasurementRecipe.SIT_UP -> setOf(shoulder, hip, if (left) 25 else 26)
                recipe == MeasurementRecipe.FLY -> setOf(shoulder, elbow, wrist, if (left) 12 else 11)
                recipe == MeasurementRecipe.EXTERNAL_ROTATION -> setOf(shoulder, elbow, wrist)
                // Elbow metrics use three arm joints; the shared yaw gate additionally requires both hips and shoulders.
                recipe in setOf(MeasurementRecipe.CURL, MeasurementRecipe.ROW, MeasurementRecipe.VERTICAL_PULL,
                    MeasurementRecipe.SHOULDER_PRESS, MeasurementRecipe.TRICEPS_EXTENSION, MeasurementRecipe.CHEST_PRESS) ->
                    setOf(shoulder, elbow, wrist)
                else -> setOf(shoulder, elbow, wrist, hip)
            }
            val direction = "${target.label} · ${if (target.nearArm) "$side 쪽을 카메라 가까이에 두고 " else ""}양어깨와 골반, 수행 관절이 보이게 해 주세요"
            val metric = when (recipe) {
                MeasurementRecipe.CURL, MeasurementRecipe.ROW, MeasurementRecipe.VERTICAL_PULL -> CameraMotion.ELBOW_FOLD
                MeasurementRecipe.SHOULDER_PRESS, MeasurementRecipe.TRICEPS_EXTENSION -> CameraMotion.ELBOW_PRESS
                MeasurementRecipe.CHEST_PRESS -> if (id == "chest_press") CameraMotion.ELBOW_PRESS else CameraMotion.ELBOW_FOLD
                MeasurementRecipe.LATERAL_RAISE -> CameraMotion.SHOULDER_RAISE
                MeasurementRecipe.SQUAT -> CameraMotion.SQUAT_KNEE_FLEXION
                MeasurementRecipe.PUSH_UP -> CameraMotion.ELBOW_FOLD
                MeasurementRecipe.SIT_UP -> CameraMotion.SITUP_HIP_FLEXION
                MeasurementRecipe.PULLOVER -> CameraMotion.PULLOVER_REACH
                MeasurementRecipe.FLY -> if (id == "machine_fly") CameraMotion.FLY_OPEN else CameraMotion.FLY_CLOSED
                else -> null
            }
            return CameraCaptureGuide("camera-$id-v2", direction,
                "폰을 고정해 보이는 관절이 화면 가장자리 밖으로 나가지 않게 맞춰요. 셀카 화면은 반전돼도 $side 해부학적 관절을 관측해요.",
                required, contract.startDescription + ". 관측 범위 밖 자세는 평가하지 않아요.", metric,
                // External rotation turns the forearm about the upper arm, which a phone camera cannot
                // judge reliably; it only checks the elbow is set at about 90 degrees beside the body.
                if (recipe == MeasurementRecipe.EXTERNAL_ROTATION) 75.0..105.0 else null,
                brief(id, recipe, left), startHint(id, recipe), target,
                if (id in HIDDEN_WRIST_IDS) setOf(wrist) else emptySet())
        }

        private val HIDDEN_WRIST_IDS = setOf("cable_overhead_extension", "rope_overhead_extension", "dumbbell_triceps_extension")

        /** One short line: which way to face the camera and what must be in frame. */
        private fun brief(id: String, recipe: MeasurementRecipe, left: Boolean): String {
            val arm = if (left) "왼팔" else "오른팔"
            val target = CameraOrientationPolicy.target(id)
            return "${target.label} · ${if (recipe == MeasurementRecipe.SQUAT || recipe == MeasurementRecipe.MANUAL) "전신이 보이게" else "골반과 ${arm}이 보이게"}"
        }

        /** The start pose in a few words; the pictogram shows the rest. */
        private fun startHint(id: String, recipe: MeasurementRecipe): String? = when (recipe) {
            MeasurementRecipe.CURL -> when (id) {
                "preacher_curl" -> "패드에 팔을 올리고 편 상태에서 시작"
                "incline_curl", "incline_hammer_curl" -> "등을 기대고 팔을 내린 상태에서 시작"
                else -> "팔을 쭉 내린 상태에서 시작"
            }
            MeasurementRecipe.ROW -> if (id in setOf("seated_row", "cable_seated_row", "high_row_machine")) "팔을 앞으로 뻗고 시작"
                else "상체를 숙이고 팔을 내린 상태에서 시작"
            MeasurementRecipe.VERTICAL_PULL -> "팔을 위로 쭉 뻗고 시작"
            MeasurementRecipe.CHEST_PRESS -> if (id == "chest_press") "손잡이를 가슴 옆에 두고 시작" else "누워서 팔을 위로 편 상태에서 시작"
            MeasurementRecipe.SHOULDER_PRESS -> "손을 어깨 높이에 두고 시작"
            MeasurementRecipe.LATERAL_RAISE -> "팔을 내린 상태에서 시작"
            MeasurementRecipe.FLY -> if (id == "machine_fly") "팔을 옆으로 벌리고 시작" else "팔을 앞으로 모으고 시작"
            MeasurementRecipe.PULLOVER -> "누워서 팔을 머리 뒤로 뻗고 시작"
            MeasurementRecipe.TRICEPS_EXTENSION -> when (id) {
                "cable_overhead_extension", "rope_overhead_extension", "dumbbell_triceps_extension" -> "팔을 머리 위로 들고 팔꿈치를 굽힌 채 시작"
                "ez_lying_extension" -> "누워서 팔꿈치를 굽힌 채 시작"
                else -> "팔꿈치를 굽혀 옆구리에 붙이고 시작"
            }
            MeasurementRecipe.EXTERNAL_ROTATION -> "팔꿈치를 90°로 굽혀 옆구리에 붙이고 시작"
            MeasurementRecipe.SQUAT -> "곧게 선 상태에서 시작"
            MeasurementRecipe.PUSH_UP -> "팔을 편 플랭크 자세에서 시작"
            MeasurementRecipe.SIT_UP -> "무릎을 세우고 누운 상태에서 시작"
            MeasurementRecipe.MANUAL -> null
        }
    }
}

/** Proposed boundaries remain CAMERA_CANDIDATE until the user reviews actual frames. */
object CameraRepProposals {
    fun propose(frames: List<CameraPoseFrame>, exerciseId: String, left: Boolean): List<RepCycle> {
        val guide = CameraCaptureGuide.forExercise(exerciseId, left)
        val motion = guide.metric ?: return emptyList()
        var start: Long? = null; var turn: Long? = null; var extreme = 0.0; var departed = false
        var stableSince: Long? = null; var last = -1L
        val rows = mutableListOf<RepCycle>()
        fun reset() { start = null; turn = null; departed = false; stableSince = null }
        frames.forEach { frame ->
            val ns = frame.cameraTimestampNs
            if (ns <= last || last >= 0 && ns - last > 500_000_000L) reset()
            last = ns
            val image = frame.imageLandmarks.singleOrNull()
            val world = frame.worldLandmarks.singleOrNull()
            if (!frame.uniquelyVisible || !frame.videoFrameWritten || image == null || world == null || !guide.visible(image) ||
                guide.viewTarget?.let { !CameraTorsoOrientation.evaluate(image, world, left, it).accepted } == true) { reset(); return@forEach }
            val value = guide.observedMetric(image, world, left) ?: run { reset(); return@forEach }
            if (start == null) {
                if (motion.atRest(value)) {
                    if (stableSince == null) stableSince = ns
                    if (ns - stableSince!! >= 200_000_000L) { start = ns; extreme = value }
                } else stableSince = null
                return@forEach
            }
            // READY is permission to observe, not the start of the repetition.
            // Retain the last actually observed resting frame before movement.
            if (!departed && motion.atRest(value)) { start = ns; extreme = value; return@forEach }
            if (!departed && !motion.atRest(value)) { departed = true; turn = ns; extreme = value }
            if (departed && (if (motion.increasing) value > extreme else value < extreme)) { extreme = value; turn = ns }
            if (departed && motion.atRest(value)) {
                val first = start!!; val middle = turn!!
                if (motion.departed(extreme) && middle > first && ns > middle && ns - first in 350_000_000L..12_000_000_000L) {
                    rows.add(RepCycle(first, middle, ns, ExerciseMeasurementContracts.resolve(exerciseId).departurePhase))
                }
                start = ns; turn = null; departed = false; extreme = value
            }
        }
        return rows
    }
}

data class StartJoint(val index: Int, val visibility: Double?, val presence: Double?, val inFrame: Boolean, val ok: Boolean) {
    val confidence: Double get() = minOf(visibility ?: 0.0, presence ?: 0.0)
}

/** [angle] is the guide's movement metric when every required joint is seen; [atRest] says whether it satisfies the start pose. */
data class StartDiagnosis(val joints: List<StartJoint>, val angle: Double?, val atRest: Boolean?, val ready: Boolean,
    val requiredBand: ClosedFloatingPointRange<Double>? = null, val orientation: CameraOrientation? = null) {
    /** The first thing blocking readiness, in plain words, or null when ready. */
    val blocker: String? get() = orientation?.takeIf { !it.accepted }?.message ?: joints.firstOrNull { !it.ok }?.let {
        if (!it.inFrame) "필요한 관절이 화면 안에 보이게 해 주세요" else "팔과 몸통이 가려지지 않게 해 주세요"
    } ?: if (atRest == false) "안내된 시작 자세로 팔과 다리를 맞춰 주세요" else if (!ready) "시작 자세를 확인할 수 없어요" else null
    fun status(held: Boolean): String = blocker ?: if (held && ready) "자세 확인 · 곧 촬영을 시작해요" else "시작 자세를 잠시 유지해 주세요"
}
