package dev.kyungmin.kcoach.core

import kotlin.math.sqrt

/**
 * Deterministic phone-side reanalysis of a KCS capture.
 *
 * This is intentionally profile-driven. A profile may refuse automatic counting
 * when the exercise is not observable from a wrist IMU (for example a machine
 * leg press), instead of reporting a convincing but false number.
 */
object PhoneRepAnalyzer {
    data class Output(
        val events: List<RepEvent>,
        val result: SetAnalysisResult,
    )

    fun analyze(document: SensorCaptureDocument, watchDetectedReps: Int? = null): Output =
        analyze(document, "", "", watchDetectedReps)

    /** Replays the version frozen on the watch; these outputs never become human training labels. */
    fun analyzePersonal(document: SensorCaptureDocument, exerciseId: String, compatibility: PatternCompatibility,
        runtime: PatternRuntimeProfile, watchDetectedReps: Int?, used: Boolean, interrupted: Boolean,
        wristMotionBaseline: ReviewedWristMotionBaseline? = null): Output {
        val generic = ExerciseTrackingProfiles.resolve(exerciseId)
        val empty = emptyOutput(watchDetectedReps, generic)
        fun refused(reason: String) = sanitizeOutput(empty.copy(result = empty.result.copy(
            algorithmVersion = runtime.engineId, trackingProfileId = runtime.id, trackingStatus = reason)))
        if (generic.mode == TrackingMode.MANUAL_REQUIRED) return refused("수동 입력 필요")
        if (runtime.compatibility != compatibility || compatibility.recipe != ExerciseMeasurementContracts.resolve(exerciseId).recipe)
            return refused("원본의 개인 패턴 착용 조건 불일치 · 횟수 확인 필요")
        val records = document.records
        if (records.size !in 10..30_000 || !document.sampleRateRequestedHz.isFinite() || document.sampleRateRequestedHz <= 0 ||
            document.validateTimestamps().isNotEmpty() || records.any { record -> SignalAxis.entries.any { !it.value(record.sample).isFinite() } })
            return refused("센서 기록 부족/오류 · 횟수 확인 필요")
        if (records.all { it.sample.sensorAccuracy == 0 || SensorRecordQuality.hasAccelerationFault(it) })
            return refused("센서 품질 불량 · 횟수 확인 필요")
        val detector = ReviewedPatternDetector(exerciseId, compatibility, runtime)
        val detections = records.mapNotNull { detector.add(SensorRecordQuality.forCounting(it)) }
        var quality = calculateSignalQuality(records, document.sampleRateRequestedHz)
        if (records.zipWithNext().any { (a, b) -> b.sample.timestampNs - a.sample.timestampNs > 60_000_000 } ||
            records.any { it.sample.sensorAccuracy == 0 }) quality = quality.coerceAtMost(.70)
        fun angularRate(sample: SensorSample) = sqrt(sample.gyroscopeX.toDouble() * sample.gyroscopeX +
            sample.gyroscopeY.toDouble() * sample.gyroscopeY + sample.gyroscopeZ.toDouble() * sample.gyroscopeZ)
        val events = detections.mapIndexed { index, detection ->
            val cycle = detection.cycle
            val window = records.filter { it.sample.timestampNs in cycle.startNs..cycle.endNs }
            // Integrate observed wrist angular speed in radians; never call this a joint ROM or load path.
            val angularTravel = window.zipWithNext().sumOf { (a, b) ->
                angularRate(a.sample) * (b.sample.timestampNs - a.sample.timestampNs) / 1_000_000_000.0
            }
            RepEvent(index + 1, cycle.startNs, cycle.turnaroundNs, cycle.endNs, cycle.durationMs,
                requireNotNull(cycle.concentricMs), requireNotNull(cycle.eccentricMs), angularTravel,
                window.maxOfOrNull { angularRate(it.sample) } ?: 0.0, quality)
        }
        val measured = if (used && !interrupted && quality >= .85) ObservedMotionAnalyzer.analyze(document, detections.map { it.cycle }, "PERSONAL_WRIST_CANDIDATE") else null
        val metrics = measured?.let { observed -> observed.copy(wristMotionComparison = wristMotionBaseline?.let {
            ReviewedMotionBaseline.compare(observed, it, runtime)
        }) }
        val durations = events.map { it.durationMs.toDouble() }
        val heartRates = records.mapNotNull { it.heartRateBpm }.filter { it in 20..240 }
        val totalTimeSeconds = events.sumOf { it.durationMs } / 1_000.0
        val speed = if (totalTimeSeconds > 0) events.sumOf { it.amplitude } / totalTimeSeconds else null
        val countEligible = used && !interrupted && quality >= .85 && watchDetectedReps == events.size
        val status = when {
            !used -> "워치 개인 패턴 사용 확인 누락 · 횟수 확인 필요"
            interrupted || quality < .85 -> "센서 기록 중단/품질 부족 · 횟수 확인 필요"
            watchDetectedReps != events.size -> "워치와 원본 재분석 불일치 · 횟수 확인 필요"
            else -> "세트에 고정한 개인 패턴 · 손목 관측 반복"
        }
        val result = empty.result.copy(phoneReanalyzedReps = events.size, confirmedReps = events.size.takeIf { countEligible },
            algorithmVersion = "${runtime.engineId}:${runtime.id}:${runtime.version}", trackingProfileId = runtime.id,
            trackingStatus = status, averageRepDurationMs = durations.takeIf { it.isNotEmpty() }?.average(),
            tempoVariance = variance(durations), stabilityScore = null, consistencyScore = null,
            lateSetDegradation = lateSetDegradation(durations), averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            peakHeartRate = heartRates.maxOrNull(), analysisConfidence = quality, repConfidence = null,
            averageAngularSpeed = speed, wristMotionSpeed = speed, wristMotionRange = null, relativeRom = null,
            tempoConsistency = metrics?.tempoConsistency, signalQuality = quality, capabilityTier = CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA, motionMetrics = metrics)
        return Output(events, CapabilityOutputPolicy.sanitize(result))
    }

    fun analyze(
        document: SensorCaptureDocument,
        exerciseId: String,
        exerciseName: String = "",
        watchDetectedReps: Int? = null,
    ): Output {
        val profile = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        return analyze(document, profile, watchDetectedReps)
    }

    fun analyze(
        document: SensorCaptureDocument,
        profile: ExerciseTrackingProfile,
        watchDetectedReps: Int? = null,
    ): Output {
        if (profile.mode == TrackingMode.MANUAL_REQUIRED) return sanitizeOutput(emptyOutput(watchDetectedReps, profile))
        if (!document.sampleRateRequestedHz.isFinite() || document.sampleRateRequestedHz <= 0.0 ||
            document.validateTimestamps().isNotEmpty()) return sanitizeOutput(emptyOutput(watchDetectedReps, profile))
        val records = document.records
        if (records.size < 3) return sanitizeOutput(emptyOutput(watchDetectedReps, profile))
        if (records.all { it.sample.sensorAccuracy == 0 || SensorRecordQuality.hasAccelerationFault(it) })
            return sanitizeOutput(emptyOutput(watchDetectedReps, profile).let {
                it.copy(result = it.result.copy(trackingStatus = "센서 품질 불량 · 횟수 확인 필요"))
            })
        if (profile.signal == TrackingSignal.FOREARM_ROTATION) {
            return analyzeExternalRotation(document, profile, watchDetectedReps)
        }

        val detector = ProfiledMotionRepDetector(profile)
        val motionEvents = buildList {
            records.forEach { record -> detector.add(SensorRecordQuality.forCounting(record))?.let(::add) }
            detector.finish()?.let(::add)
        }
        val events = motionEvents.mapIndexed { index, event ->
            RepEvent(
                repIndex = index + 1,
                startTimestampNs = event.startTimestampNs,
                turnaroundTimestampNs = event.turnaroundTimestampNs,
                endTimestampNs = event.endTimestampNs,
                durationMs = event.durationMs,
                concentricMs = null,
                eccentricMs = null,
                amplitude = event.motionDisplacement,
                peakAngularVelocity = event.peakMotionRate,
                confidence = event.confidence,
            )
        }

        val metrics = observedGenericMetrics(document, events)
        val durations = events.map { it.durationMs.toDouble() }
        val amplitudes = events.map { it.amplitude }
        val heartRates = records.mapNotNull { it.heartRateBpm }.filter { it in 20..240 }
        val avgDuration = durations.average().takeUnless { it.isNaN() }
        val avgAmplitude = amplitudes.average().takeUnless { it.isNaN() }
        val tempoVariance = variance(durations)
        val amplitudeVariance = variance(amplitudes)
        val rawConfidence = events.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
        val signalQuality = calculateSignalQuality(records, document.sampleRateRequestedHz)
        val confidence = (rawConfidence * signalQuality * if (profile.mode == TrackingMode.WRIST_IMU) 0.95 else 0.85)
            .coerceIn(0.0, 1.0)
        val consistency = scoreConsistency(durations, amplitudes)
        val stability = (confidence * 100.0).toInt().coerceIn(0, 100)
        // Mixed acceleration/gyro features have no rad/s unit. Only pure gyro
        // detector rates can be presented as wrist angular speed.
        val angularSpeeds = if (profile.signal == TrackingSignal.ROTATION_ENERGY) {
            events.map { it.peakAngularVelocity }
        } else emptyList()
        val averageAngularSpeed = angularSpeeds.average().takeUnless { it.isNaN() }
        val trackingStatus = if (signalQuality < 0.85) "센서 기록 품질 부족 · 반복 수 확인 필요" else when (profile.mode) {
            TrackingMode.WRIST_IMU -> "손목 IMU 자동 추정"
            TrackingMode.WRIST_IMU_CAUTION -> "손목 기반 추정 · 실제 기준 세트 검증 필요"
            TrackingMode.MANUAL_REQUIRED -> "수동 입력 필요"
        }
        val result = SetAnalysisResult(
            watchDetectedReps = watchDetectedReps,
            phoneReanalyzedReps = events.size,
            confirmedReps = events.size.takeIf { confidence >= 0.72 && it > 0 },
            repSource = RepSource.CUSTOM_IMU,
            averageRepDurationMs = avgDuration,
            tempoVariance = tempoVariance,
            amplitudeVariance = amplitudeVariance,
            stabilityScore = stability,
            consistencyScore = consistency,
            lateSetDegradation = lateSetDegradation(durations),
            averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            peakHeartRate = heartRates.maxOrNull(),
            analysisConfidence = confidence,
            algorithmVersion = profile.id,
            trackingMode = profile.mode,
            trackingProfileId = profile.id,
            trackingStatus = trackingStatus,
            averageAngularSpeed = averageAngularSpeed,
            relativeRom = null,
            repConfidence = confidence,
            capabilityTier = profile.capabilityTier,
            wristMotionSpeed = averageAngularSpeed,
            wristMotionRange = null,
            tempoConsistency = metrics?.tempoConsistency,
            signalQuality = signalQuality,
            motionMetrics = metrics,
        )
        return Output(events, CapabilityOutputPolicy.sanitize(result))
    }

    private fun observedGenericMetrics(document: SensorCaptureDocument, events: List<RepEvent>): ObservedMotionMetrics? {
        val cycles = events.map { event ->
            if (event.startTimestampNs >= event.turnaroundTimestampNs || event.turnaroundTimestampNs >= event.endTimestampNs) return null
            RepCycle(event.startTimestampNs, event.turnaroundTimestampNs, event.endTimestampNs, MusclePhase.UNKNOWN)
        }
        return ObservedMotionAnalyzer.analyze(document, cycles, "GENERIC_WRIST_CANDIDATE")
    }

    private fun sanitizeOutput(output: Output): Output =
        output.copy(result = CapabilityOutputPolicy.sanitize(output.result))

    private fun analyzeExternalRotation(
        document: SensorCaptureDocument,
        profile: ExerciseTrackingProfile,
        watchDetectedReps: Int?,
    ): Output {
        val detector = ForearmRotationRepDetector(profile)
        val rotationEvents = buildList {
            document.records.forEach { record ->
                detector.add(SensorRecordQuality.forCounting(record))?.let(::add)
            }
            detector.finish()?.let(::add)
        }
        val events = rotationEvents.mapIndexed { index, event ->
            RepEvent(
                repIndex = index + 1,
                startTimestampNs = event.startTimestampNs,
                turnaroundTimestampNs = event.turnaroundTimestampNs,
                endTimestampNs = event.endTimestampNs,
                durationMs = event.durationMs,
                concentricMs = null,
                eccentricMs = null,
                amplitude = event.angularDisplacement,
                peakAngularVelocity = event.peakAngularVelocity,
                confidence = event.confidence,
            )
        }
        val metrics = observedGenericMetrics(document, events)
        val durations = events.map { it.durationMs.toDouble() }
        val amplitudes = events.map { it.amplitude }
        val heartRates = document.records.mapNotNull { it.heartRateBpm }.filter { it in 20..240 }
        val signalQuality = calculateSignalQuality(document.records, document.sampleRateRequestedHz)
        val rawConfidence = events.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
        val confidence = (rawConfidence * signalQuality * 0.90).coerceIn(0.0, 1.0)
        val trackingStatus = if (signalQuality < 0.85) "센서 기록 품질 부족 · 반복 수 확인 필요" else "손목 관측 외회전 · 회전축 왕복 신호 · 손목 IMU가 팔꿈치 고정/관절 ROM을 확인하지 않음 · Watch8 실물/영상 검증 필요"
        val result = SetAnalysisResult(
            watchDetectedReps = watchDetectedReps,
            phoneReanalyzedReps = events.size,
            confirmedReps = events.size.takeIf { confidence >= 0.72 && it > 0 },
            repSource = RepSource.CUSTOM_IMU,
            averageRepDurationMs = durations.average().takeUnless { it.isNaN() },
            tempoVariance = variance(durations),
            amplitudeVariance = variance(amplitudes),
            stabilityScore = (confidence * 100.0).toInt().coerceIn(0, 100),
            consistencyScore = scoreConsistency(durations, amplitudes),
            lateSetDegradation = lateSetDegradation(durations),
            averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            peakHeartRate = heartRates.maxOrNull(),
            analysisConfidence = confidence,
            algorithmVersion = profile.id,
            trackingMode = profile.mode,
            trackingProfileId = profile.id,
            trackingStatus = trackingStatus,
            averageAngularSpeed = events.map { it.peakAngularVelocity }.average().takeUnless { it.isNaN() },
            relativeRom = null,
            repConfidence = confidence,
            capabilityTier = profile.capabilityTier,
            wristMotionSpeed = events.map { it.peakAngularVelocity }.average().takeUnless { it.isNaN() },
            wristMotionRange = null,
            tempoConsistency = metrics?.tempoConsistency,
            signalQuality = signalQuality,
            motionMetrics = metrics,
        )
        return Output(events, CapabilityOutputPolicy.sanitize(result))
    }

    private fun emptyOutput(watchDetectedReps: Int?, profile: ExerciseTrackingProfile): Output {
        val manual = profile.mode == TrackingMode.MANUAL_REQUIRED
        return Output(
            events = emptyList(),
            result = SetAnalysisResult(
                watchDetectedReps = watchDetectedReps,
                phoneReanalyzedReps = null,
                confirmedReps = null,
                repSource = if (manual) RepSource.MANUAL else RepSource.CUSTOM_IMU,
                averageRepDurationMs = null,
                tempoVariance = null,
                amplitudeVariance = null,
                stabilityScore = 0,
                consistencyScore = 0,
                lateSetDegradation = null,
                averageHeartRate = null,
                peakHeartRate = null,
                analysisConfidence = 0.0,
                algorithmVersion = profile.id,
                trackingMode = profile.mode,
                trackingProfileId = profile.id,
                trackingStatus = if (manual) "손목 IMU만으로 관측 불가 · 수동 입력 필요" else "샘플 부족 · 재측정 필요",
                repConfidence = 0.0,
                capabilityTier = profile.capabilityTier,
                signalQuality = 0.0,
                tempoConsistency = 0,
            ),
        )
    }

    private fun calculateSignalQuality(records: List<SensorCaptureRecord>, requestedHz: Double): Double {
        if (records.size < 2 || !requestedHz.isFinite() || requestedHz <= 0.0) return 0.0
        val intervalsNs = records.zipWithNext { a, b ->
            b.sample.timestampNs.toDouble() - a.sample.timestampNs.toDouble()
        }
        if (intervalsNs.any { it <= 0.0 || !it.isFinite() }) return 0.0
        val expectedIntervalNs = 1_000_000_000.0 / requestedHz
        // Use elapsed coverage, not the observed median: uniformly lost samples can still look regular.
        // Averaging also avoids treating ordinary short/long scheduling jitter as missing samples.
        val coverage = (intervalsNs.size * expectedIntervalNs / intervalsNs.sum()).coerceIn(0.0, 1.0)
        val gapRate = intervalsNs.count { it > expectedIntervalNs * 2.5 }.toDouble() / intervalsNs.size
        val accelerationFault = records.any(SensorRecordQuality::hasAccelerationFault)
        val reliableFraction = 1.0 - records.count {
            it.sample.sensorAccuracy == 0 || SensorRecordQuality.hasAccelerationFault(it)
        }.toDouble() / records.size
        var quality = (coverage * (1.0 - gapRate) * reliableFraction).coerceIn(0.0, 1.0)
        // The motion detector resets across >450ms. Remaining clear reps cannot prove a complete set.
        if (intervalsNs.any { it > 450_000_000.0 }) quality = quality.coerceAtMost(0.70)
        if (accelerationFault) quality = quality.coerceAtMost(0.70)
        return quality
    }

    private fun variance(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun scoreConsistency(durations: List<Double>, amplitudes: List<Double>): Int {
        if (durations.isEmpty() || amplitudes.isEmpty()) return 0
        val durationMean = durations.average().coerceAtLeast(1.0)
        val amplitudeMean = amplitudes.average().coerceAtLeast(1e-6)
        val durationCv = sqrt(variance(durations) ?: 0.0) / durationMean
        val amplitudeCv = sqrt(variance(amplitudes) ?: 0.0) / amplitudeMean
        return (100.0 - (durationCv * 55.0 + amplitudeCv * 45.0)).toInt().coerceIn(0, 100)
    }

    private fun lateSetDegradation(durations: List<Double>): Double? {
        if (durations.size < 4) return null
        val split = durations.size / 2
        val first = durations.take(split).average()
        val last = durations.drop(split).average()
        return ((last - first) / first.coerceAtLeast(1.0)).coerceIn(-1.0, 1.0)
    }
}
