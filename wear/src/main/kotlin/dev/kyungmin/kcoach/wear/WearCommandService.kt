package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.os.Build
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import androidx.core.content.ContextCompat
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WirePayloadKeys
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import org.json.JSONObject
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

class WearCommandService : WearableListenerService() {
    private var emulatorBridge: EmulatorBridgeClient? = null

    override fun onCreate() {
        super.onCreate()
        dev.kyungmin.kcoach.updates.OtaCoordinator.get(this).recover()
        if (Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk_gwear", true)) emulatorBridge = EmulatorBridgeClient(::handlePayload).also { it.start() }
        sendPairHello()
    }

    override fun onDestroy() {
        emulatorBridge?.stop()
        emulatorBridge = null
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (dev.kyungmin.kcoach.updates.OtaDataLayer.receive(this, event.path, String(event.data), event.sourceNodeId)) return
        if (event.path == WearSync.PATH_PATTERN_PROFILE) {
            receivePatternProfile(String(event.data), event.sourceNodeId)
            return
        }
        handlePayload(event.path, String(event.data))
    }

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        dev.kyungmin.kcoach.updates.OtaDataLayer.receive(this, events)
    }

    override fun onPeerConnected(peer: com.google.android.gms.wearable.Node) {
        super.onPeerConnected(peer)
        dev.kyungmin.kcoach.updates.OtaCoordinator.get(this).recover()
    }

    private fun handlePayload(path: String, payload: String) {
        val receivedNs = android.os.SystemClock.elapsedRealtimeNanos()
        if (path == WatchHomeStore.PATH_OVERVIEW) {
            if (WatchHomeStore(this).receiveOverview(payload))
                sendBroadcast(Intent(WatchHomeStore.ACTION_OVERVIEW).setPackage(packageName))
            return
        }
        if (path == WearSync.PATH_WORKOUT_OFFER) {
            runCatching { check(WatchWorkoutOutbox(this).receiveOffer(payload)) }.onSuccess {
                sendBroadcast(Intent(WearWorkoutService.ACTION_WORKOUT_CONTROL).setPackage(packageName))
            }
            return
        }
        if (path == WearSync.PATH_WORKOUT_CONTROL_ACK) {
            if (runCatching { dev.kyungmin.kcoach.core.WatchWorkoutControlAck.decode(payload) }.isFailure) return
            startWorkoutOwner(Intent(this, WearWorkoutService::class.java)
                .putExtra(WearWorkoutService.EXTRA_COMMAND, "WORKOUT_CONTROL_ACK").putExtra("payload", payload))
            return
        }
        if (path == WearSync.PATH_PATTERN_PROFILE) {
            receivePatternProfile(payload, null)
            return
        }
        if (path == WearSync.PATH_CALIBRATION_CAMERA_STATE) {
            if (runCatching { dev.kyungmin.kcoach.core.CalibrationCameraState.decode(payload) }.isFailure) return
            startWorkoutOwner(Intent(this, WearWorkoutService::class.java)
                .putExtra(WearWorkoutService.EXTRA_COMMAND, "CALIBRATION_CAMERA_STATE").putExtra("payload", payload))
            return
        }
        if (path == WearSync.PATH_CLOCK_REQUEST) {
            val reply = runCatching {
                val request = WireCodec.decode(payload)
                val epoch = WearClockEpoch.current(this)
                val reply = dev.kyungmin.kcoach.core.ClockProbeProtocol.reply(request, receivedNs,
                    android.os.SystemClock.elapsedRealtimeNanos(), epoch.id, epoch.verified)
                WireCodec.encode(reply.copy(payload = reply.payload + mapOf("pairedReferenceSupported" to "true", "captureFormats" to "1,2",
                    "patternShadowCaptureSupported" to "true", "patternProfileFormat" to "1", "patternActiveBindingSupported" to "true",
                    "startWristContextFormat" to "1", "startDirectionProfileFormat" to "1", "tempoSettingsFormat" to "1", "calibrationCameraStateFormat" to "1")))
            }.getOrNull() ?: return
            Thread {
                runCatching { Tasks.await(Wearable.getNodeClient(this).connectedNodes, 2, TimeUnit.SECONDS).forEach { node ->
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, WearSync.PATH_CLOCK_REPLY, reply.toByteArray()), 2, TimeUnit.SECONDS)
                } }
                emulatorBridge?.send(WearSync.PATH_CLOCK_REPLY, reply)
            }.start()
            return
        }
        if (path == WearSync.PATH_MANUAL_ACK) {
            if (runCatching { dev.kyungmin.kcoach.core.ManualSetAck.decode(payload) }.isFailure) return
            startWorkoutOwner(Intent(this, WearWorkoutService::class.java)
                .putExtra(WearWorkoutService.EXTRA_COMMAND, "MANUAL_ACK").putExtra("payload", payload))
            return
        }
        if (path == WearSync.PATH_SET_RECORD) {
            if (runCatching { dev.kyungmin.kcoach.core.SetRecordCommand.decode(payload) }.isFailure) return
            startWorkoutOwner(Intent(this, WearWorkoutService::class.java)
                .putExtra(WearWorkoutService.EXTRA_COMMAND, "SET_RECORD").putExtra("payload", payload))
            return
        }
        if (path == WearSync.PATH_SET_ACK) {
            startWorkoutOwner(Intent(this, WearWorkoutService::class.java)
                .putExtra(WearWorkoutService.EXTRA_COMMAND, "SET_ACK").putExtra("payload", payload))
            return
        }
        if (path == WearSync.PATH_PROBE_START || path == WearSync.PATH_PROBE_STOP) {
            handleProbe(start = path == WearSync.PATH_PROBE_START)
            return
        }
        if (path == WearSync.PATH_HEARTBEAT) {
            val state = getSharedPreferences("workout-runtime", MODE_PRIVATE).getString("state", null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val ownsSession = state?.optString("session").orEmpty().isNotBlank()
            val hasCompletion = state != null && !state.isNull("pendingCompletion")
            val hasCaptures = getSharedPreferences("capture-outbox", MODE_PRIVATE).getStringSet("pendingCaptureIds", emptySet()).orEmpty().isNotEmpty()
            if (!ownsSession && !hasCompletion && !hasCaptures && ManualSetOutbox(this).pending().isEmpty() && WatchWorkoutOutbox(this).pending().isEmpty()) return
            dispatchCommand("HEARTBEAT", emptyList())
            return
        }
        if (path == WearSync.PATH_ACK_V3) {
            val envelope = runCatching { WireCodec.decode(payload) }.getOrNull() ?: return
            val captureId = envelope.payload[WirePayloadKeys.ACK_CAPTURE_ID] ?: return
            dispatchCommand("CAPTURE_ACK", listOf(captureId))
            return
        }
        if (path == WearSync.PATH_PERSONAL_PROFILE) {
            val profile = runCatching { PersonalCalibrationProfileCodec.decode(payload) }.getOrNull() ?: return
            getSharedPreferences("personal-profiles", MODE_PRIVATE)
                .edit()
                .putString(profile.exerciseId, payload)
                .commit()
            return
        }
        if (path == WearSync.PATH_PERSONAL_PROFILE_DELETE) {
            val exerciseId = payload.trim()
            if (exerciseId.isBlank()) return
            getSharedPreferences("personal-profiles", MODE_PRIVATE)
                .edit()
                .remove(exerciseId)
                .commit()
            return
        }
        if (path != WearSync.PATH_COMMAND && path != WearSync.PATH_PAIR) return
        val fields = WearSync.parse(payload)
        if (path == WearSync.PATH_PAIR) {
            if (fields.getOrNull(1) == "phone") {
                WatchWorkoutOutbox(this).receivePhoneHello(fields)
                sendBroadcast(Intent(WearWorkoutService.ACTION_WORKOUT_CONTROL).setPackage(packageName))
            }
            dispatchCommand("HELLO", fields)
            return
        }
        if (fields.size < 2) return
        dispatchCommand(fields[1], fields)
    }

    private fun receivePatternProfile(payload: String, sourceNodeId: String?) {
        val command = runCatching { dev.kyungmin.kcoach.core.PatternProfileCommand.decode(payload) }.getOrNull() ?: return
        val response = PatternProfileStore(this).stage(command).getOrElse {
            android.util.Log.w("KCoachPattern", "Profile staging failed; no acknowledgement sent", it)
            return
        }
        val encoded = response.encode()
        Thread {
            runCatching {
                val nodes = sourceNodeId?.takeIf { it.isNotBlank() }?.let(::listOf)
                    ?: Tasks.await(Wearable.getNodeClient(this).connectedNodes, 2, TimeUnit.SECONDS).map { it.id }
                nodes.forEach { node -> Tasks.await(Wearable.getMessageClient(this).sendMessage(node,
                    WearSync.PATH_PATTERN_PROFILE_ACK, encoded.toByteArray()), 2, TimeUnit.SECONDS) }
            }
            emulatorBridge?.send(WearSync.PATH_PATTERN_PROFILE_ACK, encoded)
        }.start()
    }

    private fun dispatchCommand(type: String, fields: List<String>) {
        val intent = Intent(this, WearWorkoutService::class.java).apply {
            putExtra(WearWorkoutService.EXTRA_COMMAND, type)
            if (type == "CAPTURE_ACK") {
                putExtra("captureId", fields.firstOrNull().orEmpty())
            } else if (fields.firstOrNull() == "2") {
                putExtra("session", fields.getOrNull(2).orEmpty())
                putExtra("exerciseId", fields.getOrNull(3).orEmpty())
                putExtra("exerciseName", fields.getOrNull(4).orEmpty())
                putExtra("setIndex", fields.getOrNull(5)?.toIntOrNull() ?: 0)
                putExtra("targetReps", fields.getOrNull(6)?.toIntOrNull() ?: 10)
                putExtra("weightKg", fields.getOrNull(7)?.toDoubleOrNull() ?: Double.NaN)
                putExtra("restSeconds", fields.getOrNull(8)?.toIntOrNull() ?: 60)
                putExtra("restEndAtMs", fields.getOrNull(9)?.toLongOrNull() ?: 0L)
                putExtra("commandRevision", fields.getOrNull(10)?.toLongOrNull() ?: 0L)
                putExtra("syncPhase", fields.getOrNull(11).orEmpty())
                putExtra("pausedRestRemainingMs", fields.getOrNull(12)?.toLongOrNull() ?: 0L)
                putExtra("setId", fields.getOrNull(13).orEmpty())
                putExtra("attemptId", fields.getOrNull(14).orEmpty())
                putExtra("patternShadowCommandId", fields.getOrNull(15).orEmpty())
                putExtra("patternBinding", fields.getOrNull(16).orEmpty())
                putExtra("patternWearing", fields.getOrNull(17).orEmpty())
                putExtra("patternSelectionMode", fields.getOrNull(18).orEmpty())
                putExtra("startWristContext", fields.getOrNull(19).orEmpty())
                putExtra("startDirectionSnapshot", fields.getOrNull(20).orEmpty())
                putExtra("tempoSettings", fields.getOrNull(21).orEmpty())
                if (fields.getOrNull(22)?.toLongOrNull() != null) putExtra("restRev", fields[22].toLong())
                putExtra("watchRestSeq", fields.getOrNull(23)?.toLongOrNull() ?: 0L)
            } else {
                putExtra("session", fields.getOrNull(2).orEmpty())
                putExtra("exerciseId", fields.getOrNull(3).orEmpty())
                putExtra("setIndex", fields.getOrNull(4)?.toIntOrNull() ?: 0)
                putExtra("targetReps", fields.getOrNull(5)?.toIntOrNull() ?: 10)
            }
        }
        startWorkoutOwner(intent)
    }

    private fun startWorkoutOwner(intent: Intent) {
        try { ContextCompat.startForegroundService(this, intent) }
        catch (error: RuntimeException) {
            // The sender's durable outbox retries: starting the service is never an application ACK.
            LocalExitDiagnostics.event("service-launch-failed", "", "UNAVAILABLE", 0, error)
            android.util.Log.e("KCoachWear", "Workout service launch deferred; no command acknowledgement", error)
        }
    }

    /** The phone's probe screen opened or closed: stream (or stop) without anyone touching the watch. */
    private fun handleProbe(start: Boolean) {
        val service = Intent(this, ProbeService::class.java)
        if (!start) {
            stopService(service)
            return
        }
        if (!ProbeStatus.running) runCatching { ContextCompat.startForegroundService(this, service) }
        // Bring the probe screen up when the system lets us (the app is on screen); the ongoing activity covers the rest.
        runCatching {
            startActivity(
                Intent(this, ProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun sendPairHello() {
        Thread {
            runCatching {
                emulatorBridge?.send(WearSync.PATH_PAIR, WearSync.hello("watch"))
            }
        }.start()
    }
}
