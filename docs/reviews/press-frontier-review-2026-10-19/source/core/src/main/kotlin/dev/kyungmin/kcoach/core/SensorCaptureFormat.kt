package dev.kyungmin.kcoach.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One synchronized motion/heart-rate record in a set capture. */
data class SensorCaptureRecord(
    val sample: SensorSample,
    val heartRateBpm: Int? = null,
    val accelerationTimestampNs: Long? = null,
    val gyroscopeTimestampNs: Long? = null,
    val receivedElapsedNs: Long? = null,
    val accelerationAccuracy: Int? = null,
    val heartRateTimestampNs: Long? = null,
)

data class SensorCaptureDocument(
    val formatVersion: Int,
    val sampleRateRequestedHz: Double,
    val records: List<SensorCaptureRecord>,
) {
    val sampleCount: Int get() = records.size
    val startMonotonicNs: Long? get() = records.firstOrNull()?.sample?.timestampNs
    val endMonotonicNs: Long? get() = records.lastOrNull()?.sample?.timestampNs

    fun validateTimestamps(): List<String> {
        val errors = mutableListOf<String>()
        records.zipWithNext().forEachIndexed { index, (previous, current) ->
            if (current.sample.timestampNs <= previous.sample.timestampNs) {
                errors += "timestamp[$index] is not strictly increasing"
            }
        }
        records.forEachIndexed { index, record ->
            val sample = record.sample
            if (sample.timestampNs < 0 || listOfNotNull(record.accelerationTimestampNs, record.gyroscopeTimestampNs,
                    record.receivedElapsedNs, record.heartRateTimestampNs).any { it < 0 } ||
                record.gyroscopeTimestampNs != null && record.gyroscopeTimestampNs != sample.timestampNs) {
                errors += "clock[$index] contains an invalid original timestamp"
            }
            if (
                !sample.accelerationX.isFinite() ||
                !sample.accelerationY.isFinite() ||
                !sample.accelerationZ.isFinite() ||
                !sample.gyroscopeX.isFinite() ||
                !sample.gyroscopeY.isFinite() ||
                !sample.gyroscopeZ.isFinite()
            ) {
                errors += "sensor[$index] contains a non-finite value"
            }
            if (record.heartRateBpm != null && record.heartRateBpm !in 20..240) {
                errors += "heartRate[$index] is outside 20..240 bpm"
            }
        }
        return errors
    }
}

/**
 * Dependency-free KCoach Sensor (KCS) binary format.
 *
 * Header:
 *   magic[4] = KCS1
 *   formatVersion: int
 *   recordCount: int
 *   requestedSampleRateHz: double
 *
 * Version 1 record (40 bytes):
 *   timestampNs: long
 *   acceleration x/y/z: float x 3
 *   gyroscope x/y/z: float x 3
 *   sensorAccuracy: int (-1 means unavailable)
 *   heartRateBpm: int (-1 means unavailable)
 * Version 2 appends (36 bytes): accelerometer time, gyroscope time,
 * callback elapsed time (long x 3), accelerometer accuracy (int), HR time (long).
 * Missing original times remain -1 when reading older captures.
 */
object SensorCaptureFormat {
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    const val FORMAT_VERSION = 2
    val SUPPORTED_VERSIONS = setOf(1, 2)
    fun recordBytes(version: Int = FORMAT_VERSION): Int {
        require(version in SUPPORTED_VERSIONS)
        return if (version == 1) 40 else 76
    }

    fun write(
        output: OutputStream,
        records: List<SensorCaptureRecord>,
        sampleRateRequestedHz: Double,
        formatVersion: Int = FORMAT_VERSION,
    ) {
        require(sampleRateRequestedHz.isFinite() && sampleRateRequestedHz > 0.0) { "Requested sample rate must be positive" }
        require(formatVersion in SUPPORTED_VERSIONS)
        require(formatVersion != 1 || records.all { it.accelerationTimestampNs == null && it.gyroscopeTimestampNs == null &&
            it.receivedElapsedNs == null && it.accelerationAccuracy == null && it.heartRateTimestampNs == null }) { "V1 cannot preserve original sensor clocks" }
        require(records.size <= Int.MAX_VALUE) { "Too many capture records" }
        val errors = SensorCaptureDocument(formatVersion, sampleRateRequestedHz, records).validateTimestamps()
        require(errors.isEmpty()) { errors.joinToString("; ") }

        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(formatVersion)
            data.writeInt(records.size)
            data.writeDouble(sampleRateRequestedHz)
            records.forEach { record ->
                val sample = record.sample
                data.writeLong(sample.timestampNs)
                data.writeFloat(sample.accelerationX)
                data.writeFloat(sample.accelerationY)
                data.writeFloat(sample.accelerationZ)
                data.writeFloat(sample.gyroscopeX)
                data.writeFloat(sample.gyroscopeY)
                data.writeFloat(sample.gyroscopeZ)
                data.writeInt(sample.sensorAccuracy ?: -1)
                data.writeInt(record.heartRateBpm ?: -1)
                if (formatVersion >= 2) {
                    data.writeLong(record.accelerationTimestampNs ?: -1)
                    data.writeLong(record.gyroscopeTimestampNs ?: -1)
                    data.writeLong(record.receivedElapsedNs ?: -1)
                    data.writeInt(record.accelerationAccuracy ?: -1)
                    data.writeLong(record.heartRateTimestampNs ?: -1)
                }
            }
        }
    }

    fun read(input: InputStream): SensorCaptureDocument {
        DataInputStream(input).use { data ->
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Invalid KCS capture magic" }
            val version = data.readInt()
            require(version in SUPPORTED_VERSIONS) { "Unsupported KCS format version: $version" }
            val recordCount = data.readInt()
            require(recordCount >= 0) { "Negative KCS record count" }
            val sampleRate = data.readDouble()
            require(sampleRate.isFinite() && sampleRate > 0.0) { "Invalid KCS sample rate" }
            require(recordCount.toLong() * recordBytes(version) <= Int.MAX_VALUE) { "KCS capture is too large" }

            val records = ArrayList<SensorCaptureRecord>(recordCount)
            repeat(recordCount) {
                try {
                    val sample = SensorSample(
                        timestampNs = data.readLong(),
                        accelerationX = data.readFloat(),
                        accelerationY = data.readFloat(),
                        accelerationZ = data.readFloat(),
                        gyroscopeX = data.readFloat(),
                        gyroscopeY = data.readFloat(),
                        gyroscopeZ = data.readFloat(),
                        sensorAccuracy = data.readInt().takeUnless { it < 0 },
                    )
                    val heartRate = data.readInt().takeUnless { it < 0 }
                    records += if (version == 1) SensorCaptureRecord(sample, heartRate) else SensorCaptureRecord(sample, heartRate,
                        data.readLong().takeUnless { it == -1L }, data.readLong().takeUnless { it == -1L },
                        data.readLong().takeUnless { it == -1L }, data.readInt().takeUnless { it < 0 }, data.readLong().takeUnless { it == -1L })
                } catch (error: EOFException) {
                    throw IllegalArgumentException("Truncated KCS capture at record $it", error)
                }
            }
            require(data.read() == -1) { "Trailing bytes in KCS capture" }
            return SensorCaptureDocument(version, sampleRate, records).also { document ->
                require(document.validateTimestamps().isEmpty()) {
                    document.validateTimestamps().joinToString("; ")
                }
            }
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
