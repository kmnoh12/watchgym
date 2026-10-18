package dev.kyungmin.kcoach.core

import kotlin.math.acos
import kotlin.math.sqrt

/** Camera-side comparison only. These provisional angle gates are not verified ground truth. */
enum class CameraMotion(val restDegrees: Double, val excursionDegrees: Double, val increasing: Boolean) {
    ELBOW_FOLD(145.0, 85.0, false),
    ELBOW_PRESS(100.0, 150.0, true),
    SHOULDER_RAISE(25.0, 65.0, true),
    SQUAT_KNEE_FLEXION(160.0, 110.0, false),
    // Shoulder-driven lifts: the elbow barely moves, so the arm is measured at the shoulder.
    PULLOVER_REACH(150.0, 100.0, false),
    FLY_OPEN(145.0, 110.0, false),
    FLY_CLOSED(105.0, 140.0, true);

    fun atRest(angle: Double) = if (increasing) angle <= restDegrees else angle >= restDegrees
    fun departed(angle: Double) = if (increasing) angle >= excursionDegrees else angle <= excursionDegrees

    companion object {
        fun forExercise(exerciseId: String, name: String = ""): CameraMotion? {
            if (ExerciseMeasurementContracts.resolve(exerciseId).recipe == MeasurementRecipe.SQUAT) return SQUAT_KNEE_FLEXION
            val profile = ExerciseTrackingProfiles.resolve(exerciseId, name)
            if (profile.mode == TrackingMode.MANUAL_REQUIRED) return null
            return when (profile.family) {
                MotionFamily.FLEXION_EXTENSION, MotionFamily.PULL_RETURN -> ELBOW_FOLD
                MotionFamily.PRESS_RETURN, MotionFamily.VERTICAL_PRESS_RETURN -> ELBOW_PRESS
                MotionFamily.MULTI_AXIS_RETURN -> if (exerciseId.contains("lateral") || name.contains("레터럴")) SHOULDER_RAISE else null
                else -> null
            }
        }
    }
}

data class PosePoint(val x: Double, val y: Double, val z: Double, val visibility: Double)

object UpperBodyPoseGeometry {
    fun metric(points: List<PosePoint>, motion: CameraMotion, left: Boolean): Double? {
        val shoulder = if (left) 11 else 12
        val elbow = if (left) 13 else 14
        val wrist = if (left) 15 else 16
        val hip = if (left) 23 else 24
        val knee = if (left) 25 else 26; val ankle = if (left) 27 else 28
        val otherShoulder = if (left) 12 else 11
        val indices = when (motion) {
            CameraMotion.SHOULDER_RAISE, CameraMotion.PULLOVER_REACH -> listOf(elbow, shoulder, hip)
            CameraMotion.FLY_OPEN, CameraMotion.FLY_CLOSED -> listOf(elbow, shoulder, otherShoulder)
            CameraMotion.SQUAT_KNEE_FLEXION -> listOf(hip, knee, ankle)
            else -> listOf(shoulder, elbow, wrist)
        }
        val value = angle(points, indices[0], indices[1], indices[2]) ?: return null
        if (motion == CameraMotion.SQUAT_KNEE_FLEXION && motion.atRest(value) &&
            (angle(points, shoulder, hip, knee) ?: return null) < 150.0) return null
        return value
    }

    fun angle(points: List<PosePoint>, first: Int, vertex: Int, last: Int): Double? {
        val indices = listOf(first, vertex, last)
        val p = indices.map { points.getOrNull(it) ?: return null }
        if (p.any { it.visibility < 0.65 || !listOf(it.x, it.y, it.z, it.visibility).all(Double::isFinite) }) return null
        val a = doubleArrayOf(p[0].x - p[1].x, p[0].y - p[1].y, p[0].z - p[1].z)
        val b = doubleArrayOf(p[2].x - p[1].x, p[2].y - p[1].y, p[2].z - p[1].z)
        val norm = sqrt(a.sumOf { it * it }) * sqrt(b.sumOf { it * it })
        if (norm < 1e-6) return null
        return Math.toDegrees(acos((a.indices.sumOf { a[it] * b[it] } / norm).coerceIn(-1.0, 1.0)))
    }
}

class UpperBodyPoseCounter(private val motion: CameraMotion) {
    var reps = 0; private set
    var ready = false; private set
    private var departed = false
    private var restSince = -1L
    private var departureAt = 0L
    private var lastAt = -1L
    private var lastValidAt = -1L

    fun reset() {
        reps = 0; resetCycle(); lastAt = -1L; lastValidAt = -1L
    }

    fun observe(timestampMs: Long, angle: Double?): Boolean {
        if (timestampMs < 0 || timestampMs <= lastAt) { resetCycle(); return false }
        lastAt = timestampMs
        if (angle == null || !angle.isFinite() || angle !in 0.0..180.0) {
            if (lastValidAt < 0 || timestampMs - lastValidAt > 500L) resetCycle()
            return false
        }
        if (lastValidAt >= 0 && timestampMs - lastValidAt > 500L) resetCycle()
        lastValidAt = timestampMs
        if (!ready) {
            if (motion.atRest(angle)) {
                if (restSince < 0) restSince = timestampMs
                if (timestampMs - restSince >= 200L) ready = true
            } else restSince = -1L
            return false
        }
        if (!departed && motion.departed(angle)) { departed = true; departureAt = timestampMs }
        if (departed && motion.atRest(angle)) {
            val accepted = timestampMs - departureAt >= 200L
            departed = false
            if (accepted) { reps++; return true }
        }
        return false
    }

    private fun resetCycle() { ready = false; departed = false; restSince = -1L; departureAt = 0L }
}
