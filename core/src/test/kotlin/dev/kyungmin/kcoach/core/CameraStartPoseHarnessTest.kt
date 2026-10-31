package dev.kyungmin.kcoach.core

import java.io.File
import kotlin.test.*

/**
 * Runs every exercise's own start-pose pictogram through the real readiness gate, so a start pose the app
 * asks for but can never recognise is caught here instead of on the user's phone.
 * Landmarks are synthetic: this checks the app's internal consistency, not MediaPipe's real confidence
 * distribution (that needs replayed field recordings; see the sensitivity report notes).
 */
class CameraStartPoseHarnessTest {
    private val w = 480.0; private val h = 640.0

    /**
     * Side-view exercises whose gate asks for far-side joints on purpose (squat: both legs and shoulders). Whether the model
     * really loses them in a side view is unproven; the field overlay (KCoachPose log) must show it before this gate changes.
     */
    private val needsFieldData = setOf("squat", "barbell_squat", "front_squat", "goblet_squat", "smith_squat", "push_up", "sit_up")

    private class Case(val id: String, val family: CameraPoseFamily, val guide: CameraCaptureGuide)

    private fun cases(left: Boolean) = ExerciseMotionCatalog.explicitBindings.map { it.exerciseId }.plus("squat").distinct().sorted().mapNotNull { id ->
        val family = CameraPoseGuide.familyFor(id) ?: return@mapNotNull null
        Case(id, family, CameraCaptureGuide.forExercise(id, left))
    }

    /** Landmarks of a user holding [family]'s start pose. Near-side joints get [near]; the hidden side gets [far]. */
    private fun landmarks(c: Case, left: Boolean, near: Double, far: Double): Pair<List<CameraLandmark>, List<CameraLandmark>> {
        val family = c.family
        val bodyHeight = 450.0
        val shoulder = GuidePoint(300.0, 200.0)
        val anchor = if (family.front) GuideAnchor(shoulder, GuidePoint(shoulder.x - .259 * bodyHeight, shoulder.y), bodyHeight, 1)
            else GuideAnchor(shoulder, shoulder, bodyHeight, 1)
        val figure = CameraPoseGuide.build(family, anchor)
        val image = MutableList(33) { CameraLandmark(.5, .5, 0.0, 0.0, 0.0) }
        val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 0.0, 0.0) }
        fun put(index: Int, p: GuidePoint, confidence: Double) {
            image[index] = CameraLandmark((p.x / w).coerceIn(0.0, 1.0), (p.y / h).coerceIn(0.0, 1.0), 0.0, confidence, confidence)
            world[index] = CameraLandmark(p.x / bodyHeight, p.y / bodyHeight, 0.0, confidence, confidence)
        }
        fun idx(leftIndex: Int, onLeft: Boolean) = if (onLeft) leftIndex else leftIndex + 1
        put(0, figure.head, near)
        val arms = figure.limbs.filter { it.points.size == 3 && it.width < .06 * bodyHeight }
        if (family.front) {
            // The pictogram's first limb starts at the anchor shoulder, which is the user's left (landmark 11).
            arms.forEachIndexed { n, limb ->
                val onLeft = n == 0
                put(idx(11, onLeft), limb.points[0], near); put(idx(13, onLeft), limb.points[1], near); put(idx(15, onLeft), limb.points[2], near)
            }
            put(23, figure.torso[3], near); put(24, figure.torso[2], near)
        } else {
            val primary = arms.last { true }; val secondary = arms.first()
            put(idx(11, left), primary.points[0], near); put(idx(13, left), primary.points[1], near); put(idx(15, left), primary.points[2], near)
            put(idx(11, !left), secondary.points[0], far); put(idx(13, !left), secondary.points[1], far); put(idx(15, !left), secondary.points[2], far)
            put(idx(23, left), figure.torso[3], near); put(idx(23, !left), figure.torso[2], far)
            figure.limbs.filter { it.width >= .06 * bodyHeight }.let { legs ->
                if (legs.size == 2) {
                    put(idx(23, left), legs[1].points[0], near); put(idx(25, left), legs[1].points[1], near); put(idx(27, left), legs[1].points[2], near)
                    put(idx(23, !left), legs[0].points[0], far); put(idx(25, !left), legs[0].points[1], far); put(idx(27, !left), legs[0].points[2], far)
                }
            }
        }
        // The drawing is a 2D projection of a small 3D skeleton. The gate reads that skeleton in body axes
        // (a frontal view, depth along z), and the yaw rotation below turns it to the exercise's camera target.
        if (!family.front) {
            for ((index, joint) in CameraPoseGuide.bodyFrameJoints(family)) {
                if ((image[index].visibility ?: 0.0) == 0.0) continue
                // Body x is the person's right; the person's left sits at world +x when they face the camera.
                world[index] = world[index].copy(x = -joint[0], y = -joint[1], z = -joint[2])
            }
        }
        if (c.id in setOf("external_rotation", "single_arm_cable_external_rotation")) {
            // The thumbnail's inward horizontal forearm represents forward reach in 3D,
            // not a wrist crossing through the chest in the frontal plane.
            for ((elbow, wrist) in listOf(13 to 15, 14 to 16)) {
                world[wrist] = world[elbow].copy(z = world[elbow].z - .146)
            }
        }
        val target = c.guide.viewTarget!!
        val yaw = Math.toRadians(target.degrees * if (left) -1 else 1)
        val rotated = world.map { p -> p.copy(x = p.x * kotlin.math.cos(yaw) - p.z * kotlin.math.sin(yaw),
            z = p.x * kotlin.math.sin(yaw) + p.z * kotlin.math.cos(yaw)) }
        return image to rotated
    }

    /** Scales confidences so that the gate's fixed 0.65 acts as [threshold]; lets the report ask "what if the bar were X". */
    private fun rescaled(points: List<CameraLandmark>, threshold: Double) = points.map {
        CameraLandmark(it.x, it.y, it.z, it.visibility?.let { v -> minOf(1.0, v * CameraCaptureGuide.MIN_CONFIDENCE / threshold) },
            it.presence?.let { v -> minOf(1.0, v * CameraCaptureGuide.MIN_CONFIDENCE / threshold) })
    }

    /** Joints of the user's far side, which a pure side view hides behind the body. */
    private fun farSide(index: Int, left: Boolean) = index in 11..32 && (index % 2 == 1) != left

    @Test fun everyExercisesOwnStartPosePassesItsOwnReadinessGate() {
        val failures = mutableListOf<String>()
        listOf(true, false).forEach { left ->
            cases(left).filter { it.id !in needsFieldData && ExerciseMeasurementContracts.resolve(it.id).recipe !in setOf(MeasurementRecipe.SQUAT, MeasurementRecipe.PUSH_UP, MeasurementRecipe.SIT_UP) }.forEach { c ->
                val (image, world) = landmarks(c, left, near = .95, far = .95)
                if (!c.guide.startReady(image, world, left))
                    failures += "${c.id} (${c.family}, left=$left): ${c.guide.diagnose(image, world, left).blocker}"
            }
        }
        assertTrue(failures.isEmpty(), "Start poses the app asks for but its own gate rejects:\n" + failures.joinToString("\n"))
    }

    @Test fun obliqueViewsRequireBothTorsoSidesEvenThoughOnlyTheWatchArmIsCounted() {
        listOf(true, false).forEach { left ->
            cases(left).forEach { c ->
                assertTrue(c.guide.required.containsAll(CameraOrientationPolicy.torsoJoints), c.id)
                val (image, world) = landmarks(c, left, near = .95, far = .30)
                if (!c.family.front) assertFalse(c.guide.startReady(image, world, left), c.id)
            }
        }
    }

    @Test fun diagnosisNamesTheJointThatBlocksReadiness() {
        val c = cases(true).first { it.family == CameraPoseFamily.STAND_ARMS_DOWN_SIDE }
        val (image, world) = landmarks(c, true, near = .95, far = .95)
        assertTrue(c.guide.diagnose(image, world, true).ready)
        val weak = image.toMutableList().also { it[13] = it[13].copy(visibility = .40) }
        val diagnosis = c.guide.diagnose(weak, world, true)
        assertFalse(diagnosis.ready)
        assertEquals(13, diagnosis.joints.first { !it.ok }.index)
        assertEquals("팔이 몸에 가려져요", diagnosis.blocker)
    }

    /** Writes the per-exercise threshold sensitivity table to build/reports/camera-start-pose.md. */
    @Test fun writesPassRateReportAcrossConfidenceThresholds() {
        val thresholds = listOf(.40, .50, .60, .65, .70, .80)
        val ladder = (50..95 step 5).map { it / 100.0 }
        val out = StringBuilder("# 시작 자세 통과율 (합성 랜드마크)\n\n")
        out.append("근거 한계: 관절 신뢰도는 합성값이다. 가까운 쪽 관절을 0.50~0.95 균등 구간, 가려진 반대쪽을 0.30으로 가정했다. 새 요 판정은 양어깨·골반이 필요하므로 몸통 반대쪽이 가려진 사선은 의도적으로 거절한다. ")
        out.append("실제 모델 분포가 아니라 \"어떤 종목이 기준값과 무관하게 영영 통과 못 하는가\"를 보는 표다.\n\n")
        out.append("| 종목 | 자세 | 시점 | 필수 관절 | ").append(thresholds.joinToString(" | ") { "≥%.2f".format(it) }).append(" |\n")
        out.append("|---|---|---|---|").append(thresholds.joinToString("") { "---|" }).append("\n")
        cases(true).forEach { c ->
            val cells = thresholds.map { t ->
                val passed = ladder.count { level ->
                    val (image, world) = landmarks(c, true, near = level, far = .30)
                    c.guide.startReady(rescaled(image, t), world, true)
                }
                "%d%%".format(passed * 100 / ladder.size)
            }
            out.append("| ${c.id} | ${c.family} | ${c.guide.viewTarget!!.label} | ${c.guide.required.sorted().joinToString(",")} | ")
                .append(cells.joinToString(" | ")).append(" |\n")
        }
        val file = File("build/reports/camera-start-pose.md").apply { parentFile.mkdirs() }
        file.writeText(out.toString())
        assertTrue(file.length() > 0)
    }

    @Test fun diagnosisReportsTheElbowAngleForRotationLiftsThatHaveNoMovementMetric() {
        val c = cases(true).first { it.id == "external_rotation" }
        assertNull(c.guide.metric)
        val (image, world) = landmarks(c, true, near = .95, far = .95)
        val ok = c.guide.diagnose(image, world, true)
        assertNotNull(ok.angle); assertEquals(true, ok.atRest); assertTrue(ok.ready)
        // A forearm pointing along the camera axis flattens the model's depth estimate; the overlay must show that angle.
        val straightArm = world.toMutableList().also { it[15] = it[13].copy(x = it[13].x - (it[11].x - it[13].x), y = it[13].y - (it[11].y - it[13].y)) }
        val bad = c.guide.diagnose(image, straightArm, true)
        assertEquals(false, bad.atRest); assertFalse(bad.ready)
        assertEquals(75.0..105.0, bad.requiredBand)
        assertEquals("안내된 시작 자세로 팔과 다리를 맞춰 주세요", bad.blocker)
    }
}
