package dev.kyungmin.kcoach.core

/** Missing legacy metadata is unknown; explicitly unusable acceleration is never silently reliable. */
object SensorRecordQuality {
    fun forCounting(record: SensorCaptureRecord): SensorSample =
        if (hasAccelerationFault(record)) record.sample.copy(sensorAccuracy = 0) else record.sample

    fun hasAccelerationFault(record: SensorCaptureRecord): Boolean {
        return hasAccelerationFault(record.accelerationAccuracy, record.accelerationTimestampNs, record.sample.timestampNs)
    }
    fun hasAccelerationFault(accelerationAccuracy: Int?, accelerationTimestampNs: Long?, gyroNs: Long): Boolean {
        if (accelerationAccuracy == 0) return true
        val accelerationNs = accelerationTimestampNs ?: return false
        return accelerationNs < 0 || accelerationNs > gyroNs || gyroNs - accelerationNs > 40_000_000L
    }
}
