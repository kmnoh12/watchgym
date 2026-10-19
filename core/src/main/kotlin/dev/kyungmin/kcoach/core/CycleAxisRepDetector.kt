package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Counts one-axis rhythmic wrist cycles (rows, lateral raises, triceps extensions) without gravity or a fixed sensor axis.
 *
 * Every [STEP_NS] the whole history since [beginCounting] is re-analysed: the principal axis of the smoothed gyroscope and of
 * the smoothed dynamic acceleration is found, the more regular candidate (lowest interval variation) is kept, and its
 * peaks are counted. A peak is final [CONFIRM_NS] after it happened, so reps appear 1-2 s late. The count never decreases.
 * Peaks are relative to the set's own 95th percentile, so an absolute floor ([GYRO_AMPLITUDE_FLOOR], [DYN_AMPLITUDE_FLOOR])
 * refuses sets whose motion is too small to be a rep of these exercises (a still wrist or a few-centimetre fidget).
 * The floors sit well below the weakest real sets seen: one Watch8 cable seated row (gyro 0.83 rad/s) and MM-Fit rows,
 * lateral raises and triceps extensions (gyro min 0.72 / dynamic acceleration min 3.4 m/s^2); they are not tuned per user.
 * Constants are frozen with this algorithm ID (cycle-axis-return-family-v1); changing them requires a new version.
 * Developed on MM-Fit left-wrist data plus one Galaxy Watch8 cable seated row; it is not a validated Watch8 accuracy claim.
 */
class CycleAxisRepDetector {
    companion object {
        private const val MIN_SAMPLE_GAP_NS = 38_000_000L
        private const val STEP_NS = 1_500_000_000L
        private const val FIRST_ANALYSIS_NS = 4_000_000_000L
        private const val CONFIRM_NS = 600_000_000L
        private const val MIN_SEPARATION_NS = 800_000_000L
        private const val MAX_GAP_NS = 2_000_000_000L
        private const val MAX_HISTORY = 30_000
        private const val GYRO_SMOOTH = 3
        private const val GRAVITY_WINDOW = 50
        private const val PROJECTION_SMOOTH = 7
        private const val PEAK_HALF_WINDOW = 8
        private const val PEAK_FRACTION = 0.40
        private const val MIN_PEAKS = 3
        private const val MIN_MEDIAN_S = 1.0
        private const val MAX_MEDIAN_S = 7.0
        private const val GYRO_AMPLITUDE_FLOOR = 0.4 // rad/s
        private const val DYN_AMPLITUDE_FLOOR = 1.5 // m/s^2
    }

    private class TrailingMean(private val window: Int) {
        private val ring = Array(window) { DoubleArray(3) }
        private val sum = DoubleArray(3)
        private var size = 0
        private var next = 0
        fun push(v: DoubleArray, out: DoubleArray) {
            if (size == window) { for (c in 0..2) sum[c] -= ring[next][c] } else size++
            for (c in 0..2) { ring[next][c] = v[c]; sum[c] += v[c] }
            next = (next + 1) % window
            for (c in 0..2) out[c] = sum[c] / size
        }
    }

    private class Series {
        var data = DoubleArray(4096)
        var size = 0
        fun add(v: Double) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun clear() { size = 0 }
    }

    private val times = LongArray(MAX_HISTORY)
    private var count = 0
    private val gyro = Array(3) { Series() }
    private val dyn = Array(3) { Series() }
    private var gyroMean = TrailingMean(GYRO_SMOOTH)
    private var gravityMean = TrailingMean(GRAVITY_WINDOW)
    private var dynMean = TrailingMean(GYRO_SMOOTH)
    private var lastNs = 0L
    private var startNs = 0L
    private var nextAnalysisNs = 0L
    private var emitted = 0
    private val pending = ArrayDeque<ProfiledMotionRepEvent>()

    val maximumCycleNs: Long get() = (MAX_MEDIAN_S * 1e9).toLong()

    fun beginCounting() {
        count = 0; gyro.forEach { it.clear() }; dyn.forEach { it.clear() }
        gyroMean = TrailingMean(GYRO_SMOOTH); gravityMean = TrailingMean(GRAVITY_WINDOW); dynMean = TrailingMean(GYRO_SMOOTH)
        lastNs = 0L; startNs = 0L; nextAnalysisNs = 0L; emitted = 0; pending.clear()
    }

    fun add(sample: SensorSample): ProfiledMotionRepEvent? {
        val t = sample.timestampNs
        val a = doubleArrayOf(sample.accelerationX.toDouble(), sample.accelerationY.toDouble(), sample.accelerationZ.toDouble())
        val g = doubleArrayOf(sample.gyroscopeX.toDouble(), sample.gyroscopeY.toDouble(), sample.gyroscopeZ.toDouble())
        if (sample.sensorAccuracy == 0 || a.any { !it.isFinite() } || g.any { !it.isFinite() } || t < 0) return pending.removeFirstOrNull()
        if (lastNs != 0L) {
            if (t <= lastNs || t - lastNs < MIN_SAMPLE_GAP_NS) return pending.removeFirstOrNull()
            if (t - lastNs > MAX_GAP_NS) beginCounting()
        }
        if (count >= MAX_HISTORY) beginCounting()
        lastNs = t
        if (startNs == 0L) { startNs = t; nextAnalysisNs = t + FIRST_ANALYSIS_NS }
        val smoothedGyro = DoubleArray(3); gyroMean.push(g, smoothedGyro)
        val gravity = DoubleArray(3); gravityMean.push(a, gravity)
        val dynamic = DoubleArray(3) { a[it] - gravity[it] }
        val smoothedDyn = DoubleArray(3); dynMean.push(dynamic, smoothedDyn)
        times[count++] = t
        for (c in 0..2) { gyro[c].add(smoothedGyro[c]); dyn[c].add(smoothedDyn[c]) }
        if (t >= nextAnalysisNs) { nextAnalysisNs = t + STEP_NS; analyze(t, CONFIRM_NS) }
        return pending.removeFirstOrNull()
    }

    /** Final analysis at the end of a stream; returns every rep that has not been delivered yet. */
    fun finishAll(): List<ProfiledMotionRepEvent> {
        if (count > 0) analyze(lastNs, 0L)
        val rest = pending.toList(); pending.clear()
        return rest
    }

    private class Candidate(val score: Double, val peaksNs: LongArray, val amplitude: Double, val medianS: Double)

    private fun analyze(nowNs: Long, confirmNs: Long) {
        if (count < 40) return
        var best: Candidate? = null
        for (series in arrayOf(gyro, dyn)) {
            val projected = project(series)
            val sorted = projected.map { abs(it) }.sorted()
            val p95 = max(sorted[(0.95 * (sorted.size - 1)).toInt()], 1e-9)
            if (p95 < (if (series === gyro) GYRO_AMPLITUDE_FLOOR else DYN_AMPLITUDE_FLOOR)) continue
            for (sign in intArrayOf(1, -1)) {
                val peaks = findPeaks(projected, sign, p95)
                if (peaks.size < MIN_PEAKS) continue
                val intervals = DoubleArray(peaks.size - 1) { (times[peaks[it + 1]] - times[peaks[it]]) / 1e9 }
                val median = intervals.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0 }
                if (median < MIN_MEDIAN_S || median > MAX_MEDIAN_S) continue
                val mean = intervals.average()
                val deviation = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
                val score = deviation / mean - 0.02 * minOf(peaks.size, 10)
                if (best == null || score < best.score) best = Candidate(score, LongArray(peaks.size) { times[peaks[it]] }, p95, median)
            }
        }
        val chosen = best ?: return
        val confirmed = chosen.peaksNs.count { it <= nowNs - confirmNs }
        for (index in emitted until confirmed) {
            val peak = chosen.peaksNs[index]
            val previous = if (index > 0) chosen.peaksNs[index - 1] else peak - (chosen.medianS * 1e9).toLong()
            pending.addLast(ProfiledMotionRepEvent(previous, peak, peak, (peak - previous) / 1_000_000L, chosen.amplitude, 0.0, 0.0, 0.7))
        }
        if (confirmed > emitted) emitted = confirmed
    }

    private fun project(series: Array<Series>): DoubleArray {
        val n = count
        val mean = DoubleArray(3) { c -> var s = 0.0; for (i in 0 until n) s += series[c].data[i]; s / n }
        val cov = Array(3) { DoubleArray(3) }
        for (a in 0..2) for (b in a..2) {
            var s = 0.0
            for (i in 0 until n) s += (series[a].data[i] - mean[a]) * (series[b].data[i] - mean[b])
            cov[a][b] = s / n; cov[b][a] = s / n
        }
        var v = doubleArrayOf(1.0, 0.7, 0.3)
        repeat(40) {
            val w = DoubleArray(3) { a -> cov[a][0] * v[0] + cov[a][1] * v[1] + cov[a][2] * v[2] }
            val norm = sqrt(w.sumOf { it * it }).let { if (it == 0.0) 1.0 else it }
            v = DoubleArray(3) { w[it] / norm }
        }
        val raw = DoubleArray(n) { i -> (0..2).sumOf { c -> (series[c].data[i] - mean[c]) * v[c] } }
        val smoothed = DoubleArray(n)
        var running = 0.0
        for (i in 0 until n) {
            running += raw[i]
            if (i >= PROJECTION_SMOOTH) running -= raw[i - PROJECTION_SMOOTH]
            smoothed[i] = running / minOf(i + 1, PROJECTION_SMOOTH)
        }
        return smoothed
    }

    private fun findPeaks(p: DoubleArray, sign: Int, p95: Double): IntArray {
        val peaks = ArrayList<Int>()
        val w = PEAK_HALF_WINDOW
        for (i in w until p.size - w) {
            val value = sign * p[i]
            if (value <= PEAK_FRACTION * p95) continue
            var isMax = true
            for (j in i - w..i + w) if (sign * p[j] > value) { isMax = false; break }
            if (!isMax) continue
            val last = peaks.lastOrNull()
            if (last != null && times[i] - times[last] < MIN_SEPARATION_NS) {
                if (value > sign * p[last]) peaks[peaks.size - 1] = i
            } else peaks += i
        }
        return peaks.toIntArray()
    }
}
