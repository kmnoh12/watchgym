package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.sqrt

data class PersonalCalibrationProfile(
    val profileId: String,
    val profileVersion: Int,
    val exerciseId: String,
    val variantId: String,
    val side: String,
    val watchPlacement: String,
    val watchModel: String,
    val dominantAxis: Int,
    val direction: Int,
    val gyroNoiseFloor: Double,
    val accelerationNoiseFloor: Double,
    val minimumRom: Double,
    val maximumRom: Double,
    val peakAngularVelocity: Double,
    val medianRepDurationMs: Long,
    val trajectorySpread: Double,
    val validRepCount: Int,
    val createdAtMs: Long,
)

sealed interface CalibrationBuildResult {
    data class Success(val profile: PersonalCalibrationProfile) : CalibrationBuildResult
    data class Failed(val reason: String, val detectedReps: Int) : CalibrationBuildResult
}

object PersonalCalibrationBuilder {
    fun build(
        document: SensorCaptureDocument,
        exerciseId: String,
        exerciseName: String,
        side: String = "LEFT",
        watchPlacement: String = "WRIST_BONE",
        watchModel: String = "Galaxy Watch8",
        nowMs: Long = System.currentTimeMillis(),
    ): CalibrationBuildResult {
        val generic = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        if (generic.mode == TrackingMode.MANUAL_REQUIRED) {
            return CalibrationBuildResult.Failed("exercise_unobservable_from_wrist", 0)
        }
        if (document.records.size < 10) return CalibrationBuildResult.Failed("insufficient_samples", 0)

        val calibrationProfile = generic.copy(
            minimumRepMs = (generic.minimumRepMs * 0.75).toLong().coerceAtLeast(250L),
            maximumRepMs = (generic.maximumRepMs * 1.25).toLong().coerceAtMost(8_000L),
            minimumFeatureRate = (generic.minimumFeatureRate * 0.80).coerceAtLeast(0.07),
            minimumLobeDisplacement = (generic.minimumLobeDisplacement * 0.80).coerceAtLeast(0.10),
        )
        val events = if (generic.signal == TrackingSignal.FOREARM_ROTATION) {
            val detector = ForearmRotationRepDetector(calibrationProfile)
            buildList {
                document.records.forEach { detector.add(it.sample)?.let(::add) }
                detector.finish()?.let(::add)
            }.map { EventFeatures(it.durationMs, it.angularDisplacement, it.peakAngularVelocity, 0.0) }
        } else {
            val detector = ProfiledMotionRepDetector(calibrationProfile)
            buildList {
                document.records.forEach { detector.add(it.sample)?.let(::add) }
                detector.finishAll().forEach(::add)
            }.map { EventFeatures(it.durationMs, it.motionDisplacement, it.peakMotionRate, it.trajectorySpread) }
        }
        if (events.size < 3) return CalibrationBuildResult.Failed("fewer_than_3_valid_reps", events.size)
        val filtered = rejectOutliers(events)
        if (filtered.size < 3) return CalibrationBuildResult.Failed("fewer_than_3_reps_after_outlier_rejection", filtered.size)

        val axisEnergy = DoubleArray(3)
        document.records.forEach {
            axisEnergy[0] += abs(it.sample.gyroscopeX.toDouble())
            axisEnergy[1] += abs(it.sample.gyroscopeY.toDouble())
            axisEnergy[2] += abs(it.sample.gyroscopeZ.toDouble())
        }
        val dominantAxis = axisEnergy.indices.maxBy { axisEnergy[it] }
        val signed = document.records.sumOf {
            when (dominantAxis) {
                0 -> it.sample.gyroscopeX.toDouble()
                1 -> it.sample.gyroscopeY.toDouble()
                else -> it.sample.gyroscopeZ.toDouble()
            }
        }
        val baseline = document.records.take(maxOf(3, document.records.size / 10)) +
            document.records.takeLast(maxOf(3, document.records.size / 10))
        val gyroNoise = baseline.map { sampleNorm(it.sample, gyro = true) }.median()
        val accelNoise = baseline.map { abs(sampleNorm(it.sample, gyro = false) - 9.80665) }.median()
        val roms = filtered.map(EventFeatures::rom).sorted()
        val durations = filtered.map { it.durationMs.toDouble() }

        return CalibrationBuildResult.Success(
            PersonalCalibrationProfile(
                profileId = "$exerciseId-$side-${nowMs}",
                profileVersion = 1,
                exerciseId = exerciseId,
                variantId = "standard",
                side = side,
                watchPlacement = watchPlacement,
                watchModel = watchModel,
                dominantAxis = dominantAxis,
                direction = if (signed >= 0.0) 1 else -1,
                gyroNoiseFloor = gyroNoise.coerceIn(0.0, 2.0),
                accelerationNoiseFloor = accelNoise.coerceIn(0.0, 8.0),
                minimumRom = roms.first().coerceIn(0.05, 8.0),
                maximumRom = roms.last().coerceIn(0.05, 12.0),
                peakAngularVelocity = filtered.maxOf(EventFeatures::peak).coerceIn(0.1, 20.0),
                medianRepDurationMs = durations.median().toLong().coerceIn(250L, 10_000L),
                trajectorySpread = filtered.map(EventFeatures::spread).median().coerceIn(0.0, 5.0),
                validRepCount = filtered.size,
                createdAtMs = nowMs,
            ),
        )
    }

    /** Personal observations receive 80%; generic thresholds remain a 20% safety prior. */
    fun personalizedTrackingProfile(
        personal: PersonalCalibrationProfile,
        generic: ExerciseTrackingProfile,
    ): ExerciseTrackingProfile = generic.copy(
        id = "${generic.id}+${personal.profileId}",
        // A slow/fast calibration is an example, not a requirement to keep that tempo.
        minimumRepMs = minOf(generic.minimumRepMs,
            blend(personal.medianRepDurationMs * 0.35, generic.minimumRepMs.toDouble()).toLong().coerceAtLeast(200L)),
        maximumRepMs = maxOf(generic.maximumRepMs,
            blend(personal.medianRepDurationMs * 2.2, generic.maximumRepMs.toDouble()).toLong().coerceAtMost(10_000L)),
        minimumFeatureRate = blend(
            maxOf(personal.gyroNoiseFloor * 2.5, personal.peakAngularVelocity * 0.12),
            generic.minimumFeatureRate,
        ).coerceIn(0.07, 4.0),
        // minimumRom is a complete out-and-back cycle. Use a deliberately
        // tolerant fraction for one lobe so calibration does not require the
        // user to reproduce an exaggerated reference ROM on every real rep.
        minimumLobeDisplacement = blend(personal.minimumRom * 0.22, generic.minimumLobeDisplacement)
            .coerceIn(0.05, 4.0),
        preferredAxis = personal.dominantAxis,
        preferredDirection = personal.direction,
        gyroNoiseFloor = personal.gyroNoiseFloor,
        calibrationProfileVersion = personal.profileVersion,
    )

    private data class EventFeatures(val durationMs: Long, val rom: Double, val peak: Double, val spread: Double)

    private fun rejectOutliers(events: List<EventFeatures>): List<EventFeatures> {
        val durationMedian = events.map { it.durationMs.toDouble() }.median()
        val romMedian = events.map(EventFeatures::rom).median()
        val durationMad = events.map { abs(it.durationMs - durationMedian) }.median().coerceAtLeast(80.0)
        val romMad = events.map { abs(it.rom - romMedian) }.median().coerceAtLeast(0.08)
        return events.filter {
            abs(it.durationMs - durationMedian) <= durationMad * 3.5 &&
                abs(it.rom - romMedian) <= romMad * 3.5 &&
                it.peak.isFinite() && it.rom.isFinite()
        }
    }

    private fun sampleNorm(sample: SensorSample, gyro: Boolean): Double {
        val x = if (gyro) sample.gyroscopeX else sample.accelerationX
        val y = if (gyro) sample.gyroscopeY else sample.accelerationY
        val z = if (gyro) sample.gyroscopeZ else sample.accelerationZ
        return sqrt((x * x + y * y + z * z).toDouble())
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun blend(personal: Double, generic: Double): Double = personal * 0.8 + generic * 0.2
}

object PersonalCalibrationProfileCodec {
    fun encode(profile: PersonalCalibrationProfile): String = listOf(
        "1",
        profile.profileId,
        profile.profileVersion,
        profile.exerciseId,
        profile.variantId,
        profile.side,
        profile.watchPlacement,
        profile.watchModel,
        profile.dominantAxis,
        profile.direction,
        profile.gyroNoiseFloor,
        profile.accelerationNoiseFloor,
        profile.minimumRom,
        profile.maximumRom,
        profile.peakAngularVelocity,
        profile.medianRepDurationMs,
        profile.trajectorySpread,
        profile.validRepCount,
        profile.createdAtMs,
    ).joinToString("|") { it.toString().replace('|', '/') }

    fun decode(encoded: String): PersonalCalibrationProfile {
        val fields = encoded.split('|')
        require(fields.size == 19 && fields[0] == "1") { "invalid calibration profile" }
        return PersonalCalibrationProfile(
            profileId = fields[1],
            profileVersion = fields[2].toInt(),
            exerciseId = fields[3],
            variantId = fields[4],
            side = fields[5],
            watchPlacement = fields[6],
            watchModel = fields[7],
            dominantAxis = fields[8].toInt(),
            direction = fields[9].toInt(),
            gyroNoiseFloor = fields[10].toDouble(),
            accelerationNoiseFloor = fields[11].toDouble(),
            minimumRom = fields[12].toDouble(),
            maximumRom = fields[13].toDouble(),
            peakAngularVelocity = fields[14].toDouble(),
            medianRepDurationMs = fields[15].toLong(),
            trajectorySpread = fields[16].toDouble(),
            validRepCount = fields[17].toInt(),
            createdAtMs = fields[18].toLong(),
        ).also {
            require(it.profileId.isNotBlank() && it.exerciseId.isNotBlank())
            require(it.profileVersion > 0 && it.validRepCount >= 3)
            require(it.dominantAxis in 0..2 && it.direction in setOf(-1, 1))
            require(
                listOf(
                    it.gyroNoiseFloor,
                    it.accelerationNoiseFloor,
                    it.minimumRom,
                    it.maximumRom,
                    it.peakAngularVelocity,
                    it.trajectorySpread,
                ).all(Double::isFinite),
            )
        }
    }
}
