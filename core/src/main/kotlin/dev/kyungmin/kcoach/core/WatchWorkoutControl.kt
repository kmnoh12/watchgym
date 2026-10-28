package dev.kyungmin.kcoach.core

enum class WatchWorkoutAction { START_PREPARED, NEXT_SET, REST_ADJUST, REST_PAUSE, REST_RESUME, EDIT_SET, START_OTHER_SIDE, FINISH_WATCH_SIDE_ONLY, COMPLETE_OTHER_SIDE }
enum class WatchWorkoutControlStatus { APPLIED, DUPLICATE, STALE, BUSY, NO_PLAN, REJECTED }

data class WatchWorkoutOffer(val sessionId: String, val routineName: String, val exerciseName: String,
    val setId: String, val attemptId: String, val planHash: String, val expiresAtMs: Long) {
    init {
        require(listOf(sessionId, setId, attemptId).all { it.matches(CONTROL_ID) })
        require(CapturePurpose.forSession(sessionId) == CapturePurpose.WORKOUT)
        require(planHash.matches(CONTROL_HASH) && expiresAtMs > 0 && routineName.length <= 200 && exerciseName.length <= 200)
    }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageType = WireMessageType.HEARTBEAT,
        sessionId = sessionId, setId = setId, source = WireSource.PHONE, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "WORKOUT_OFFER_V1", "routineName" to routineName, "exerciseName" to exerciseName,
            "attemptId" to attemptId, "planHash" to planHash, "expiresAtMs" to expiresAtMs.toString())))
    companion object {
        fun decode(value: String): WatchWorkoutOffer {
            require(value.length <= 12_000)
            val e = WireCodec.decode(value)
            require(e.source == WireSource.PHONE && e.messageType == WireMessageType.HEARTBEAT && e.payload["kind"] == "WORKOUT_OFFER_V1")
            return WatchWorkoutOffer(e.sessionId, e.payload.getValue("routineName"), e.payload.getValue("exerciseName"),
                requireNotNull(e.setId), e.payload.getValue("attemptId"), e.payload.getValue("planHash"), e.payload.getValue("expiresAtMs").toLong())
        }
    }
}

data class WatchWorkoutControl(val commandId: String, val action: WatchWorkoutAction, val sessionId: String,
    val setId: String, val attemptId: String, val planHash: String? = null, val side: String? = null, val placement: String? = null,
    val deltaSeconds: Int? = null, val restSeconds: Int? = null, val restEndAtMs: Long? = null,
    val which: String? = null, val reps: Int = -1, val weightKg: Double? = null, val seq: Long = 0, val pausedRestRemainingMs: Long? = null) {
    init {
        require(seq >= 0 && (pausedRestRemainingMs == null || pausedRestRemainingMs >= 0))
        require(listOf(commandId, sessionId).all { it.matches(CONTROL_ID) })
        require((setId.matches(CONTROL_ID) && attemptId.matches(CONTROL_ID)) ||
            (action in setOf(WatchWorkoutAction.REST_ADJUST, WatchWorkoutAction.REST_PAUSE, WatchWorkoutAction.REST_RESUME, WatchWorkoutAction.EDIT_SET) && setId.isEmpty() && attemptId.isEmpty()))
        require(CapturePurpose.forSession(sessionId) == CapturePurpose.WORKOUT)
        require((action == WatchWorkoutAction.START_PREPARED) == (planHash != null))
        require(if (action in setOf(WatchWorkoutAction.REST_ADJUST, WatchWorkoutAction.REST_PAUSE, WatchWorkoutAction.REST_RESUME))
            deltaSeconds != null && restSeconds != null && restSeconds in 0..1800 && restEndAtMs != null && restEndAtMs >= 0
            else deltaSeconds == null && restSeconds == null && restEndAtMs == null)
        require(action != WatchWorkoutAction.REST_PAUSE || pausedRestRemainingMs != null)
        require(action != WatchWorkoutAction.REST_RESUME || pausedRestRemainingMs == null)
        require(pausedRestRemainingMs == null || action in setOf(WatchWorkoutAction.REST_ADJUST, WatchWorkoutAction.REST_PAUSE))
        require(if (action == WatchWorkoutAction.EDIT_SET)
            which in setOf("PREVIOUS", "NEXT", "CURRENT") && reps in -1..9999 && (weightKg == null || weightKg.isFinite() && weightKg >= 0) &&
                (reps >= 0 || weightKg != null) && (which != "CURRENT" || (reps == -1 && weightKg != null))
            else which == null && (if (action == WatchWorkoutAction.COMPLETE_OTHER_SIDE) reps in -1..9999 else reps == -1) && weightKg == null)
        require(planHash == null || planHash.matches(CONTROL_HASH))
        require((side == null && placement == null) ||
            (action == WatchWorkoutAction.START_PREPARED && side in setOf("LEFT", "RIGHT") && placement in setOf("OUTSIDE", "INSIDE")))
    }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageId = commandId, messageType = WireMessageType.HEARTBEAT,
        sessionId = sessionId, setId = setId, source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "WORKOUT_CONTROL_V1", "action" to action.name, "attemptId" to attemptId,
            "planHash" to planHash.orEmpty(), "side" to side.orEmpty(), "placement" to placement.orEmpty()) +
            (if (seq > 0 || pausedRestRemainingMs != null) mapOf("seq" to seq.toString(),
                "pausedRestRemainingMs" to (pausedRestRemainingMs?.toString() ?: "")) else emptyMap()) + (if (action in setOf(WatchWorkoutAction.REST_ADJUST, WatchWorkoutAction.REST_PAUSE, WatchWorkoutAction.REST_RESUME, WatchWorkoutAction.EDIT_SET, WatchWorkoutAction.COMPLETE_OTHER_SIDE)) mapOf(
            "deltaSeconds" to (deltaSeconds?.toString() ?: ""), "restSeconds" to (restSeconds?.toString() ?: ""),
            "restEndAtMs" to (restEndAtMs?.toString() ?: ""), "which" to which.orEmpty(), "reps" to reps.toString(),
            "weightKg" to (weightKg?.toString() ?: "")) else emptyMap())))
    companion object {
        fun decode(value: String): WatchWorkoutControl {
            require(value.length <= 12_000)
            val e = WireCodec.decode(value)
            require(e.source == WireSource.WATCH && e.messageType == WireMessageType.HEARTBEAT && e.payload["kind"] == "WORKOUT_CONTROL_V1")
            return WatchWorkoutControl(e.messageId, WatchWorkoutAction.valueOf(e.payload.getValue("action")), e.sessionId,
                e.setId.orEmpty(), e.payload.getValue("attemptId"), e.payload.getValue("planHash").ifEmpty { null },
                e.payload.getValue("side").ifEmpty { null }, e.payload.getValue("placement").ifEmpty { null },
                e.payload["deltaSeconds"]?.takeIf { it.isNotEmpty() }?.toInt(),
                e.payload["restSeconds"]?.takeIf { it.isNotEmpty() }?.toInt(),
                e.payload["restEndAtMs"]?.takeIf { it.isNotEmpty() }?.toLong(),
                e.payload["which"]?.takeIf { it.isNotEmpty() }, e.payload["reps"]?.toInt() ?: -1,
                e.payload["weightKg"]?.takeIf { it.isNotEmpty() }?.toDouble(),
                e.payload["seq"]?.toLong() ?: 0, e.payload["pausedRestRemainingMs"]?.takeIf { it.isNotEmpty() }?.toLong())
        }
    }
}

data class WatchWorkoutControlAck(val commandId: String, val sessionId: String, val setId: String,
    val attemptId: String, val status: WatchWorkoutControlStatus) {
    init {
        require(listOf(commandId, sessionId).all { it.matches(CONTROL_ID) })
        require((setId.matches(CONTROL_ID) && attemptId.matches(CONTROL_ID)) || (setId.isEmpty() && attemptId.isEmpty()))
    }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageType = WireMessageType.ACK, sessionId = sessionId,
        setId = setId, source = WireSource.PHONE, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "WORKOUT_CONTROL_ACK_V1", "commandId" to commandId, "attemptId" to attemptId, "status" to status.name)))
    companion object {
        fun decode(value: String): WatchWorkoutControlAck {
            require(value.length <= 12_000)
            val e = WireCodec.decode(value)
            require(e.source == WireSource.PHONE && e.messageType == WireMessageType.ACK && e.payload["kind"] == "WORKOUT_CONTROL_ACK_V1")
            return WatchWorkoutControlAck(e.payload.getValue("commandId"), e.sessionId, e.setId.orEmpty(),
                e.payload.getValue("attemptId"), WatchWorkoutControlStatus.valueOf(e.payload.getValue("status")))
        }
    }
}
private val CONTROL_ID = Regex("[A-Za-z0-9._-]{1,160}")
private val CONTROL_HASH = Regex("[0-9a-f]{64}")
