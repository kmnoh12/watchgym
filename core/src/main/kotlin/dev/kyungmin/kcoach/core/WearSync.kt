package dev.kyungmin.kcoach.core

/** Small, versioned wire format shared by the phone and Wear OS app. */
object WearSync {
    const val PROTOCOL_VERSION = CURRENT_WIRE_PROTOCOL_VERSION
    const val PATH_CONTROL_V3 = "/kcoach/v3/control"
    const val PATH_LIVE_V3 = "/kcoach/v3/live"
    const val PATH_CAPTURE_MANIFEST_V3 = "/kcoach/v3/capture/manifest"
    const val PATH_CAPTURE_ASSET_V3 = "/kcoach/v3/capture/asset"
    const val PATH_CAPTURE_ASSET_EMULATOR = "/kcoach/emulator/capture/asset"
    const val PATH_PERSONAL_PROFILE = "/kcoach/personal-profile"
    const val PATH_PATTERN_PROFILE = "/kcoach/pattern-profile"
    const val PATH_PATTERN_PROFILE_ACK = "/kcoach/pattern-profile/ack"
    const val PATH_PERSONAL_PROFILE_DELETE = "/kcoach/personal-profile/delete"
    const val PATH_ACK_V3 = "/kcoach/v3/ack"
    const val PATH_SET_RECORD = "/kcoach/set-record"
    const val PATH_COMMAND = "/kcoach/command"
    const val PATH_COMMAND_ACK = "/kcoach/command/ack"
    const val PATH_MANUAL_SET = "/kcoach/manual-set"
    const val PATH_MANUAL_ACK = "/kcoach/manual-set/ack"
    const val PATH_WORKOUT_OFFER = "/kcoach/workout-offer"
    const val PATH_WORKOUT_CONTROL = "/kcoach/workout-control"
    const val PATH_WORKOUT_CONTROL_ACK = "/kcoach/workout-control/ack"
    const val PATH_CALIBRATION_CAMERA_STATE = "/kcoach/calibration/camera-state"
    const val PATH_CLOCK_REQUEST = "/kcoach/clock/request"
    /** Watch crash reports handed to the phone on the next launch, so one export covers both devices. */
    const val PATH_DIAGNOSTICS = "/kcoach/diagnostics"
    const val PATH_CLOCK_REPLY = "/kcoach/clock/reply"
    const val PATH_SET_ACK = "/kcoach/set/ack"
    const val PATH_RECONNECT = "/kcoach/reconnect"
    const val PATH_PROBE_START = "/kcoach/probe/start"
    const val PATH_PROBE_STOP = "/kcoach/probe/stop"
    const val PATH_STATE = "/kcoach/state"
    const val PATH_LIVE = "/kcoach/live"
    const val PATH_SAMPLE = "/kcoach/sample"
    const val PATH_PAIR = "/kcoach/pair"
    const val PATH_HEART_RATE_SAMPLE = "/kcoach/heart-rate-sample"
    const val PATH_HEARTBEAT = "/kcoach/heartbeat"
    const val PATH_WATCH_UI_READY = "/kcoach/watch-ui-ready"
    const val PATH_COMPLETE = "/kcoach/complete"
    const val KEY_PAYLOAD = "payload"
    const val KEY_SESSION = "session"

    fun captureAssetPath(captureId: String): String {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid capture id" }
        return "$PATH_CAPTURE_ASSET_V3/$captureId"
    }

    // The emulator-only TCP relay runs on the host. Keep it separate from the
    // old adb-forwarded port so a stale forwarding rule cannot hijack traffic.
    const val EMULATOR_BRIDGE_HOST = "10.0.2.2"
    const val EMULATOR_BRIDGE_PORT = 8767

    fun command(type: String, sessionId: String, exerciseId: String, setIndex: Int, targetReps: Int): String =
        listOf("1", type, sessionId, exerciseId, setIndex, targetReps).joinToString("|")

    fun richCommand(
        type: String,
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
        restEndAtMs: Long = 0L,
        setId: String = "",
        attemptId: String = "",
    ): String = listOf(
        "2", type, sessionId, exerciseId, exerciseName.replace('|', '/'), setIndex,
        targetReps, weightKg ?: "", restSeconds, restEndAtMs,
    ).joinToString("|").let { withSetIdentity(it, setId, attemptId) }

    fun withSetIdentity(payload: String, setId: String, attemptId: String): String {
        if (setId.isBlank() && attemptId.isBlank()) return payload
        val f = parse(payload).toMutableList()
        while (f.size < 15) f.add(when (f.size) { 10, 12 -> "0"; else -> "" })
        f[13] = setId; f[14] = attemptId
        return f.joinToString("|")
    }

    fun withPatternShadow(payload: String, commandId: String): String {
        if (commandId.isEmpty()) return payload
        require(commandId.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2")
        while (fields.size < 16) fields.add(when (fields.size) { 10, 12 -> "0"; else -> "" })
        fields[15] = commandId
        return fields.joinToString("|")
    }

    fun withPatternBinding(payload: String, key: PatternCompatibility): String {
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2")
        while (fields.size < 17) fields.add(when (fields.size) { 10, 12 -> "0"; else -> "" })
        fields[16] = PatternCompatibilityCodec.encode(key)
        return fields.joinToString("|")
    }

    fun withPatternWearing(payload: String, wearing: PatternWearingContext): String {
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2")
        while (fields.size < 18) fields.add(when (fields.size) { 10, 12 -> "0"; else -> "" })
        fields[17] = wearing.encode()
        return fields.joinToString("|")
    }

    fun withVerifiedPatternSelection(payload: String): String {
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2")
        while (fields.size < 19) fields.add(when (fields.size) { 10, 12 -> "0"; else -> "" })
        fields[18] = "VERIFIED_PATTERN"
        return fields.joinToString("|")
    }

    /** Additive field: older watch readers ignore it and never assume direction confirmation. */
    fun withStartWristContext(payload: String, context: StartWristContext): String {
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2")
        require(ExerciseMotionCatalog.normalizeExerciseId(fields[3]) == context.exerciseId)
        val wearing = PatternWearingContext.decode(fields.getOrNull(17).orEmpty())
        require(wearing.owner == context.owner && wearing.side == context.side && wearing.placement == context.placement)
        while (fields.size < 20) fields.add("")
        fields[19] = StartWristContextCodec.encode(context)
        return fields.joinToString("|")
    }

    fun withStartDirectionProfile(payload: String, profile: StartDirectionRuntimeProfile): String {
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2" && fields.getOrNull(1) in setOf("START", "SET", "SYNC"))
        require(CapturePurpose.forSession(fields[2]) == CapturePurpose.WORKOUT)
        require(StartWristContextCodec.decode(fields.getOrNull(19).orEmpty()) == profile.key.context)
        while (fields.size < 21) fields.add("")
        fields[20] = StartDirectionRuntimeCodec.encode(profile)
        return fields.joinToString("|")
    }

    fun withTempoSettings(payload: String, settings: WorkoutTempoSettings): String {
        val fields = parse(payload).toMutableList()
        require(fields.firstOrNull() == "2" && fields.getOrNull(1) in setOf("START", "SET", "SYNC"))
        require(CapturePurpose.forSession(fields[2]) == CapturePurpose.WORKOUT &&
            ExerciseMotionCatalog.normalizeExerciseId(fields[3]) == settings.exerciseId)
        while (fields.size < 22) fields.add("")
        fields[21] = WorkoutTempoSettingsCodec.encode(settings)
        return fields.joinToString("|")
    }

    /** Append-only rest ordering fields; indices 0..21 keep their existing meaning. */
    fun withRestVersion(payload: String, restRev: Long, watchRestSeq: Long): String {
        val fields = parse(payload).toMutableList()
        while (fields.size < 24) fields.add(when (fields.size) { 10, 12 -> "0"; else -> "" })
        fields[22] = restRev.toString(); fields[23] = watchRestSeq.toString()
        return fields.joinToString("|")
    }

    /** Index 24 is a durable per-set arm phase; older readers safely ignore it. */
    fun withUnilateral(payload: String, progress: UnilateralProgress?): String {
        val fields = parse(payload).toMutableList()
        while (fields.size < 25) fields.add(when (fields.size) { 10, 12 -> "0"; else -> "" })
        fields[24] = progress?.encode().orEmpty()
        return fields.joinToString("|")
    }

    fun hello(device: String): String = listOf("1", device, System.currentTimeMillis()).joinToString("|")

    fun heartbeat(device: String): String =
        listOf("1", device, System.currentTimeMillis()).joinToString("|")

    fun watchUiReady(sessionId: String = ""): String = listOf("1", sessionId, System.currentTimeMillis()).joinToString("|")

    fun completion(
        sessionId: String,
        exerciseId: String,
        setIndex: Int,
        reps: Int,
        heartRate: Int,
        phase: String,
        sampleCount: Int,
    ): String = listOf(
        "1", sessionId, exerciseId, setIndex, reps, heartRate, phase, sampleCount, System.currentTimeMillis(),
    ).joinToString("|")

    fun state(sessionId: String, exerciseId: String, setIndex: Int, reps: Int, heartRate: Int, recording: Boolean): String =
        listOf("1", sessionId, exerciseId, setIndex, reps, heartRate, recording).joinToString("|")

    fun liveState(
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        reps: Int,
        targetReps: Int,
        weightKg: Double?,
        heartRate: Int,
        phase: String,
        recording: Boolean,
        restEndAtMs: Long = 0L,
        sampleCount: Int = 0,
        trackingProfileId: String = "",
        personalized: Boolean = false,
        completionId: String = "",
        setId: String = "",
        attemptId: String = "",
    ): String = listOf(
        "2", sessionId, exerciseId, exerciseName.replace('|', '/'), setIndex, reps,
        targetReps, weightKg ?: "", heartRate, phase, recording, System.currentTimeMillis(),
        restEndAtMs, sampleCount, trackingProfileId.replace('|', '/'), personalized,
    ).let { if (setId.isNotBlank() || attemptId.isNotBlank()) it + listOf(completionId, setId, attemptId) else if (completionId.isBlank()) it else it + completionId }.joinToString("|")

    fun setAck(sessionId: String, exerciseId: String, setIndex: Int, completionId: String, setId: String = "", attemptId: String = ""): String =
        listOf("1", sessionId, exerciseId, setIndex, completionId).let { if (setId.isBlank() && attemptId.isBlank()) it else it + listOf(setId, attemptId) }.joinToString("|")

    fun sample(sessionId: String, timestamp: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): String =
        listOf("1", sessionId, timestamp, ax, ay, az, gx, gy, gz).joinToString("|")

    fun parse(payload: String): List<String> = payload.split('|')
}
