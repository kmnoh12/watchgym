package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects one controlled out-and-back forearm rotation from a wrist IMU.
 *
 * The rotation axis is a learned 3D vector, not one permanently selected device
 * X/Y/Z axis. That matters on a cable external rotation because the watch frame
 * curves with the forearm through both halves of the repetition.
 */
data class ForearmRotationRepEvent(
    val startTimestampNs: Long,
    val turnaroundTimestampNs: Long,
    val endTimestampNs: Long,
    val durationMs: Long,
    val peakAngularVelocity: Double,
    val angularDisplacement: Double,
    val confidence: Double,
)

data class ForearmRotationDiagnostics(
    val lowRateSamples: Int,
    val misalignedSamples: Int,
    val accelerationShockSamples: Int,
    val rejectedAngleLobes: Int,
    val rejectedDurationLobes: Int,
    val rejectedRepDurations: Int,
    val axisLearningResets: Int,
    val detectedReps: Int,
)

class ForearmRotationRepDetector(
    private val profile: ExerciseTrackingProfile? = null,
) {
    companion object {
        private const val DEFAULT_MIN_PROJECTED_RATE = 0.12
        private const val MIN_AXIS_ALIGNMENT = 0.22
        private const val MAX_DYNAMIC_ACCELERATION = 6.0
        private const val DEFAULT_MIN_LOBE_ANGLE = 0.22
        private const val DEFAULT_MIN_LOBE_MS = 100L
        private const val DEFAULT_MAX_LOBE_MS = 2_100L
        private const val MAX_SAMPLE_GAP_MS = 450L
        private const val END_SETTLE_MS = 160L
        private const val AXIS_LEARNING_SAMPLES = 4
        private const val AXIS_ADAPT_ALPHA = 0.10
    }

    private val preferredAxis = profile?.preferredAxis?.takeIf { it in 0..2 }
    private val legacyLobes = profile != null && profile.id.substringBefore('+') == MotionFamily.FOREARM_ROTATION.legacyAlgorithmId
    private val minimumProjectedRate = max(
        profile?.minimumFeatureRate ?: DEFAULT_MIN_PROJECTED_RATE,
        (profile?.gyroNoiseFloor ?: 0.0) * 2.2,
    ).coerceIn(0.07, 3.0)
    private val minimumGyroNorm = (minimumProjectedRate * 0.82).coerceAtLeast(0.07)
    private val minimumLobeAngle = (profile?.minimumLobeDisplacement ?: DEFAULT_MIN_LOBE_ANGLE)
        .coerceIn(0.10, 3.5)
    private val minimumLobeMs = profile?.minimumLobeMs ?: DEFAULT_MIN_LOBE_MS
    private val maximumLobeMs = profile?.maximumLobeMs ?: DEFAULT_MAX_LOBE_MS
    private val minimumRepMs = profile?.minimumRepMs ?: 350L
    private val maximumRepMs = profile?.maximumRepMs ?: 4_500L

    private val axis = DoubleArray(3)
    private var axisReady = false
    private val learningSum = DoubleArray(3)
    private var learningSamples = 0
    private var learningStartNs = 0L
    private var learningAngle = 0.0
    private var learningPeakRate = 0.0

    private var direction = 0
    private var lobeStartNs = 0L
    private var lobeAngle = 0.0
    private var lobePeakRate = 0.0
    private var firstLobeStartNs = 0L
    private var firstLobeEndNs = 0L
    private var firstLobePeakRate = 0.0
    private var firstLobeAngle = 0.0
    private var firstLobeDirection = 0
    private var lastTimestampNs = 0L
    private var quietSinceNs = 0L
    var lastMotionNs: Long = 0L
        private set

    private var lowRateSamples = 0
    private var misalignedSamples = 0
    private var accelerationShockSamples = 0
    private var rejectedAngleLobes = 0
    private var rejectedDurationLobes = 0
    private var rejectedRepDurations = 0
    private var axisLearningResets = 0
    private var detectedReps = 0

    init {
        preferredAxis?.let { setAxisBasis(it) }
    }

    val candidateStartNs: Long? get() = firstLobeStartNs.takeIf { it > 0L }
        ?: lobeStartNs.takeIf { it > 0L && lobeAngle >= minimumLobeAngle }

    fun add(sample: SensorSample): ForearmRotationRepEvent? {
        if (sample.sensorAccuracy == 0) {
            resetMotion(releaseAxis = preferredAxis == null); lastTimestampNs = 0L
            return null
        }
        val timestampNs = sample.timestampNs
        if (lastTimestampNs > 0L && timestampNs <= lastTimestampNs) return null
        val dtMs = if (lastTimestampNs > 0L) {
            ((timestampNs - lastTimestampNs) / 1_000_000L).coerceAtLeast(0L)
        } else {
            0L
        }
        lastTimestampNs = timestampNs
        if (dtMs > MAX_SAMPLE_GAP_MS) resetMotion(releaseAxis = preferredAxis == null)

        val vector = doubleArrayOf(
            sample.gyroscopeX.toDouble(),
            sample.gyroscopeY.toDouble(),
            sample.gyroscopeZ.toDouble(),
        )
        val norm = vector.norm()
        val accelNorm = sqrt(
            sample.accelerationX * sample.accelerationX +
                sample.accelerationY * sample.accelerationY +
                sample.accelerationZ * sample.accelerationZ,
        ).toDouble()
        val dynamicAcceleration = abs(accelNorm - 9.80665)
        if (dynamicAcceleration > MAX_DYNAMIC_ACCELERATION) {
            accelerationShockSamples += 1
            return observeQuiet(timestampNs, controlled = false)
        }
        if (norm < minimumGyroNorm) {
            lowRateSamples += 1
            return observeQuiet(timestampNs, controlled = true)
        }

        if (!axisReady) {
            return learnAxis(vector, norm, timestampNs, dtMs)
        }

        var projected = vector.dot(axis)
        var alignment = abs(projected) / norm.coerceAtLeast(1e-6)
        if (alignment < MIN_AXIS_ALIGNMENT || abs(projected) < minimumProjectedRate) {
            if (direction == 0 && firstLobeStartNs == 0L && norm >= minimumProjectedRate * 1.5) {
                // Between reps a clearly stronger motion may establish a fresh axis.
                releaseLearnedAxis()
                return learnAxis(vector, norm, timestampNs, dtMs)
            }
            if (alignment < MIN_AXIS_ALIGNMENT) misalignedSamples += 1 else lowRateSamples += 1
            return observeQuiet(timestampNs, controlled = true)
        }

        adaptAxis(vector, norm, projected)
        projected = vector.dot(axis)
        alignment = abs(projected) / norm.coerceAtLeast(1e-6)
        if (alignment < MIN_AXIS_ALIGNMENT || abs(projected) < minimumProjectedRate) {
            if (alignment < MIN_AXIS_ALIGNMENT) misalignedSamples += 1 else lowRateSamples += 1
            return observeQuiet(timestampNs, controlled = true)
        }

        quietSinceNs = 0L
        lastMotionNs = timestampNs
        val nextDirection = if (projected > 0.0) 1 else -1
        val magnitude = abs(projected)
        if (direction == 0) {
            startLobe(timestampNs, nextDirection, magnitude)
            return null
        }
        if (nextDirection == direction) {
            if (dtMs in 1..MAX_SAMPLE_GAP_MS) lobeAngle += magnitude * (dtMs / 1_000.0)
            lobePeakRate = max(lobePeakRate, magnitude)
            return null
        }

        val completed = closeLobe(timestampNs)
        startLobe(timestampNs, nextDirection, magnitude)
        return completed
    }

    /** New engines require observed settling/reversal; a manual stop cannot fabricate a return. */
    fun finish(): ForearmRotationRepEvent? {
        if (!legacyLobes) { resetMotion(releaseAxis = false); return null }
        if (direction == 0 || lastTimestampNs == 0L) return null
        val completed = closeLobe(lastMotionNs.takeIf { it > 0L } ?: lastTimestampNs)
        resetMotion(releaseAxis = false)
        return completed
    }

    fun beginCounting() {
        resetMotion(releaseAxis = preferredAxis == null)
        lastTimestampNs = 0L
    }

    fun diagnostics(): ForearmRotationDiagnostics = ForearmRotationDiagnostics(
        lowRateSamples = lowRateSamples,
        misalignedSamples = misalignedSamples,
        accelerationShockSamples = accelerationShockSamples,
        rejectedAngleLobes = rejectedAngleLobes,
        rejectedDurationLobes = rejectedDurationLobes,
        rejectedRepDurations = rejectedRepDurations,
        axisLearningResets = axisLearningResets,
        detectedReps = detectedReps,
    )

    private fun learnAxis(
        vector: DoubleArray,
        norm: Double,
        timestampNs: Long,
        dtMs: Long,
    ): ForearmRotationRepEvent? {
        val unit = DoubleArray(3) { vector[it] / norm.coerceAtLeast(1e-6) }
        if (learningSamples > 0 && unit.dot(learningSum) < 0.0) {
            resetAxisLearning(countReset = true)
        }
        if (learningSamples == 0) learningStartNs = timestampNs
        for (index in 0..2) learningSum[index] += unit[index] * norm
        learningSamples += 1
        if (dtMs in 1..MAX_SAMPLE_GAP_MS) learningAngle += norm * (dtMs / 1_000.0)
        learningPeakRate = max(learningPeakRate, norm)
        lastMotionNs = timestampNs
        quietSinceNs = 0L
        if (learningSamples < AXIS_LEARNING_SAMPLES) return null

        setAxis(learningSum)
        direction = 1
        lobeStartNs = learningStartNs
        lobeAngle = learningAngle
        lobePeakRate = learningPeakRate
        resetAxisLearning(countReset = false)
        return null
    }

    private fun adaptAxis(vector: DoubleArray, norm: Double, projected: Double) {
        val sign = if (projected >= 0.0) 1.0 else -1.0
        val target = DoubleArray(3) { sign * vector[it] / norm.coerceAtLeast(1e-6) }
        for (index in 0..2) {
            axis[index] = axis[index] * (1.0 - AXIS_ADAPT_ALPHA) + target[index] * AXIS_ADAPT_ALPHA
        }
        normalize(axis)
    }

    private fun startLobe(timestampNs: Long, nextDirection: Int, magnitude: Double) {
        direction = nextDirection
        lobeStartNs = timestampNs
        lobeAngle = 0.0
        lobePeakRate = magnitude
    }

    private fun closeLobe(endTimestampNs: Long): ForearmRotationRepEvent? {
        if (lobeStartNs == 0L) return null
        val durationMs = ((endTimestampNs - lobeStartNs) / 1_000_000L).coerceAtLeast(0L)
        val angleValid = lobeAngle >= minimumLobeAngle
        val durationValid = durationMs in minimumLobeMs..maximumLobeMs
        if (!angleValid || !durationValid) {
            if (!angleValid) rejectedAngleLobes += 1
            if (!durationValid) rejectedDurationLobes += 1
            resetMotion(releaseAxis = preferredAxis == null)
            return null
        }

        if (firstLobeStartNs == 0L || !legacyLobes && firstLobeDirection == direction) {
            firstLobeStartNs = lobeStartNs
            firstLobeEndNs = endTimestampNs
            firstLobePeakRate = lobePeakRate
            firstLobeAngle = lobeAngle
            firstLobeDirection = direction
            return null
        }

        val repDurationMs = ((endTimestampNs - firstLobeStartNs) / 1_000_000L).coerceAtLeast(0L)
        val event = if (repDurationMs in minimumRepMs..maximumRepMs) {
            ForearmRotationRepEvent(
                startTimestampNs = firstLobeStartNs,
                turnaroundTimestampNs = firstLobeEndNs,
                endTimestampNs = endTimestampNs,
                durationMs = repDurationMs,
                peakAngularVelocity = max(firstLobePeakRate, lobePeakRate),
                angularDisplacement = firstLobeAngle + lobeAngle,
                confidence = confidence(repDurationMs, firstLobeAngle + lobeAngle),
            ).also { detectedReps += 1 }
        } else {
            rejectedRepDurations += 1
            null
        }
        clearFirstLobe()
        return event
    }

    private fun observeQuiet(timestampNs: Long, controlled: Boolean): ForearmRotationRepEvent? {
        if (!controlled) {
            quietSinceNs = 0L
            return null
        }
        if (!axisReady) {
            if (learningSamples == 0) return null
            if (quietSinceNs == 0L) quietSinceNs = timestampNs
            if ((timestampNs - quietSinceNs) / 1_000_000L >= END_SETTLE_MS) {
                resetAxisLearning(countReset = true)
                quietSinceNs = 0L
            }
            return null
        }
        if (direction == 0 || lastMotionNs == 0L) return null
        if (quietSinceNs == 0L) quietSinceNs = timestampNs
        if ((timestampNs - quietSinceNs) / 1_000_000L < END_SETTLE_MS) return null

        val completed = closeLobe(lastMotionNs)
        clearActiveLobe()
        quietSinceNs = 0L
        return completed
    }

    private fun confidence(durationMs: Long, displacement: Double): Double {
        val targetDuration = ((minimumRepMs + maximumRepMs) / 2.0).coerceAtLeast(1.0)
        val durationScore = (1.0 - abs(durationMs - targetDuration) / targetDuration).coerceIn(0.0, 1.0)
        val angleScore = ((displacement - minimumLobeAngle * 2.0) / 1.6).coerceIn(0.0, 1.0)
        return (0.55 + durationScore * 0.20 + angleScore * 0.25).coerceIn(0.0, 1.0)
    }

    private fun resetMotion(releaseAxis: Boolean) {
        clearActiveLobe()
        clearFirstLobe()
        quietSinceNs = 0L
        lastMotionNs = 0L
        resetAxisLearning(countReset = false)
        if (releaseAxis) releaseLearnedAxis()
    }

    private fun clearActiveLobe() {
        direction = 0
        lobeStartNs = 0L
        lobeAngle = 0.0
        lobePeakRate = 0.0
    }

    private fun clearFirstLobe() {
        firstLobeStartNs = 0L
        firstLobeEndNs = 0L
        firstLobePeakRate = 0.0
        firstLobeAngle = 0.0
        firstLobeDirection = 0
    }

    private fun resetAxisLearning(countReset: Boolean) {
        if (countReset && learningSamples > 0) axisLearningResets += 1
        learningSum.fill(0.0)
        learningSamples = 0
        learningStartNs = 0L
        learningAngle = 0.0
        learningPeakRate = 0.0
    }

    private fun releaseLearnedAxis() {
        if (preferredAxis == null) {
            axis.fill(0.0)
            axisReady = false
        } else {
            setAxisBasis(preferredAxis)
        }
    }

    private fun setAxisBasis(index: Int) {
        axis.fill(0.0)
        axis[index] = 1.0
        axisReady = true
    }

    private fun setAxis(source: DoubleArray) {
        source.copyInto(axis)
        normalize(axis)
        axisReady = axis.norm() > 0.99
    }

    private fun normalize(vector: DoubleArray) {
        val magnitude = vector.norm().coerceAtLeast(1e-9)
        for (index in vector.indices) vector[index] /= magnitude
    }

    private fun DoubleArray.norm(): Double = sqrt(sumOf { it * it })
    private fun DoubleArray.dot(other: DoubleArray): Double = indices.sumOf { this[it] * other[it] }
}
