package dev.kyungmin.kcoach.core

import kotlin.math.abs

enum class SignalAxis(val gyro: Boolean) {
    ACCEL_X(false), ACCEL_Y(false), ACCEL_Z(false), GYRO_X(true), GYRO_Y(true), GYRO_Z(true);
    fun value(sample: SensorSample): Double = when (this) {
        ACCEL_X -> sample.accelerationX.toDouble(); ACCEL_Y -> sample.accelerationY.toDouble(); ACCEL_Z -> sample.accelerationZ.toDouble()
        GYRO_X -> sample.gyroscopeX.toDouble(); GYRO_Y -> sample.gyroscopeY.toDouble(); GYRO_Z -> sample.gyroscopeZ.toDouble()
    }
}

data class PaperPeakSettings(val smoothingWindowNs: Long = 2_000_000_000L, val thresholdFraction: Double = 0.25,
    val minimumAccelerationSpan: Double = 0.25, val minimumGyroSpan: Double = 0.12,
    val minimumRepNs: Long = 350_000_000L, val maximumRepNs: Long = 8_000_000_000L) {
    init { require(smoothingWindowNs > 0 && thresholdFraction in 0.05..0.45 && minimumAccelerationSpan > 0 &&
        minimumGyroSpan > 0 && minimumRepNs > 0 && maximumRepNs > minimumRepNs) }
}

data class PeakRepAnalysis(val estimatedCount: Int?, val cycles: List<RepCycle>, val axis: SignalAxis?,
    val filteredSignal: List<SignalPoint>, val rejectionReason: String?, val lookaheadNs: Long,
    val engineId: String = "paper-sg-peak-pair-v1")

/**
 * Offline comparison candidate inspired by Prabhu et al. (Sensors 2020, 20, 4791).
 * A ~2s fourth-order SG fit and dual-threshold extrema are shared. Watch8 axis selection,
 * robust range thresholds and complete-return checks are adaptations, not published accuracy.
 * Acceleration/gyro axes compete only within their own unit space; no target count is accepted.
 */
object PaperPeakRepAnalyzer {
    fun analyze(document: SensorCaptureDocument, exerciseId: String, settings: PaperPeakSettings = PaperPeakSettings()): PeakRepAnalysis {
        fun rejected(reason: String) = PeakRepAnalysis(null, emptyList(), null, emptyList(), reason, settings.smoothingWindowNs / 2)
        val tracking = ExerciseTrackingProfiles.resolve(exerciseId)
        if (tracking.mode == TrackingMode.MANUAL_REQUIRED) return rejected("manual_required")
        if (document.records.size < 15 || document.validateTimestamps().isNotEmpty()) return rejected("insufficient_or_invalid_samples")
        val contract = ExerciseMeasurementContracts.resolve(exerciseId)
        val gyro = contract.recipe.engine == MeasurementEngine.ROTATION
        val choices = SignalAxis.entries.filter { it.gyro == gyro }
        val axis = choices.maxBy { candidate ->
            val mean = document.records.sumOf { candidate.value(it.sample) } / document.records.size
            document.records.sumOf { record -> val value = candidate.value(record.sample) - mean; value * value } / document.records.size
        }
        val segments = mutableListOf<MutableList<SignalPoint>>()
        document.records.forEach { record ->
            val point = SignalPoint(record.sample.timestampNs, axis.value(record.sample))
            if (segments.isEmpty() || point.timestampNs - segments.last().last().timestampNs > 450_000_000L) segments.add(mutableListOf())
            segments.last().add(point)
        }
        val allFiltered = mutableListOf<SignalPoint>()
        val cycles = mutableListOf<RepCycle>()
        var observableSegments = 0
        segments.forEach { points ->
            if (points.size < 15) return@forEach
            val filtered = SavitzkyGolayFilter.smooth(points, settings.smoothingWindowNs, 4)
            allFiltered.addAll(filtered)
            val ordered = filtered.map { it.value }.sorted()
            val low = percentile(ordered, 0.10); val high = percentile(ordered, 0.90)
            val span = high - low
            if (span < if (gyro) settings.minimumGyroSpan else settings.minimumAccelerationSpan) return@forEach
            observableSegments++
            val baseline = filtered.takeWhile { it.timestampNs - filtered.first().timestampNs <= 500_000_000L }
                .map { it.value }.sorted().let { it[it.size / 2] }
            val invert = abs(baseline - high) < abs(baseline - low)
            val oriented = if (invert) filtered.map { it.copy(value = -it.value) } else filtered
            val lower = (if (invert) -high else low) + span * settings.thresholdFraction
            val upper = (if (invert) -low else high) - span * settings.thresholdFraction
            cycles.addAll(findCycles(oriented, lower, upper, span, contract.departurePhase, settings))
        }
        return if (observableSegments == 0) PeakRepAnalysis(null, emptyList(), axis, allFiltered, "signal_not_observable",
            settings.smoothingWindowNs / 2) else PeakRepAnalysis(cycles.size, cycles, axis, allFiltered,
            if (segments.size > 1) "sensor_gap_partial_estimate" else null, settings.smoothingWindowNs / 2)
    }

    private fun findCycles(points: List<SignalPoint>, lower: Double, upper: Double, span: Double,
                           phase: MusclePhase, settings: PaperPeakSettings): List<RepCycle> {
        val cycles = mutableListOf<RepCycle>()
        val epsilon = span * 0.002
        var anchor: Int? = null
        var peak: Int? = null
        var returning: Int? = null
        var stableSince: Long? = null
        fun finish(end: Int) {
            val start = anchor ?: return
            val turn = peak ?: return
            val a = points[start].timestampNs; val b = points[turn].timestampNs; val c = points[end].timestampNs
            if (c - a in settings.minimumRepNs..settings.maximumRepNs && b - a >= 120_000_000L && c - b >= 120_000_000L &&
                points[end].value <= points[start].value + span * 0.15) cycles.add(RepCycle(a, b, c, phase))
            anchor = end; peak = null; returning = null; stableSince = null
        }
        points.forEachIndexed { index, point ->
            if (peak == null) {
                if (point.value <= lower) {
                    val start = anchor
                    if (start == null || point.value <= points[start].value + epsilon) anchor = index
                }
                if (anchor != null && point.value >= upper) peak = index
            } else {
                val maximum = requireNotNull(peak)
                if (point.value > points[maximum].value) peak = index
                if (point.value <= lower) {
                    val minimum = returning
                    if (minimum == null || point.value < points[minimum].value - epsilon) {
                        returning = index; stableSince = point.timestampNs
                    } else if (point.value > points[minimum].value + epsilon || point.timestampNs - (stableSince ?: point.timestampNs) >= 120_000_000L) {
                        finish(minimum)
                    }
                } else if (returning != null) {
                    val minimum = returning!!
                    finish(minimum)
                    if (point.value >= upper) peak = index
                }
            }
        }
        returning?.let { minimum ->
            if (points.last().value <= lower && points.last().timestampNs - points[minimum].timestampNs >= 120_000_000L) finish(minimum)
        }
        return cycles
    }

    private fun percentile(sorted: List<Double>, proportion: Double): Double {
        val position = proportion * (sorted.size - 1)
        val before = position.toInt(); val after = minOf(before + 1, sorted.lastIndex)
        return sorted[before] + (sorted[after] - sorted[before]) * (position - before)
    }
}
