package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class VerticalPressSignal(val timestampNs: Long, val acceleration: Double, val velocityProxy: Double)

/**
 * Causal vertical press candidate, v1. Registered dumbbell and Smith shoulder presses use it.
 * Gravity direction/bias: 2.5 s time constant; leaky integration: 0.75 s; smoothing: 80 ms.
 * The signed velocity proxy preserves ascent/return, but is NOT physical velocity or joint ROM.
 * No camera, target count, set-length percentile, future samples, or end-of-set completion.
 * Constants are frozen with this algorithm ID; changing them requires a new version.
 */
class VerticalPressRepDetector(
    private val profile: ExerciseTrackingProfile = ExerciseTrackingProfiles.resolve("dumbbell_shoulder_press"),
) {
    val maximumCycleNs: Long get() = profile.maximumRepMs * 1_000_000L

    private var lastNs: Long? = null
    private var gravity: DoubleArray? = null
    private var bias = 9.80665
    private var velocity = 0.0
    private var smoothed = 0.0
    private var direction = 0
    private var startNs = 0L
    private var area = 0.0
    private var peak = 0.0
    private var minimumMagnitude = Double.POSITIVE_INFINITY
    private var maximumMagnitude = Double.NEGATIVE_INFINITY
    private var quietNs: Long? = null
    private var lastCommittedEndNs: Long? = null
    private data class Ascent(val start: Long, val turn: Long, val area: Double, val peak: Double,
        val minimumMagnitude: Double, val maximumMagnitude: Double)
    private var ascent: Ascent? = null
    var latestSignal: VerticalPressSignal? = null
        private set
    val candidateStartNs: Long? get() = ascent?.start ?: startNs.takeIf { direction == 1 }

    fun add(sample: SensorSample): ProfiledMotionRepEvent? {
        val a = doubleArrayOf(sample.accelerationX.toDouble(), sample.accelerationY.toDouble(), sample.accelerationZ.toDouble())
        if (sample.sensorAccuracy == 0 || a.any { !it.isFinite() } || sample.timestampNs < 0) {
            reset(); return null
        }
        val previous = lastNs
        if (previous != null && sample.timestampNs <= previous) return null
        if (gravity == null || previous != null && sample.timestampNs - previous > 450_000_000L) {
            reset(); gravity = a; lastNs = sample.timestampNs; return null
        }
        val t = sample.timestampNs
        val dt = (t - requireNotNull(previous)) / 1e9
        lastNs = t
        val g = requireNotNull(gravity)
        val alpha = 1.0 - exp(-dt / 2.5)
        for (i in 0..2) g[i] += (a[i] - g[i]) * alpha
        val magnitude = sqrt(g.sumOf { it * it })
        if (magnitude < 1.0) { reset(); return null }
        val projected = a.indices.sumOf { a[it] * g[it] / magnitude }
        bias += (projected - bias) * alpha
        val acceleration = projected - bias
        velocity = exp(-dt / .75) * velocity + acceleration * dt
        smoothed += (velocity - smoothed) * (1.0 - exp(-dt / .08))
        latestSignal = VerticalPressSignal(t, acceleration, smoothed)
        val rawMagnitude = sqrt(a.sumOf { it * it })
        val next = when { smoothed > profile.minimumFeatureRate -> 1; smoothed < -profile.minimumFeatureRate -> -1; else -> 0 }
        var event: ProfiledMotionRepEvent? = null
        if (direction != 0) {
            minimumMagnitude = minOf(minimumMagnitude, rawMagnitude)
            maximumMagnitude = maxOf(maximumMagnitude, rawMagnitude)
            area += abs(smoothed) * dt
            peak = max(peak, abs(smoothed))
            if (next != 0 && next != direction) event = close(t)
            // Return has slowed after its negative peak. Both signed lobes must already be valid.
            else if (direction == -1 && smoothed > -peak * .5 && area >= profile.minimumLobeDisplacement) event = close(t)
            else if (abs(smoothed) < profile.minimumFeatureRate / 2) {
                val quiet = quietNs
                if (quiet == null) quietNs = t
                else if (t - quiet >= 120_000_000L) event = close(t)
            } else quietNs = null
            if (direction != 0 && t - startNs > profile.maximumLobeMs * 1_000_000L) close(t)
        }
        if (direction == 0 && next != 0) {
            direction = next; startNs = t; area = 0.0; peak = abs(smoothed); quietNs = null
            minimumMagnitude = rawMagnitude; maximumMagnitude = rawMagnitude
        }
        if (ascent?.let { t - it.start > maximumCycleNs } == true) ascent = null
        return event
    }

    /** Keep only the gravity estimate acquired during the stationary preparation interval. */
    fun beginCounting() {
        velocity = 0.0; smoothed = 0.0; direction = 0; ascent = null; quietNs = null
        startNs = 0; area = 0.0; peak = 0.0; latestSignal = null
        lastCommittedEndNs = null
    }

    private fun close(t: Long): ProfiledMotionRepEvent? {
        val valid = t - startNs in profile.minimumLobeMs * 1_000_000L..profile.maximumLobeMs * 1_000_000L &&
            area >= profile.minimumLobeDisplacement
        var result: ProfiledMotionRepEvent? = null
        if (valid && direction == 1) ascent = Ascent(startNs, t, area, peak, minimumMagnitude, maximumMagnitude)
        else if (valid && direction == -1 && ascent != null) {
            val up = requireNotNull(ascent)
            // A rotating gravity vector alone can fool a lagging direction filter. Translation
            // must also change raw acceleration magnitude by at least 0.35 m/s² over the cycle.
            val translationSpan = maxOf(up.maximumMagnitude, maximumMagnitude) - minOf(up.minimumMagnitude, minimumMagnitude)
            val separated = lastCommittedEndNs?.let { up.start - it >= profile.minimumSeparationMs * 1_000_000L } ?: true
            if (t - up.start in profile.minimumRepMs * 1_000_000L..maximumCycleNs && translationSpan >= .35 && separated) {
                result = ProfiledMotionRepEvent(up.start, up.turn, t, (t - up.start) / 1_000_000,
                    max(up.peak, peak), up.area + area, 0.0, .65)
                lastCommittedEndNs = t
            }
            ascent = null
        } else ascent = null
        direction = 0; quietNs = null
        return result
    }

    private fun reset() {
        gravity = null; bias = 9.80665; lastNs = null; beginCounting()
    }
}
