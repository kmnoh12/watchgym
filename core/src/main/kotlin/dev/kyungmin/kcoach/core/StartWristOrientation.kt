package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** A candidate is a specified grip, not a claim that every variant of an exercise has this start. */
enum class StartWristCandidate {
    HORIZONTAL_PALM_UP,
    HORIZONTAL_KNUCKLES_UP,
    ARM_DOWN,
    DEFERRED,
    MANUAL,
}

object StartWristOrientationPolicy {
    fun candidate(exerciseId: String): StartWristCandidate {
        val id = ExerciseMotionCatalog.normalizeExerciseId(exerciseId)
        if (ExerciseTrackingProfiles.resolve(id).mode == TrackingMode.MANUAL_REQUIRED) return StartWristCandidate.MANUAL
        return when (id) {
            "external_rotation", "single_arm_cable_external_rotation" -> StartWristCandidate.HORIZONTAL_PALM_UP
            "crossover_reverse_fly" -> StartWristCandidate.HORIZONTAL_KNUCKLES_UP
            "dumbbell_lateral_raise", "single_arm_dumbbell_lateral_raise" -> StartWristCandidate.ARM_DOWN
            else -> StartWristCandidate.DEFERRED
        }
    }

    /** No candidate is enabled merely by its exercise name; hardware-reviewed targets are still required. */
    fun uncheckedStartStatus(exerciseId: String): String = when (candidate(exerciseId)) {
        StartWristCandidate.MANUAL -> "MANUAL"
        StartWristCandidate.DEFERRED -> "DEFERRED"
        else -> "UNCONFIRMED"
    }
}

/**
 * A target in the watch's physical sensor frame. Supply only with a confirmed wearing/variant binding.
 * This class validates geometry; construction is not evidence of Watch8 validation or correct body posture.
 * There is deliberately no exercise-default angle or target and no inference from the first quiet sample.
 */
data class StartWristGravityTarget(val x: Double, val y: Double, val z: Double, val maxAngularErrorRad: Double) {
    init {
        require(listOf(x, y, z, maxAngularErrorRad).all { it.isFinite() })
        require(abs(sqrt(x * x + y * y + z * z) - 1.0) < 0.000001) { "Expected a unit gravity direction" }
        require(maxAngularErrorRad > 0.0 && maxAngularErrorRad < PI / 2.0) { "Opposite directions must stay distinguishable" }
    }
    private val minimumDot = cos(maxAngularErrorRad)
    fun matches(nx: Double, ny: Double, nz: Double): Boolean = x * nx + y * ny + z * nz >= minimumDot
}
