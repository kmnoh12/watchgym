package dev.kyungmin.kcoach.core

/** Capture-time detector configuration. Reanalysis must not silently use a later calibration. */
object TrackingProfileSnapshot {
    fun encode(p: ExerciseTrackingProfile): String = listOf(
        "1", p.exerciseId, p.id, p.mode.name, p.signal.name, p.minimumRepMs, p.maximumRepMs,
        p.minimumSeparationMs, p.thresholdFraction, p.minimumRelativeRom, p.label,
        p.capabilityTier.name, p.countMode.name, p.family.name, p.minimumFeatureRate,
        p.minimumLobeDisplacement, p.minimumLobeMs, p.maximumLobeMs, p.minimumTrajectorySpread,
        p.preferredAxis ?: "", p.preferredDirection ?: "", p.gyroNoiseFloor, p.calibrationProfileVersion ?: "",
    ).joinToString("|") { it.toString().replace('|', '/') }

    fun decode(encoded: String): ExerciseTrackingProfile {
        val f = encoded.split('|')
        require(f.size == 23 && f[0] == "1") { "invalid tracking profile snapshot" }
        return ExerciseTrackingProfile(
            exerciseId = f[1], id = f[2], mode = TrackingMode.valueOf(f[3]), signal = TrackingSignal.valueOf(f[4]),
            minimumRepMs = f[5].toLong(), maximumRepMs = f[6].toLong(), minimumSeparationMs = f[7].toLong(),
            thresholdFraction = f[8].toDouble(), minimumRelativeRom = f[9].toDouble(), label = f[10],
            capabilityTier = CapabilityTier.valueOf(f[11]), countMode = CountMode.valueOf(f[12]),
            family = MotionFamily.valueOf(f[13]), minimumFeatureRate = f[14].toDouble(),
            minimumLobeDisplacement = f[15].toDouble(), minimumLobeMs = f[16].toLong(), maximumLobeMs = f[17].toLong(),
            minimumTrajectorySpread = f[18].toDouble(), preferredAxis = f[19].takeIf(String::isNotEmpty)?.toInt(),
            preferredDirection = f[20].takeIf(String::isNotEmpty)?.toInt(), gyroNoiseFloor = f[21].toDouble(),
            calibrationProfileVersion = f[22].takeIf(String::isNotEmpty)?.toInt(),
        ).also { p ->
            require(p.exerciseId.isNotBlank() && p.id.substringBefore('+') in
                listOfNotNull(p.family.sharedAlgorithmId, p.family.legacyAlgorithmId)) {
                "unsupported recorded detector version"
            }
            val generic = ExerciseTrackingProfiles.resolve(p.exerciseId, p.label)
            // Only explicitly migrated exercises may replay their old combined-signal press engine.
            val migratedPress = p.exerciseId in setOf("dumbbell_shoulder_press", "smith_shoulder_press") &&
                generic.family == MotionFamily.VERTICAL_PRESS_RETURN ||
                p.exerciseId == "rope_extension" && generic.family == MotionFamily.FLEXION_EXTENSION
            val historicalPress = migratedPress &&
                p.family == MotionFamily.PRESS_RETURN && p.signal == TrackingSignal.COMBINED_MOTION
            require((p.family == generic.family && p.signal == generic.signal || historicalPress) && p.mode == generic.mode &&
                p.capabilityTier == generic.capabilityTier && p.countMode == generic.countMode) {
                "recorded profile contradicts exercise measurement policy"
            }
            require(p.minimumRepMs >= 0 && p.maximumRepMs >= p.minimumRepMs && p.minimumSeparationMs >= 0)
            require(p.minimumLobeMs >= 0 && p.maximumLobeMs >= p.minimumLobeMs)
            require(p.preferredAxis == null || p.preferredAxis in 0..2)
            require(p.preferredDirection == null || p.preferredDirection in setOf(-1, 1))
            require(listOf(p.thresholdFraction, p.minimumRelativeRom, p.minimumFeatureRate,
                p.minimumLobeDisplacement, p.minimumTrajectorySpread, p.gyroNoiseFloor).all { it.isFinite() && it >= 0 })
        }
    }
}
