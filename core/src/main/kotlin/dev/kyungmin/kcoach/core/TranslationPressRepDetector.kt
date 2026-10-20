package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Translation-dominant press cycle for bench presses and fixed-wrist pushdowns.
 *
 * The existing combined press family integrates a gyro-dominant feature with a
 * 0.10 gravity filter (about 0.2 s at 50 Hz), so a slow 30 cm bench stroke is
 * removed as "gravity" while a 5 cm wrist flick passes on gyro. This detector
 * instead estimates gravity with a 2.5 s constant, projects the acceleration on
 * gravity, integrates a leaky velocity proxy and pairs two opposite travel
 * lobes. It starts on either lobe, so a set beginning at the top counts its
 * first repetition.
 *
 * [ProfiledMotionRepEvent.motionDisplacement] is the area of the velocity proxy,
 * roughly proportional to travel distance but not a measured distance. A 5 cm
 * wrist bob stays below [MIN_TOTAL_DISPLACEMENT] across tempos; a 30 cm bench or
 * pushdown stroke passes it. Constants are provisional and need Watch8 captures.
 */
class TranslationPressRepDetector(
    private val profile: ExerciseTrackingProfile,
) {
    companion object {
        private const val GRAVITY_TAU_S = 2.5
        private const val VELOCITY_TAU_S = 0.75
        private const val SMOOTHING_TAU_S = 0.08
        private const val MAX_SAMPLE_GAP_NS = 450_000_000L
        private const val QUIET_CLOSE_NS = 160_000_000L
        private const val MOTION_GYRO_FLOOR = 0.25
        private const val MOTION_ACCEL_FLOOR = 0.25
        private const val STANDARD_GRAVITY = 9.80665

        /** Velocity-proxy area floor. A 5 cm wrist bob stays below it; a 30 cm bench stroke is about 0.5. */
        const val MIN_TOTAL_DISPLACEMENT = 0.24

        /** A rotation-only wrist motion does not change raw acceleration magnitude this much. */
        const val MIN_TRANSLATION_SPAN = 0.35

        /** Bench and pushdown half-cycles can take 3 s each at a very slow tempo. */
        const val MAX_LOBE_MS = 5_000L
    }

    private data class Lobe(
        val startNs: Long,
        val endNs: Long,
        val area: Double,
        val peak: Double,
        val minimumMagnitude: Double,
        val maximumMagnitude: Double,
    )

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
    private var first: Lobe? = null
    private var lastCommittedEndNs: Long? = null
    var latestSignal: Double = 0.0
        private set
    var lastMotionNs: Long = 0L
        private set
    val candidateStartNs: Long? get() = first?.startNs ?: startNs.takeIf { it > 0L && direction != 0 }

    val maximumCycleNs: Long get() = profile.maximumRepMs * 1_000_000L

    fun add(sample: SensorSample): ProfiledMotionRepEvent? {
        val a = doubleArrayOf(sample.accelerationX.toDouble(), sample.accelerationY.toDouble(), sample.accelerationZ.toDouble())
        if (sample.sensorAccuracy == 0 || a.any { !it.isFinite() } || sample.timestampNs < 0) {
            reset(); return null
        }
        val previous = lastNs
        if (previous != null && sample.timestampNs <= previous) return null
        if (gravity == null || previous != null && sample.timestampNs - previous > MAX_SAMPLE_GAP_NS) {
            reset(); gravity = a; lastNs = sample.timestampNs; return null
        }
        val t = sample.timestampNs
        val dt = (t - requireNotNull(previous)) / 1e9
        lastNs = t
        val g = requireNotNull(gravity)
        val alpha = 1.0 - exp(-dt / GRAVITY_TAU_S)
        for (i in 0..2) g[i] += (a[i] - g[i]) * alpha
        val magnitude = sqrt(g.sumOf { it * it })
        if (magnitude < 1.0) { reset(); return null }
        val projected = a.indices.sumOf { a[it] * g[it] / magnitude }
        bias += (projected - bias) * alpha
        val acceleration = projected - bias
        velocity = exp(-dt / VELOCITY_TAU_S) * velocity + acceleration * dt
        smoothed += (velocity - smoothed) * (1.0 - exp(-dt / SMOOTHING_TAU_S))
        latestSignal = smoothed
        val rawMagnitude = sqrt(a.sumOf { it * it })
        if (rawMagnitude.isFinite() &&
            (abs(smoothed) >= profile.minimumFeatureRate * 0.5 || abs(rawMagnitude - STANDARD_GRAVITY) >= MOTION_ACCEL_FLOOR)) {
            lastMotionNs = t
        }
        val next = when {
            smoothed > profile.minimumFeatureRate -> 1
            smoothed < -profile.minimumFeatureRate -> -1
            else -> 0
        }
        var event: ProfiledMotionRepEvent? = null
        if (direction != 0) {
            minimumMagnitude = minOf(minimumMagnitude, rawMagnitude)
            maximumMagnitude = maxOf(maximumMagnitude, rawMagnitude)
            area += abs(smoothed) * dt
            peak = max(peak, abs(smoothed))
            if (next != 0 && next != direction) event = close(t)
            else if (abs(smoothed) < profile.minimumFeatureRate / 2) {
                val quiet = quietNs
                if (quiet == null) quietNs = t
                else if (t - quiet >= QUIET_CLOSE_NS) event = close(t)
            } else quietNs = null
            if (direction != 0 && t - startNs > MAX_LOBE_MS * 1_000_000L) close(t)
        }
        if (direction == 0 && next != 0) {
            direction = next
            startNs = t
            area = 0.0
            peak = abs(smoothed)
            quietNs = null
            minimumMagnitude = rawMagnitude
            maximumMagnitude = rawMagnitude
        }
        return event
    }

    fun finish(): ProfiledMotionRepEvent? = null

    fun finishAll(): List<ProfiledMotionRepEvent> = emptyList()

    /** Keep the gravity estimate learned while the wrist was held still. */
    fun beginCounting() {
        velocity = 0.0; smoothed = 0.0; direction = 0; quietNs = null
        startNs = 0L; area = 0.0; peak = 0.0; latestSignal = 0.0
        first = null; lastCommittedEndNs = null; lastMotionNs = 0L
    }

    private fun close(t: Long): ProfiledMotionRepEvent? {
        val active = Lobe(startNs, t, area, peak, minimumMagnitude, maximumMagnitude)
        direction = 0
        quietNs = null
        val completed = first
        var result: ProfiledMotionRepEvent? = null
        if (completed == null) {
            if (valid(active)) first = active else first = null
        } else {
            if (valid(active)) {
                val displacement = completed.area + active.area
                val span = maxOf(completed.maximumMagnitude, active.maximumMagnitude) -
                    minOf(completed.minimumMagnitude, active.minimumMagnitude)
                val duration = active.endNs - completed.startNs
                val separated = lastCommittedEndNs?.let {
                    completed.startNs - it >= profile.minimumSeparationMs * 1_000_000L
                } ?: true
                if (duration in profile.minimumRepMs * 1_000_000L..maximumCycleNs &&
                    displacement >= MIN_TOTAL_DISPLACEMENT && span >= MIN_TRANSLATION_SPAN && separated) {
                    result = ProfiledMotionRepEvent(
                        startTimestampNs = completed.startNs,
                        turnaroundTimestampNs = completed.endNs,
                        endTimestampNs = active.endNs,
                        durationMs = duration / 1_000_000L,
                        peakMotionRate = max(completed.peak, active.peak),
                        motionDisplacement = displacement,
                        trajectorySpread = 0.0,
                        confidence = 0.65,
                    )
                    lastCommittedEndNs = active.endNs
                }
            }
            first = null
        }
        return result
    }

    private fun valid(lobe: Lobe): Boolean {
        val durationMs = (lobe.endNs - lobe.startNs) / 1_000_000L
        return durationMs in profile.minimumLobeMs..MAX_LOBE_MS &&
            lobe.area >= profile.minimumLobeDisplacement
    }

    private fun reset() {
        gravity = null; bias = 9.80665; lastNs = null; beginCounting()
    }

}
