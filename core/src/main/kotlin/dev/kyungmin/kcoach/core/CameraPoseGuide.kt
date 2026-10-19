package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sign

/**
 * Start-pose pictogram ("신호등 졸라맨") that follows the user instead of sitting fixed on screen.
 * It is only a picture: readiness stays with [CameraCaptureGuide] angle checks, never with overlap.
 *
 * Limb angles are degrees from straight down (gravity = image down); positive points toward the
 * side the body faces. Torso lean is from vertical, positive toward the facing side; lying = 90.
 */
enum class CameraPoseFamily(
    val front: Boolean,
    val torsoLean: Double,
    val arm: Pair<Double, Double>,
    val leg: Pair<Double, Double>? = null,
    /** Apparent shoulder width relative to a straight-on view; below 1 the user is turned toward a 45-degree oblique view. */
    val widthScale: Double = if (front) 1.0 else kotlin.math.cos(Math.toRadians(CameraOrientationPolicy.OBLIQUE)),
    /** Degrees each arm segment (upper arm, forearm) leans inward toward the midline; 0 keeps the arm in the sagittal plane. */
    val armInward: Pair<Double, Double> = 0.0 to 0.0,
    /** Seen from behind at 45 degrees (the camera policy's rear oblique) instead of from the front at 45 degrees. */
    val rear: Boolean = false,
) {
    STAND_ARMS_DOWN_SIDE(false, 0.0, 0.0 to 4.0),
    STAND_ARMS_DOWN_FRONT(true, 0.0, 8.0 to 6.0),
    STAND_ELBOW_BENT_SIDE(false, 8.0, -6.0 to 100.0),
    OVERHEAD_EXTENSION_SIDE(false, 2.0, 170.0 to -15.0, armInward = 2.0 to 40.0),
    // Start pose: upper arms straight up beside the head, forearms folded down and back behind it (the wrist is hidden by the head).
    CABLE_OVERHEAD_EXTENSION_SIDE(false, 2.0, 170.0 to -15.0, armInward = 2.0 to 40.0),
    // 45 degrees: a pure side view points the forearm along the camera axis, which hides the wrist and flattens its depth.
    EXTERNAL_ROTATION_OBLIQUE(true, 0.0, 0.0 to -90.0, widthScale = kotlin.math.cos(Math.toRadians(CameraOrientationPolicy.OBLIQUE))),
    PREACHER_SIDE(false, 10.0, 45.0 to 40.0),
    INCLINE_ARMS_DOWN_SIDE(false, 45.0, 0.0 to 0.0),
    INCLINE_PRESS_SIDE(false, 45.0, 180.0 to 180.0),
    // Wide grip overhead, seen from behind: arms go up and out.
    SEATED_ARMS_UP_SIDE(false, -12.0, 180.0 to 180.0, armInward = -35.0 to -25.0, rear = true),
    // Hands at shoulder height, elbows out to the sides and a little forward.
    SEATED_PRESS_FRONT(false, 0.0, 15.0 to 180.0, armInward = -80.0 to 0.0),
    SEATED_ARMS_FORWARD_SIDE(false, 2.0, 85.0 to 88.0),
    // Handles at the chest: elbows out and slightly back, forearms pointing forward.
    SEATED_CHEST_PRESS_SIDE(false, -5.0, -10.0 to 90.0, armInward = -70.0 to 0.0),
    ARMS_OPEN_FRONT(true, 0.0, 80.0 to 85.0),
    // Hands together in front of the chest. The fly gate measures elbow-shoulder-opposite shoulder, which a side view hides.
    FLY_CLOSED_FRONT(true, 0.0, 8.0 to -75.0),
    BENT_OVER_SIDE(false, 62.0, 0.0 to 0.0),
    LYING_ARMS_UP_SIDE(false, 90.0, 180.0 to 180.0),
    LYING_EXTENSION_SIDE(false, 90.0, 170.0 to 70.0),
    PULLOVER_SIDE(false, 90.0, 100.0 to 100.0),
    SQUAT_SIDE(false, 4.0, -40.0 to 160.0, 2.0 to -2.0),
    // Plank: body almost horizontal, arms straight down to the floor.
    PLANK_SIDE(false, 80.0, 0.0 to 0.0),
    // Lying on the back with the knees bent, arms crossed forward.
    SITUP_LYING_SIDE(false, 90.0, 90.0 to 90.0, 40.0 to -60.0);

    val lowerBody get() = leg != null
}

data class GuidePoint(val x: Double, val y: Double)

/** The user's measured anchor in frame pixels. [other] is the opposite shoulder (same point if unseen). */
data class GuideAnchor(val shoulder: GuidePoint, val other: GuidePoint, val bodyHeight: Double, val facing: Int)

data class GuideLimb(val points: List<GuidePoint>, val width: Double, val primary: Boolean)

/** [torso] is a two-point capsule (side views) or a four-point outline (front views). */
data class GuideFigure(val head: GuidePoint, val headRadius: Double, val torso: List<GuidePoint>, val torsoWidth: Double,
    val limbs: List<GuideLimb>, val lowerBody: Boolean)

object CameraPoseGuide {
    // Drillis-Contini segment lengths as a fraction of standing height.
    private const val UPPER_ARM = .186; private const val FOREARM = .146; private const val SHOULDER_WIDTH = .259
    private const val TORSO = .288; private const val HIP_WIDTH = .191; private const val THIGH = .245; private const val SHANK = .246
    private const val HEAD_RADIUS = .065; private const val HEAD_OFFSET = .115

    fun familyFor(exerciseId: String): CameraPoseFamily? {
        val id = ExerciseMotionCatalog.normalizeExerciseId(exerciseId)
        return when (ExerciseMeasurementContracts.resolve(id).recipe) {
            MeasurementRecipe.CURL -> when (id) {
                "preacher_curl" -> CameraPoseFamily.PREACHER_SIDE
                "incline_curl", "incline_hammer_curl" -> CameraPoseFamily.INCLINE_ARMS_DOWN_SIDE
                else -> CameraPoseFamily.STAND_ARMS_DOWN_SIDE
            }
            MeasurementRecipe.ROW -> if (id in setOf("seated_row", "cable_seated_row", "high_row_machine"))
                CameraPoseFamily.SEATED_ARMS_FORWARD_SIDE else CameraPoseFamily.BENT_OVER_SIDE
            MeasurementRecipe.VERTICAL_PULL -> CameraPoseFamily.SEATED_ARMS_UP_SIDE
            MeasurementRecipe.CHEST_PRESS -> when (id) {
                "chest_press" -> CameraPoseFamily.SEATED_CHEST_PRESS_SIDE
                "incline_bench" -> CameraPoseFamily.INCLINE_PRESS_SIDE
                else -> CameraPoseFamily.LYING_ARMS_UP_SIDE
            }
            MeasurementRecipe.SHOULDER_PRESS -> CameraPoseFamily.SEATED_PRESS_FRONT
            MeasurementRecipe.LATERAL_RAISE -> CameraPoseFamily.STAND_ARMS_DOWN_FRONT
            MeasurementRecipe.FLY -> if (id == "machine_fly") CameraPoseFamily.ARMS_OPEN_FRONT else CameraPoseFamily.FLY_CLOSED_FRONT
            MeasurementRecipe.PULLOVER -> CameraPoseFamily.PULLOVER_SIDE
            MeasurementRecipe.TRICEPS_EXTENSION -> when (id) {
                "cable_overhead_extension", "rope_overhead_extension" -> CameraPoseFamily.CABLE_OVERHEAD_EXTENSION_SIDE
                "dumbbell_triceps_extension" -> CameraPoseFamily.OVERHEAD_EXTENSION_SIDE
                "ez_lying_extension" -> CameraPoseFamily.LYING_EXTENSION_SIDE
                else -> CameraPoseFamily.STAND_ELBOW_BENT_SIDE
            }
            MeasurementRecipe.EXTERNAL_ROTATION -> CameraPoseFamily.EXTERNAL_ROTATION_OBLIQUE
            MeasurementRecipe.SQUAT -> CameraPoseFamily.SQUAT_SIDE
            MeasurementRecipe.PUSH_UP -> CameraPoseFamily.PLANK_SIDE
            MeasurementRecipe.SIT_UP -> CameraPoseFamily.SITUP_LYING_SIDE
            MeasurementRecipe.PULL_UP, MeasurementRecipe.MANUAL, MeasurementRecipe.LOADED_HINGE, MeasurementRecipe.LOADED_LUNGE, MeasurementRecipe.HIP_EXTENSION, MeasurementRecipe.CORE_FLEXION, MeasurementRecipe.CORE_ROTATION, MeasurementRecipe.CORE_EXTENSION, MeasurementRecipe.DIP, MeasurementRecipe.SQUAT_PRESS -> null
        }
    }

    /** Anchor from normalized landmarks of a [width]x[height] frame, or null when the anchor joints are not seen. */
    fun measure(points: List<CameraLandmark>, width: Double, height: Double, family: CameraPoseFamily, left: Boolean): GuideAnchor? {
        fun seen(i: Int) = points.getOrNull(i)?.let { minOf(it.visibility ?: 0.0, it.presence ?: 0.0) >= .5 &&
            it.x in -.05..1.05 && it.y in -.05..1.05 } == true
        fun px(i: Int) = GuidePoint(points[i].x * width, points[i].y * height)
        val shoulderIndex = if (left) 11 else 12; val otherIndex = if (left) 12 else 11
        if (family.front) {
            if (!seen(11) || !seen(12)) return null
            val shoulder = px(shoulderIndex); val other = px(otherIndex)
            val span = dist(shoulder, other).takeIf { it > 1.0 } ?: return null
            return GuideAnchor(shoulder, other, span / (SHOULDER_WIDTH * family.widthScale), 1)
        }
        val elbowIndex = if (left) 13 else 14; val hipIndex = if (left) 23 else 24
        if (!seen(shoulderIndex) || !seen(elbowIndex)) return null
        val shoulder = px(shoulderIndex)
        val upperArm = dist(shoulder, px(elbowIndex)).takeIf { it > 1.0 } ?: return null
        val hip = if (seen(hipIndex)) px(hipIndex) else null
        // The upper arm can foreshorten toward the camera; a visible torso keeps the scale honest.
        val bodyHeight = max(upperArm / UPPER_ARM, hip?.let { dist(shoulder, it) / TORSO } ?: 0.0)
        val reclined = abs(family.torsoLean) >= 30.0
        val facing = when {
            reclined && hip != null && abs(shoulder.x - hip.x) > 2.0 -> sign(shoulder.x - hip.x).toInt()
            seen(0) && abs(px(0).x - shoulder.x) > 2.0 -> sign(px(0).x - shoulder.x).toInt()
            else -> 1
        }
        return GuideAnchor(shoulder, if (seen(otherIndex)) px(otherIndex) else shoulder, bodyHeight, facing)
    }

    fun build(family: CameraPoseFamily, anchor: GuideAnchor): GuideFigure {
        val h = anchor.bodyHeight
        val (upper, fore) = family.arm
        if (family.front) {
            val limbs = listOf(anchor.shoulder to anchor.other, anchor.other to anchor.shoulder).map { (s, opposite) ->
                val out = sign(s.x - opposite.x)
                val elbow = s + outward(upper, out) * (UPPER_ARM * h)
                GuideLimb(listOf(s, elbow, elbow + outward(fore, out) * (FOREARM * h)), .03 * h, true)
            }
            val inset = (SHOULDER_WIDTH - HIP_WIDTH) / 2 * h
            fun hipUnder(s: GuidePoint, toward: GuidePoint) = GuidePoint(s.x + sign(toward.x - s.x) * inset, s.y + TORSO * h)
            val mid = GuidePoint((anchor.shoulder.x + anchor.other.x) / 2, (anchor.shoulder.y + anchor.other.y) / 2)
            return GuideFigure(GuidePoint(mid.x, mid.y - HEAD_OFFSET * h), HEAD_RADIUS * h,
                listOf(anchor.shoulder, anchor.other, hipUnder(anchor.other, anchor.shoulder), hipUnder(anchor.shoulder, anchor.other)),
                0.0, limbs, false)
        }
        return buildTurned(family, anchor)
    }

    private class SideJoints(val shoulder: DoubleArray, val elbow: DoubleArray, val wrist: DoubleArray,
        val hip: DoubleArray, val knee: DoubleArray?, val ankle: DoubleArray?)

    private fun vec(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    private fun plus(a: DoubleArray, b: DoubleArray, k: Double) = vec(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k)
    private fun hipCenterOf(family: CameraPoseFamily) = Math.toRadians(family.torsoLean).let { vec(0.0, -TORSO * cos(it), -TORSO * sin(it)) }

    /** Segment direction in body axes from a sagittal angle (degrees from straight down, positive forward) and an inward lean. */
    private fun segment(sagittal: Double, inward: Double, inwardSign: Double): DoubleArray {
        val a = Math.toRadians(sagittal); val b = Math.toRadians(inward)
        return vec(inwardSign * sin(b), -cos(a) * cos(b), sin(a) * cos(b))
    }

    /** One body side, in body axes (x = the person's right, y = up, z = forward) in body heights, the shoulder line at y = 0. */
    private fun sideJoints(family: CameraPoseFamily, side: Double): SideJoints {
        val (upper, fore) = family.arm; val (upperIn, foreIn) = family.armInward
        val shoulder = vec(side * SHOULDER_WIDTH / 2, 0.0, 0.0)
        val inwardSign = -sign(side)
        val elbow = plus(shoulder, segment(upper, upperIn, inwardSign), UPPER_ARM)
        val wrist = plus(elbow, segment(fore, foreIn, inwardSign), FOREARM)
        val hip = plus(hipCenterOf(family), vec(side * HIP_WIDTH / 2, 0.0, 0.0), 1.0)
        val legs = family.leg
        val knee = legs?.let { plus(hip, segment(it.first, 0.0, 0.0), THIGH) }
        val ankle = if (legs != null && knee != null) plus(knee, segment(legs.second, 0.0, 0.0), SHANK) else null
        return SideJoints(shoulder, elbow, wrist, hip, knee, ankle)
    }

    /** Landmark index (11..16 arms, 23..28 legs; odd = left) to body-frame position, for tests of the start-pose gate. */
    internal fun bodyFrameJoints(family: CameraPoseFamily): Map<Int, DoubleArray> = buildMap {
        for (onLeft in listOf(true, false)) {
            val j = sideJoints(family, if (onLeft) -1.0 else 1.0); val o = if (onLeft) 0 else 1
            put(11 + o, j.shoulder); put(13 + o, j.elbow); put(15 + o, j.wrist); put(23 + o, j.hip)
            j.knee?.let { put(25 + o, it) }; j.ankle?.let { put(27 + o, it) }
        }
    }

    /**
     * Side families as a small 3D skeleton (MediaPipe-style joints) in body axes: x = the person's right, y = up, z = forward.
     * It is turned by the family's yaw and projected straight onto the image, so shoulder width, the head between the
     * shoulders, the torso's two faces and arms folding behind the head all fall out of the rotation.
     */
    private fun buildTurned(family: CameraPoseFamily, anchor: GuideAnchor): GuideFigure {
        val h = anchor.bodyHeight
        val yaw = kotlin.math.acos(family.widthScale.coerceIn(0.0, 1.0)).let { if (family.rear) Math.PI - it else it }
        val f = anchor.facing.toDouble(); val cy = cos(yaw); val sy = sin(yaw)
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        fun screen(v: DoubleArray) = GuidePoint(-v[0] * cy + v[2] * f * sy, -v[1])
        // From the front the left shoulder is on the image right; from behind it is on the image left.
        val anchorIsLeft = if (family.rear) anchor.other.x >= anchor.shoulder.x else anchor.other.x <= anchor.shoulder.x
        val anchorSide = if (anchorIsLeft) -1.0 else 1.0
        val origin = screen(p(anchorSide * SHOULDER_WIDTH / 2, 0.0, 0.0))
        fun map(v: DoubleArray) = screen(v).let { GuidePoint(anchor.shoulder.x + (it.x - origin.x) * h, anchor.shoulder.y + (it.y - origin.y) * h) }
        fun add(a: DoubleArray, b: DoubleArray, k: Double) = p(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k)
        val lean = Math.toRadians(family.torsoLean)
        val hipCenter = p(0.0, -TORSO * cos(lean), -TORSO * sin(lean))
        val limbs = mutableListOf<GuideLimb>()
        for ((side, primary) in listOf(-anchorSide to false, anchorSide to true)) {
            val j = sideJoints(family, side)
            limbs += GuideLimb(listOf(map(j.shoulder), map(j.elbow), map(j.wrist)), .03 * h, primary)
        }
        if (family.leg != null) for ((side, primary) in listOf(-anchorSide to false, anchorSide to true)) {
            val j = sideJoints(family, side)
            limbs += GuideLimb(listOf(map(j.hip), map(j.knee!!), map(j.ankle!!)), .04 * h, primary)
        }
        val shoulders = p(0.0, 0.0, 0.0)
        fun joint(side: Double, level: DoubleArray, width: Double) = map(add(level, p(side * width / 2, 0.0, 0.0), 1.0))
        // Near/far shoulder, then far/near hip: the torso is just these four joints joined.
        val joints = listOf(joint(anchorSide, shoulders, SHOULDER_WIDTH), joint(-anchorSide, shoulders, SHOULDER_WIDTH),
            joint(-anchorSide, hipCenter, HIP_WIDTH), joint(anchorSide, hipCenter, HIP_WIDTH))
        val head = map(p(0.0, HEAD_OFFSET * cos(lean), HEAD_OFFSET * sin(lean)))
        return GuideFigure(head, HEAD_RADIUS * h, joints, .03 * h, limbs, family.lowerBody)
    }

    private fun outward(degrees: Double, out: Double) = Math.toRadians(degrees).let { GuidePoint(out * sin(it), cos(it)) }
    private fun dist(a: GuidePoint, b: GuidePoint) = hypot(a.x - b.x, a.y - b.y)
    private operator fun GuidePoint.plus(o: GuidePoint) = GuidePoint(x + o.x, y + o.y)
    private operator fun GuidePoint.times(k: Double) = GuidePoint(x * k, y * k)
}

/** Light exponential smoothing so the pictogram does not jitter; it lingers briefly when tracking drops. */
class CameraPoseGuideSmoother(private val alpha: Double = .85, private val holdMs: Long = 180) {
    private var last: GuideAnchor? = null
    private var seenAtMs = 0L

    fun update(anchor: GuideAnchor?, nowMs: Long): GuideAnchor? {
        if (anchor == null) {
            if (last != null && nowMs - seenAtMs > holdMs) last = null
            return last
        }
        val previous = last
        last = if (previous == null || previous.facing != anchor.facing) anchor else GuideAnchor(
            mix(previous.shoulder, anchor.shoulder), mix(previous.other, anchor.other),
            previous.bodyHeight + alpha * (anchor.bodyHeight - previous.bodyHeight), anchor.facing)
        seenAtMs = nowMs
        return last
    }

    private fun mix(a: GuidePoint, b: GuidePoint) = GuidePoint(a.x + alpha * (b.x - a.x), a.y + alpha * (b.y - a.y))
}
