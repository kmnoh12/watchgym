package dev.kyungmin.kcoach.core

import kotlin.math.abs

data class SignalPoint(val timestampNs: Long, val value: Double) {
    init { require(timestampNs >= 0 && value.isFinite()) }
}

/** Offline local polynomial fit. Actual timestamps are used; missing samples are not invented. */
object SavitzkyGolayFilter {
    fun smooth(points: List<SignalPoint>, windowNs: Long, order: Int = 4): List<SignalPoint> {
        require(windowNs > 0 && order in 1..4)
        require(points.zipWithNext().all { (a, b) -> b.timestampNs > a.timestampNs })
        if (points.isEmpty()) return emptyList()
        val half = windowNs / 2
        var left = 0
        var right = 0
        return points.mapIndexed { index, point ->
            while (left < index && point.timestampNs - points[left].timestampNs > half) left++
            if (right < index) right = index
            while (right + 1 < points.size && points[right + 1].timestampNs - point.timestampNs <= half) right++
            if (right - left + 1 < order + 1) return@mapIndexed point
            val size = order + 1
            val matrix = Array(size) { DoubleArray(size + 1) }
            for (sample in left..right) {
                val t = (points[sample].timestampNs - point.timestampNs).toDouble() / windowNs
                val powers = DoubleArray(order * 2 + 1)
                powers[0] = 1.0
                for (power in 1 until powers.size) powers[power] = powers[power - 1] * t
                for (row in 0 until size) {
                    for (column in 0 until size) matrix[row][column] += powers[row + column]
                    matrix[row][size] += powers[row] * points[sample].value
                }
            }
            val coefficients = solve(matrix)
            if (coefficients == null || !coefficients[0].isFinite()) point else point.copy(value = coefficients[0])
        }
    }

    private fun solve(matrix: Array<DoubleArray>): DoubleArray? {
        val size = matrix.size
        for (column in 0 until size) {
            val pivot = (column until size).maxBy { abs(matrix[it][column]) }
            if (abs(matrix[pivot][column]) < 1e-12) return null
            val temporary = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = temporary
            val scale = matrix[column][column]
            for (i in column..size) matrix[column][i] /= scale
            for (row in 0 until size) if (row != column) {
                val factor = matrix[row][column]
                for (i in column..size) matrix[row][i] -= factor * matrix[column][i]
            }
        }
        return DoubleArray(size) { matrix[it][size] }
    }
}
