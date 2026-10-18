package dev.kyungmin.kcoach.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.SensorCaptureFormat
import dev.kyungmin.kcoach.core.SensorCaptureRecord
import dev.kyungmin.kcoach.core.SensorSample
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import dev.kyungmin.kcoach.core.ForearmRotationRepDetector
import dev.kyungmin.kcoach.core.ProfiledMotionRepDetector
import dev.kyungmin.kcoach.core.StartPoseGateConfig
import dev.kyungmin.kcoach.core.StartPoseStabilityGate
import dev.kyungmin.kcoach.core.TrackingSignal
import dev.kyungmin.kcoach.core.TrackingMode
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WireMessages
import dev.kyungmin.kcoach.core.KCoachEventMarker
import dev.kyungmin.kcoach.core.KCoachEventTrace
import dev.kyungmin.kcoach.core.PersonalCalibrationBuilder
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import kotlin.math.sqrt
import java.io.File
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/**
 * Owns the watch workout runtime. MainActivity only renders the state
 * broadcast by this service; sensors and timers must survive a round-display
 * transition or screen-off event.
 */
class WearWorkoutService : Service(), SensorEventListener {
    private enum class CalibrationStage {
        BASELINE,
        CONTROLLED_REPS,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var heartRateSensor: Sensor? = null

    private val calibrationCameraState by lazy { CalibrationWatchStateStore(this) }
    private var paired = false
    private var running = false
    private var resting = false
    private var countingPaused = false
    private var countingResetAfterNs = 0L
    private var editStatus = ""
    private var paused = false
    private var pausedRestRemainingMs = 0L
    private var lastPhoneHeartbeatAtMs = 0L
    private val recordState by lazy { WatchRecordState(this) }
    private var sessionId = ""
    private var exerciseId = ""
    private var exerciseName = "운동 대기 중"
    private var setIndex = 0
    private var setId = ""
    private var attemptId = ""
    private val dataCleanupInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val setEndGate = dev.kyungmin.kcoach.core.SetEndGate()
    private var learnedRepWaitNs = 3_000_000_000L
    /** Silence that ends a set: 7 s while reps are still owed (a heavy set pausing mid-way), the usual 3 s once the target is met. */
    private fun setEndWaitNs() = dev.kyungmin.kcoach.core.SetEndGate.waitNs(reps, targetReps,
        if (activeDetector != null) learnedRepWaitNs else 3_000_000_000L)
    private val setEndTicker = object : Runnable {
        override fun run() {
            if (running && measurementReady && calibrationStage == null &&
                trackingProfile.mode != TrackingMode.MANUAL_REQUIRED && !paused && !countingPaused &&
                dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId) == dev.kyungmin.kcoach.core.CapturePurpose.WORKOUT) {
                val candidate = forearmRotationDetector?.candidateStartNs ?: profiledMotionDetector?.candidateStartNs
                if (setEndGate.shouldEnd(android.os.SystemClock.elapsedRealtimeNanos(), candidate,
                        profiledMotionDetector?.maximumCycleNs ?: (trackingProfile.maximumRepMs * 1_000_000L),
                        setEndWaitNs())) {
                    try { completeSet() } catch (error: Exception) { runtimeFailure("set-timer", error) }
                }
            }
            if (!serviceDestroyed && (running || paused) && trackingProfile.mode != TrackingMode.MANUAL_REQUIRED)
                handler.postDelayed(this, if (paused) 1_000L else 100L)
        }
    }
    private var targetReps = 10
    private var trackingProfile = ExerciseTrackingProfiles.resolve("", "")
    private var forearmRotationDetector: ForearmRotationRepDetector? = null
    private var profiledMotionDetector: ProfiledMotionRepDetector? = null
    private var weightKg: Double? = null
    private var restSeconds = 60
    private var phoneRev = 0L
    private var mySeq = 0L
    private var restEndAtMs = 0L
    private var reps = 0
    private var heartRate = 0
    private var lastPeakAtMs = 0L
    private var lastStateAtMs = 0L
    private var lastUiBroadcastAtMs = 0L
    private var sensorEventCount = 0
    private var uiBroadcastCount = 0
    private var liveStateCount = 0
    private var maxObservedOutboundQueue = 0
    private var lastCaptureTimestampNs = 0L
    private var lastSensorAccuracy = -1
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastAccelerometerNs: Long? = null
    private var lastAccelerometerAccuracy: Int? = null
    private var lastHeartRateNs: Long? = null
    private val captureRecords = ArrayList<SensorCaptureRecord>(CAPTURE_CHUNK_SAMPLES)
    private val captureChunkFiles = mutableListOf<File>()
    private var captureSegmentId = ""
    private var captureBootCount: Int? = null
    private var sensorClockNeedsCheck = false
    private var captureAttemptInterrupted = false
    private var captureRecoveryError: String? = null
    private var captureSampleCount = 0
    private var captureChunkIndex = 0
    private var finalizingCaptureId: String? = null
    private var captureLimitLogged = false
    private var restTicker: Runnable? = null
    private var phoneWatchdog: Runnable? = null
    private var calibrationStage: CalibrationStage? = null
    private var measurementReady = false
    private var measurementArmed = false
    private var startPoseGate: StartPoseStabilityGate? = null
    /** How long the wrist has been held still so far (calibration countdown on the watch screen). */
    private var preparationHoldMs = 0L
    private val capturePrefs by lazy { getSharedPreferences(CAPTURE_PREFS, MODE_PRIVATE) }
    private val captureSendInFlight = mutableSetOf<String>()
    private val outboundSequence = AtomicLong()
    private val outboundQueue = PriorityBlockingQueue<Runnable>()
    private val outboundExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        outboundQueue,
        { runnable -> Thread(runnable, "kcoach-watch-outbound").apply { isDaemon = true } },
    )
    private val captureTransferExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "kcoach-capture-upload").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var serviceDestroyed = false
    private val runtimePrefs by lazy { getSharedPreferences("workout-runtime", MODE_PRIVATE) }
    private var pendingCompletion: String? = null
    private var lastAppliedCommand = ""
    private var lastCommandRevision = 0L
    private val completionRetry = Runnable { replayCompletion() }
    private val manualOutbox by lazy { ManualSetOutbox(this) }
    private val patternProfiles by lazy { PatternProfileStore(this) }
    private var patternShadowCommandId = ""
    private var frozenShadow: FrozenPatternProfile? = null
    private var shadowDetector: dev.kyungmin.kcoach.core.ReviewedPatternDetector? = null
    private var shadowUseConfirmed = false
    private var shadowCount = 0
    private var shadowInterrupted = false
    private var shadowSampleCalls = 0L
    private val shadowProcessingHistogram = LongArray(64)
    private val shadowEvents = mutableListOf<dev.kyungmin.kcoach.core.PatternTrialEvent>()
    private var patternBinding = ""
    private var patternWearing = ""
    private var startWristContext = ""
    private var startWristDeviceId = ""
    private var startDirectionSnapshot = ""
    private var startDirectionStatus = "UNCONFIRMED"
    private var countingStartNs: Long? = null
    private var tempoSettings = ""
    private var tempoMuted = false
    private var tempoHint = ""
    private var lastTempoAlertNs = 0L
    private var tempoPolicy = dev.kyungmin.kcoach.core.TempoFeedbackPolicy()
    private var patternSelectionMode = ""
    private var frozenActive: FrozenPatternProfile? = null
    private var activeDetector: dev.kyungmin.kcoach.core.ReviewedPatternDetector? = null
    private var disableUseConfirmed = false
    private var activeSampleCalls = 0
    private var activeUseConfirmed = false
    private var activeInterrupted = false
    private val manualRetry = Runnable { replayManualSets() }
    private val workoutControls by lazy { WatchWorkoutOutbox(this) }
    private val workoutControlRetry = Runnable { replayWorkoutControls() }
    private val pendingCaptureRetry = Runnable { retryPendingCaptures() }

    private var startupFailed = false
    override fun onCreate() {
        super.onCreate()
        try { createRuntime() } catch (error: RuntimeException) {
            startupFailed = true
            LocalExitDiagnostics.event("service-start-failed", sessionId, currentPhase(), lastCaptureTimestampNs, error)
            stopSelf() // Never claim a healthy owner after an FGS/permission/sensor startup failure.
        }
    }
    private fun createRuntime() {
        serviceDestroyed = false
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        createNotificationChannel()
        // Android 14/15 validates the health FGS runtime permission at startup.
        // Generic Wear emulators intentionally run the fake telemetry lane
        // without BODY_SENSORS/ACTIVITY_RECOGNITION, so use the manifest-declared
        // dataSync type only on that lane. Physical watches stay on health FGS.
        if (isEmulator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (isEmulator) emulatorBridge.start()
        restoreRuntime()
        retryDataCleanup()
        writeStorageReport()
        startPhoneWatchdog()
        schedulePendingCaptureRetry(1_500L)
        broadcastState(force = true)
        if (pendingCompletion != null) replayCompletion()
        replayManualSets()
        replayWorkoutControls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupFailed) return START_NOT_STICKY
        return try { handleStartCommand(intent, flags, startId) }
        catch (error: Exception) {
            LocalExitDiagnostics.event("command-deferred", sessionId, currentPhase(), lastCaptureTimestampNs, error)
            Log.e(TAG, "Command deferred; no application acknowledgement", error)
            restartMode()
        }
    }

    private fun handleStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra(EXTRA_COMMAND)
        if (intent != null && type in setOf("REST", "SYNC") && (intent.hasExtra("restRev") || phoneRev > 0)) {
            val sameSession = intent.getStringExtra("session") == sessionId
            val rev = intent.getLongExtra("restRev", 0)
            if (sameSession && rev <= phoneRev) {
                markPhoneAlive(); acknowledgeCommand(intent, "DUPLICATE"); return restartMode()
            }
            if (sameSession && intent.getLongExtra("watchRestSeq", 0) < mySeq) {
                phoneRev = rev
                if (!checkpoint()) {
                    runtimeFailure("command-checkpoint", java.io.IOException("Rest revision checkpoint failed"))
                    return restartMode()
                }
                markPhoneAlive(); acknowledgeCommand(intent, "APPLIED"); return restartMode()
            }
        }
        if (type in MUTATING_COMMANDS && intent != null) {
            val rejected = commandStatus(intent)
            if (rejected != null) {
                if (rejected == "DUPLICATE") markPhoneAlive()
                acknowledgeCommand(intent, rejected)
                if (rejected == "WAITING_COMPLETION") { replayCompletion(); replayManualSets() }
                val mode = restartMode()
                if (mode == START_NOT_STICKY) stopRuntimeService(startId)
                return mode
            }
            if (captureRecoveryError != null && !recoverCaptureChunks(captureSampleCount.takeIf { it > 0 || captureChunkFiles.isEmpty() })) {
                val replacement = type in setOf("START", "SET", "REST", "SYNC") &&
                    (intent.getStringExtra("session") != sessionId || intent.getStringExtra("exerciseId") != exerciseId || !sameSet(intent))
                if (replacement || type in setOf("STOP", "END", "COMPLETE")) {
                    if (!quarantineIncompleteCapture()) { acknowledgeCommand(intent, "CAPTURE_RECOVERY_REQUIRED"); return restartMode() }
                } else {
                    acknowledgeCommand(intent, "CAPTURE_RECOVERY_REQUIRED")
                    if (pendingCompletion != null) replayCompletion()
                    broadcastState(force = true)
                    return restartMode()
                }
            }
            val incoming = intent.getStringExtra("session").orEmpty()
            if (sessionId.isNotBlank() && incoming != sessionId) {
                val saved = runCatching {
                    check(checkpoint())
                    if (captureSampleCount > 0) {
                        val capture = saveCapture()
                        check(captureSampleCount == 0 || capture != null) { "Previous session capture could not be saved" }
                        capture?.let(::sendCaptureToPhone); resetCaptureBuffer(deleteChunks = true)
                    }
                    closeSessionId(sessionId)
                    pendingCompletion = null // Explicit replacement closes the old session after preserving its raw capture.
                }
                if (saved.isFailure) { Log.e(TAG, "Session switch deferred; original capture retained", saved.exceptionOrNull()); return restartMode() }
            }
        }
        val applied = runCatching {
        if (type in MUTATING_COMMANDS && intent != null) {
            if (intent.getStringExtra("session") != sessionId) { phoneRev = 0; mySeq = 0 }
            if (intent.hasExtra("restRev")) {
                phoneRev = maxOf(phoneRev, intent.getLongExtra("restRev", 0))
                mySeq = maxOf(mySeq, intent.getLongExtra("watchRestSeq", 0))
            }
        }
        when (type) {
            "SET_RECORD" -> {
                recordState.receive(intent?.getStringExtra("payload").orEmpty())?.let {
                    broadcastState(force = true)
                }
            }
            "WORKOUT_CONTROL_SUBMIT" -> {
                val request = dev.kyungmin.kcoach.core.WatchWorkoutControl.decode(requireNotNull(intent?.getStringExtra("payload")))
                check(checkpoint()) { "Preserve raw before requesting a workout transition" }
                check(workoutControls.enqueue(request)) { "A workout request is already waiting" }
                replayWorkoutControls()
                sendBroadcast(Intent(ACTION_WORKOUT_CONTROL).setPackage(packageName))
            }
            "WORKOUT_CONTROL_ACK" -> {
                val ack = dev.kyungmin.kcoach.core.WatchWorkoutControlAck.decode(requireNotNull(intent?.getStringExtra("payload")))
                if (workoutControls.acknowledge(ack)) {
                    replayWorkoutControls()
                    broadcastState(force = true)
                    sendBroadcast(Intent(ACTION_WORKOUT_CONTROL).setPackage(packageName).putExtra("status", ack.status.name))
                }
            }
            "PATTERN_FRAME_COMMIT" -> {
                val frozen = frozenShadow
                val index = intent?.getIntExtra("eventIndex", -1) ?: -1
                val at = intent?.getLongExtra("feedbackAtNs", -1L) ?: -1L
                if (frozen != null && intent?.getStringExtra("patternCommandId") == frozen.commandId &&
                    intent.getStringExtra("session") == sessionId && intent.getStringExtra("setId") == setId &&
                    intent.getStringExtra("attemptId") == attemptId && index in shadowEvents.indices) {
                    val event = shadowEvents[index]
                    if (at >= event.processedAtNs && at <= android.os.SystemClock.elapsedRealtimeNanos() &&
                        intent.getLongExtra("sourceEmissionNs", -1L) == event.sourceEmissionNs && event.feedbackAtNs == null) {
                        shadowEvents[index] = event.copy(feedbackAtNs = at)
                        persistRuntime()
                    }
                }
            }
            "COUNTING_PAUSE", "COUNTING_RESUME", "REST_ADJUST", "REST_PAUSE", "REST_RESUME", "EDIT_SET" -> handleEditorCommand(intent!!)
            "SYNC" -> applySync(intent!!)
            "SET_ACK" -> acknowledgeCompletion(intent?.getStringExtra("payload").orEmpty())
            "MANUAL_SUBMIT" -> submitManualSet(intent?.getStringExtra("payload").orEmpty())
            "MANUAL_ACK" -> {
                val ack = dev.kyungmin.kcoach.core.ManualSetAck.decode(intent?.getStringExtra("payload").orEmpty())
                if (manualOutbox.acknowledge(ack)) {
                    markPhoneAlive()
                    replayManualSets()
                    broadcastState(force = true)
                    if (ack.status in setOf(dev.kyungmin.kcoach.core.ManualSetStatus.APPLIED, dev.kyungmin.kcoach.core.ManualSetStatus.DUPLICATE))
                        sendToPhone(WearSync.PATH_RECONNECT, WearSync.watchUiReady(sessionId), coalescible = false)
                }
            }
            "CALIBRATION_CAMERA_STATE" -> {
                val state = dev.kyungmin.kcoach.core.CalibrationCameraState.decode(intent!!.getStringExtra("payload").orEmpty())
                if ((sessionId.isBlank() || sessionId == state.sessionId) && calibrationCameraState.update(state)) broadcastState(force = true)
            }
            "QUERY" -> {
                broadcastState(force = true)
                sendLiveState(currentPhase(), running, force = true)
            }
            "HEARTBEAT" -> {
                markPhoneAlive()
                // Phone heartbeat messages can arrive after COMPLETE and would
                // otherwise recreate the foreground workout service forever.
                // Keep the service alive only while a session is still owned.
                if (sessionId.isBlank() && pendingCompletion == null && manualOutbox.pending().isEmpty() && workoutControls.pending().isEmpty() && capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().isEmpty()) {
                    stopRuntimeService(startId)
                }
            }
            "HELLO" -> markPhoneAlive()
            "START", "SET" -> startSession(intent)
            "REST" -> startRest(intent)
            "PAUSE" -> pauseSession()
            "RESUME" -> resumeSession()
            "START_DIRECTION_BYPASS" -> {
                require(intent.getStringExtra("session") == sessionId && intent.getStringExtra("setId") == setId &&
                    intent.getStringExtra("attemptId") == attemptId) { "Bypass belongs to a different attempt" }
                if (running && !measurementReady && startDirectionSnapshot.isNotEmpty()) {
                    startDirectionStatus = "BYPASSED"
                    // The next original sensor sample is the first counting sample, not a synthetic callback time.
                    beginMeasurementCounting()
                    check(checkpoint())
                    sendLiveState(currentWorkingPhase(), true, force = true); broadcastState(force = true)
                }
            }
            "TEMPO_MUTE" -> {
                require(intent.getStringExtra("session") == sessionId && intent.getStringExtra("setId") == setId &&
                    intent.getStringExtra("attemptId") == attemptId)
                tempoMuted = true; tempoHint = "이번 세트 템포 알림 꺼짐"
                tempoPolicy.configure(dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Config())
                check(checkpoint()); broadcastState(force = true)
            }
            "STOP", "END" -> finishSession("STOPPED", startId)
            "COMPLETE" -> finishSession("COMPLETE", startId)
            "CAPTURE_ACK" -> acknowledgeCapture(intent.getStringExtra("captureId").orEmpty())
            "SET_COMPLETE" -> {
                completeSet(manual = true)
                startRest(intent)
            }
        }
        }
        if (applied.isFailure) {
            val error = requireNotNull(applied.exceptionOrNull())
            LocalExitDiagnostics.event("command-deferred", sessionId, currentPhase(), lastCaptureTimestampNs, error)
            Log.e(TAG, "Command deferred; durable state retained", error)
            if (captureRecoveryError == null && !checkpoint()) runtimeFailure("command-checkpoint", error)
            return restartMode()
        }
        if (type in MUTATING_COMMANDS && intent != null) {
            val previousRevision = lastCommandRevision
            val previousCommand = lastAppliedCommand
            lastCommandRevision = maxOf(lastCommandRevision, intent.getLongExtra("commandRevision", 0))
            lastAppliedCommand = commandFingerprint(intent)
            if (checkpoint()) acknowledgeCommand(intent, "APPLIED") else {
                lastCommandRevision = previousRevision
                lastAppliedCommand = previousCommand
                runtimeFailure("command-checkpoint", java.io.IOException("Command checkpoint failed"))
            }
        }
        val mode = restartMode()
        if (mode == START_NOT_STICKY) stopRuntimeService(startId)
        return mode
    }

    override fun onDestroy() {
        serviceDestroyed = true
        handler.removeCallbacks(setEndTicker)
        if (!startupFailed) checkpoint() // A failed startup has not restored the original owner yet.
        LocalExitDiagnostics.event("service-destroyed", sessionId, currentPhase(), lastCaptureTimestampNs)
        stopRestTicker()
        phoneWatchdog?.let(handler::removeCallbacks)
        phoneWatchdog = null
        handler.removeCallbacks(pendingCaptureRetry)
        handler.removeCallbacks(completionRetry)
        handler.removeCallbacks(manualRetry)
        handler.removeCallbacks(workoutControlRetry)
        if (::sensorManager.isInitialized) runCatching { sensorManager.unregisterListener(this) }
        emulatorBridge.stop()
        outboundExecutor.shutdown()
        captureTransferExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun commandStatus(intent: Intent): String? {
        val revision = intent.getLongExtra("commandRevision", 0)
        val newerRest = intent.hasExtra("restRev") && intent.getStringExtra(EXTRA_COMMAND) in setOf("REST", "SYNC") &&
            intent.getLongExtra("restRev", 0) > phoneRev
        if (!newerRest && revision > 0 && revision <= lastCommandRevision) {
            return if (revision == lastCommandRevision && lastAppliedCommand.isNotBlank() && lastAppliedCommand == commandFingerprint(intent)) "DUPLICATE" else "STALE"
        }
        val incoming = intent.getStringExtra("session").orEmpty()
        val type = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        if (incoming.isBlank()) return "INVALID"
        if (runtimePrefs.getString("closedSessions", "").orEmpty().lines().contains(incoming)) return "CLOSED_SESSION"
        if (incoming != sessionId && type !in setOf("START", "SYNC") && !(sessionId.isBlank() && type == "SET")) return "WRONG_SESSION"
        if (incoming != sessionId && sessionId.isNotBlank() && revision == 0L && lastCommandRevision > 0) return "STALE"
        if (type == "SYNC" && intent.getStringExtra("syncPhase") !in setOf("WORKING", "RESTING", "PAUSED")) return "INVALID"
        val requestedShadow = intent.getStringExtra("patternShadowCommandId").orEmpty()
        val workingRequest = type in setOf("START", "SET") || type == "SYNC" &&
            (intent.getStringExtra("syncPhase") == "WORKING" || intent.getStringExtra("syncPhase") == "PAUSED" &&
                intent.getLongExtra("pausedRestRemainingMs", 0L) == 0L)
        if (intent.getStringExtra("patternSelectionMode").orEmpty() !in setOf("", "VERIFIED_PATTERN")) return "INVALID"
        val requestedStart = intent.getStringExtra("startWristContext").orEmpty()
        if (requestedStart.isNotEmpty()) {
            val context = runCatching { dev.kyungmin.kcoach.core.StartWristContextCodec.decode(requestedStart) }.getOrNull() ?: return "INVALID"
            val wearing = runCatching { dev.kyungmin.kcoach.core.PatternWearingContext.decode(intent.getStringExtra("patternWearing").orEmpty()) }.getOrNull() ?: return "INVALID"
            if (context.exerciseId != dev.kyungmin.kcoach.core.ExerciseMotionCatalog.normalizeExerciseId(intent.getStringExtra("exerciseId").orEmpty()) ||
                context.owner != wearing.owner || context.side != wearing.side || context.placement != wearing.placement) return "INVALID"
        }
        if ((running || paused && pausedRestRemainingMs == 0L) && incoming == sessionId && sameSet(intent) &&
            workingRequest && requestedStart != startWristContext) return "START_CONTEXT_CHANGED"
        val requestedDirection = intent.getStringExtra("startDirectionSnapshot").orEmpty()
        if (workingRequest && requestedDirection.isNotEmpty()) {
            val profile = runCatching {
                dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.hash(requestedDirection)
                dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.decode(requestedDirection)
            }.getOrNull() ?: return "INVALID"
            if (dev.kyungmin.kcoach.core.CapturePurpose.forSession(incoming) != dev.kyungmin.kcoach.core.CapturePurpose.WORKOUT ||
                profile.key.deviceId != WearClockEpoch.current(this).deviceId ||
                profile.key.context != runCatching { dev.kyungmin.kcoach.core.StartWristContextCodec.decode(requestedStart) }.getOrNull()) return "INVALID"
        }
        if ((running || paused && pausedRestRemainingMs == 0L) && incoming == sessionId && sameSet(intent) &&
            workingRequest && requestedDirection != startDirectionSnapshot) return "START_PROFILE_CHANGED"
        val requestedTempo = intent.getStringExtra("tempoSettings").orEmpty()
        if (workingRequest && requestedTempo.isNotEmpty()) {
            val settings = runCatching {
                dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.hash(requestedTempo)
                dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.decode(requestedTempo)
            }.getOrNull() ?: return "INVALID"
            if (dev.kyungmin.kcoach.core.CapturePurpose.forSession(incoming) != dev.kyungmin.kcoach.core.CapturePurpose.WORKOUT ||
                settings.exerciseId != dev.kyungmin.kcoach.core.ExerciseMotionCatalog.normalizeExerciseId(intent.getStringExtra("exerciseId").orEmpty())) return "INVALID"
        }
        if ((running || paused && pausedRestRemainingMs == 0L) && incoming == sessionId && sameSet(intent) &&
            workingRequest && requestedTempo != tempoSettings) return "TEMPO_SETTINGS_CHANGED"
        val binding = if (type in setOf("START", "SET", "SYNC")) runCatching { incomingPatternBinding(intent) }.getOrElse { return "INVALID" }
            else intent.getStringExtra("patternBinding").orEmpty()
        if (binding.isNotEmpty() && type in setOf("START", "SET", "SYNC")) {
            val key = runCatching { dev.kyungmin.kcoach.core.PatternCompatibilityCodec.decode(binding) }.getOrNull() ?: return "INVALID"
            if (key.deviceId != WearClockEpoch.current(this).deviceId ||
                key.recipe != dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.resolve(intent.getStringExtra("exerciseId").orEmpty()).recipe)
                return "INVALID"
        }
        if (running && incoming == sessionId && sameSet(intent) && workingRequest && binding != patternBinding)
            return "PATTERN_BINDING_CHANGED"
        if (requestedShadow.isNotEmpty() && type in setOf("START", "SET", "SYNC")) {
            val profile = patternProfiles.requestedShadow(requestedShadow).getOrNull()?.runtime ?: return "PATTERN_NOT_READY"
            if (profile.compatibility.recipe != dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.resolve(intent.getStringExtra("exerciseId").orEmpty()).recipe)
                return "PATTERN_NOT_READY"
        }
        if (pendingCompletion != null && incoming == sessionId && (type in setOf("START", "SET") || (type == "SYNC" && intent.getStringExtra("syncPhase") == "WORKING"))) return "WAITING_COMPLETION"
        if (manualOutbox.latest(sessionId, setId, attemptId)?.ack == null &&
            manualOutbox.latest(sessionId, setId, attemptId) != null && incoming == sessionId && sameSet(intent) &&
            (type in setOf("START", "SET", "RESUME") || (type == "SYNC" && intent.getStringExtra("syncPhase") == "WORKING"))) return "WAITING_COMPLETION"
        return null
    }

    private fun commandFingerprint(intent: Intent): String {
        val fields = listOf(
            intent.getStringExtra(EXTRA_COMMAND).orEmpty(), intent.getStringExtra("session").orEmpty(),
            intent.getStringExtra("exerciseId").orEmpty(), intent.getStringExtra("exerciseName").orEmpty(),
            intent.getStringExtra("setId").orEmpty(), intent.getStringExtra("attemptId").orEmpty(),
            intent.getIntExtra("setIndex", 0).toString(), intent.getIntExtra("targetReps", 10).toString(),
            intent.getDoubleExtra("weightKg", Double.NaN).toString(), intent.getIntExtra("restSeconds", 60).toString(),
            intent.getLongExtra("restEndAtMs", 0L).toString(), intent.getStringExtra("syncPhase").orEmpty(),
            intent.getLongExtra("pausedRestRemainingMs", 0L).toString(),
            intent.getStringExtra("patternShadowCommandId").orEmpty(),
        )
        var boundFields = intent.getStringExtra("patternBinding")?.takeIf { it.isNotEmpty() }?.let { fields + it } ?: fields
        intent.getStringExtra("patternWearing")?.takeIf { it.isNotEmpty() }?.let { boundFields = boundFields + it }
        intent.getStringExtra("patternSelectionMode")?.takeIf { it.isNotEmpty() }?.let { boundFields = boundFields + it }
        intent.getStringExtra("startWristContext")?.takeIf { it.isNotEmpty() }?.let { boundFields = boundFields + it }
        intent.getStringExtra("startDirectionSnapshot")?.takeIf { it.isNotEmpty() }?.let { boundFields = boundFields + it }
        intent.getStringExtra("tempoSettings")?.takeIf { it.isNotEmpty() }?.let { boundFields = boundFields + it }
        if (intent.hasExtra("restRev")) boundFields = boundFields + intent.getLongExtra("restRev", 0).toString() +
            intent.getLongExtra("watchRestSeq", 0).toString()
        return SensorCaptureFormat.sha256(JSONArray(boundFields).toString().toByteArray(Charsets.UTF_8))
    }

    private fun acknowledgeCommand(intent: Intent, status: String) {
        val revision = intent.getLongExtra("commandRevision", 0)
        if (revision <= 0) return
        val receipt = if (status in setOf("APPLIED", "DUPLICATE") &&
            intent.getStringExtra("startDirectionSnapshot").orEmpty().isNotEmpty() &&
            intent.getStringExtra("startDirectionSnapshot") == startDirectionSnapshot)
            dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.hash(startDirectionSnapshot) else ""
        val tempoReceipt = if (status in setOf("APPLIED", "DUPLICATE") && intent.getStringExtra("tempoSettings").orEmpty().isNotEmpty() &&
            intent.getStringExtra("tempoSettings") == tempoSettings) dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.hash(tempoSettings) else ""
        val payload = listOf("1", intent.getStringExtra("session").orEmpty(), revision, status, lastCommandRevision)
            .let { if (tempoReceipt.isNotEmpty()) it + receipt + tempoReceipt else if (receipt.isNotEmpty()) it + receipt else it }.joinToString("|")
        sendToPhone(WearSync.PATH_COMMAND_ACK, payload, coalescible = false)
    }

    private fun applySync(intent: Intent) {
        val same = sessionId == intent.getStringExtra("session") && exerciseId == intent.getStringExtra("exerciseId") && sameSet(intent)
        if (same) setIndex = intent.getIntExtra("setIndex", setIndex)
        when (intent.getStringExtra("syncPhase")) {
            "RESTING" -> startRest(intent)
            "PAUSED" -> {
                val remaining = intent.getLongExtra("pausedRestRemainingMs", 0)
                if (remaining > 0) {
                    startRest(Intent(intent).putExtra("restEndAtMs", System.currentTimeMillis() + remaining))
                    pauseSession()
                    restEndAtMs = intent.getLongExtra("restEndAtMs", 0)
                    pausedRestRemainingMs = remaining
                    broadcastState(force = true)
                } else {
                    if (!same || (!running && !paused)) startSession(intent)
                    pauseSession()
                }
            }
            "WORKING" -> {
                if (same && paused && pausedRestRemainingMs == 0L) resumeSession() else startSession(intent)
            }
        }
    }

    private fun replayCompletion() {
        handler.removeCallbacks(completionRetry)
        val payload = pendingCompletion ?: return
        if (captureRecoveryError == null && checkpoint()) sendToPhone(WearSync.PATH_LIVE, payload, coalescible = false)
        if (!serviceDestroyed) handler.postDelayed(completionRetry, 3_000L)
    }

    private fun submitManualSet(payload: String) {
        val command = dev.kyungmin.kcoach.core.ManualSetCommand.fromEnvelope(WireCodec.decode(payload))
        require(command.sessionId == sessionId && command.exerciseId == exerciseId && command.setIndex == setIndex &&
            command.setId == setId && command.attemptId == attemptId && calibrationStage == null && !resting && pausedRestRemainingMs == 0L)
        check(checkpoint()) { "Raw capture must be preserved before manual completion" }
        check(manualOutbox.enqueue(command)) { "Manual count is already pending or belongs to an older revision" }
        countingPaused = false
        running = false
        reps = command.performedReps
        unregisterMotionSensors()
        cancelMeasurementPreparation()
        check(checkpoint())
        replayManualSets()
        broadcastState(force = true)
    }

    /** Local UI requests have attempt guards, but never advance the phone command revision. */
    private fun handleEditorCommand(intent: Intent) {
        if (intent.getStringExtra("session") != sessionId || !sameSet(intent) || sessionId.isBlank()) {
            editStatus = "STALE"; broadcastState(force = true); return
        }
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            "COUNTING_PAUSE", "COUNTING_RESUME" -> {
                if (!running || resting || paused) return
                val pause = intent.getStringExtra(EXTRA_COMMAND) == "COUNTING_PAUSE"
                if (countingPaused != pause) {
                    resetCountingWindow()
                    countingPaused = pause
                    countingResetAfterNs = android.os.SystemClock.elapsedRealtimeNanos()
                    tempoPolicy.pause()
                    check(checkpoint())
                }
            }
            "REST_PAUSE", "REST_RESUME" -> {
                val pause = intent.getStringExtra(EXTRA_COMMAND) == "REST_PAUSE"
                if (pause && !resting || !pause && (!paused || pausedRestRemainingMs <= 0)) return
                val now = System.currentTimeMillis()
                val remaining = if (pause) (restEndAtMs - now).coerceAtLeast(0) else pausedRestRemainingMs
                val nextSeq = mySeq + 1
                val request = dev.kyungmin.kcoach.core.WatchWorkoutControl(java.util.UUID.randomUUID().toString(),
                    if (pause) dev.kyungmin.kcoach.core.WatchWorkoutAction.REST_PAUSE else dev.kyungmin.kcoach.core.WatchWorkoutAction.REST_RESUME,
                    sessionId, setId, attemptId, deltaSeconds = 0, restSeconds = restSeconds,
                    restEndAtMs = if (pause) 0 else now + remaining, seq = nextSeq,
                    pausedRestRemainingMs = if (pause) remaining else null)
                check(workoutControls.enqueue(request))
                mySeq = nextSeq
                if (pause) { pauseSession(); pausedRestRemainingMs = remaining; restEndAtMs = 0 }
                else resumeSession(request.restEndAtMs)
                check(checkpoint()); replayWorkoutControls()
            }
            "REST_ADJUST", "EDIT_SET" -> {
                val adjusting = intent.getStringExtra(EXTRA_COMMAND) == "REST_ADJUST"
                val editingCurrent = !adjusting && intent.getStringExtra("which") == "CURRENT"
                if (editingCurrent) {
                    val weight = intent.getDoubleExtra("weightKg", Double.NaN)
                    if (!running || resting || paused || calibrationStage != null ||
                        intent.getIntExtra("reps", -1) != -1 || !weight.isFinite() || weight < 0) return
                } else if (!resting && !(adjusting && paused && pausedRestRemainingMs > 0)) return
                if (!adjusting && intent.getIntExtra("reps", -1) == -1 && intent.getDoubleExtra("weightKg", Double.NaN).isNaN()) return
                val delta = intent.getIntExtra("deltaSeconds", 0)
                val now = System.currentTimeMillis()
                val seconds = (restSeconds.toLong() + delta).coerceIn(0L, 1800L).toInt()
                val remaining = (pausedRestRemainingMs + delta.toLong() * 1000L).coerceIn(0, 1_800_000L)
                val end = if (paused) 0 else (restEndAtMs + delta.toLong() * 1000L).coerceIn(now, now + 1_800_000L)
                val request = dev.kyungmin.kcoach.core.WatchWorkoutControl(
                    java.util.UUID.randomUUID().toString(),
                    if (adjusting) dev.kyungmin.kcoach.core.WatchWorkoutAction.REST_ADJUST else dev.kyungmin.kcoach.core.WatchWorkoutAction.EDIT_SET,
                    sessionId, setId, attemptId,
                    deltaSeconds = if (adjusting) delta else null, restSeconds = if (adjusting) seconds else null,
                    restEndAtMs = if (adjusting) end else null,
                    seq = if (adjusting) mySeq + 1 else 0,
                    pausedRestRemainingMs = if (adjusting && paused) remaining else null,
                    which = if (adjusting) null else intent.getStringExtra("which"),
                    reps = if (adjusting) -1 else intent.getIntExtra("reps", -1),
                    weightKg = if (adjusting) null else intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() })
                check(checkpoint())
                if (!workoutControls.enqueue(request)) {
                    editStatus = "BUSY"; broadcastState(force = true); return
                }
                editStatus = ""
                if (adjusting) { mySeq = request.seq; restSeconds = seconds; restEndAtMs = end; if (paused) pausedRestRemainingMs = remaining }
                if (editingCurrent) weightKg = request.weightKg
                check(checkpoint())
                replayWorkoutControls()
            }
        }
        broadcastState(force = true)
    }

    // Recreate only the unfinished detector windows; keep reps, frozen profiles and receipts.
    private fun resetCountingWindow() {
        setEndGate.reset()
        forearmRotationDetector = forearmRotationDetector?.let { ForearmRotationRepDetector(trackingProfile).also { it.beginCounting() } }
        profiledMotionDetector = profiledMotionDetector?.let { ProfiledMotionRepDetector(trackingProfile).also { it.beginCounting() } }
        frozenActive?.let { activeDetector = dev.kyungmin.kcoach.core.ReviewedPatternDetector(exerciseId, it.runtime.compatibility, it.runtime) }
        frozenShadow?.let { shadowDetector = dev.kyungmin.kcoach.core.ReviewedPatternDetector(exerciseId, it.runtime.compatibility, it.runtime) }
        startPoseGate?.reset()
    }

    private fun replayManualSets() {
        handler.removeCallbacks(manualRetry)
        val pending = manualOutbox.pending()
        pending.forEach { command -> sendToPhone(WearSync.PATH_MANUAL_SET,
            WireCodec.encode(command.toEnvelope(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())), coalescible = false) }
        if (!serviceDestroyed && pending.isNotEmpty()) handler.postDelayed(manualRetry, 3_000L)
    }

    private fun replayWorkoutControls() {
        handler.removeCallbacks(workoutControlRetry)
        val pending = workoutControls.pending()
        pending.forEach { sendToPhone(WearSync.PATH_WORKOUT_CONTROL, it.encode(), coalescible = false) }
        if (!serviceDestroyed && pending.isNotEmpty()) handler.postDelayed(workoutControlRetry, 3_000L)
    }

    private fun acknowledgeCompletion(payload: String) {
        val ack = WearSync.parse(payload)
        val pending = pendingCompletion?.let(WearSync::parse) ?: return
        if (ack.size !in setOf(5, 7) || ack[0] != "1" || pending.size < 17 ||
            ack[1] != pending[1] || ack[2] != pending[2] || ack[3] != pending[4] || ack[4] != pending[16]) return
        if (pending.getOrNull(17).orEmpty().isNotBlank() && (ack.getOrNull(5) != pending[17] || ack.getOrNull(6) != pending.getOrNull(18))) return
        pendingCompletion = null
        if (!persistRuntime()) {
            pendingCompletion = pending.joinToString("|")
            return
        }
        handler.removeCallbacks(completionRetry)
        sendToPhone(WearSync.PATH_RECONNECT, WearSync.watchUiReady(sessionId), coalescible = false)
        if (sessionId.isBlank() && capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().isEmpty()) stopRuntimeService()
    }

    private fun closeSessionId(id: String) {
        if (id.isBlank()) return
        val closed = (runtimePrefs.getString("closedSessions", "").orEmpty().lines().filter { it.isNotBlank() } + id).distinct().takeLast(32)
        check(runtimePrefs.edit().putString("closedSessions", closed.joinToString("\n")).commit()) { "Failed to close watch session" }
    }

    private fun restartMode(): Int = if (sessionId.isNotBlank() || pendingCompletion != null || manualOutbox.pending().isNotEmpty() || workoutControls.pending().isNotEmpty() || capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().isNotEmpty()) START_STICKY else START_NOT_STICKY

    private fun checkpoint(): Boolean = flushCaptureChunk() && persistRuntime()
    private val chunkJournal get() = File(filesDir, "capture-checkpoint.json")

    private fun runtimeState(): JSONObject = JSONObject().put("session", sessionId).put("exerciseId", exerciseId).put("exerciseName", exerciseName)
            .put("setIndex", setIndex).put("setId", setId).put("attemptId", attemptId).put("targetReps", targetReps).put("weightKg", weightKg ?: JSONObject.NULL)
            .put("phoneRev", phoneRev).put("mySeq", mySeq)
            .put("restSeconds", restSeconds).put("restEndAtMs", restEndAtMs).put("pausedRestRemainingMs", pausedRestRemainingMs)
            .put("countingPaused", countingPaused).put("countingResetAfterNs", countingResetAfterNs).put("editStatus", editStatus)
            .put("running", running).put("resting", resting).put("paused", paused).put("reps", reps).put("heartRate", heartRate)
            .put("trackingProfileSnapshot", dev.kyungmin.kcoach.core.TrackingProfileSnapshot.encode(trackingProfile))
            .put("calibrationStage", calibrationStage?.name ?: JSONObject.NULL).put("pendingCompletion", pendingCompletion ?: JSONObject.NULL)
            .put("chunks", JSONArray(captureChunkFiles.map { it.name })).put("captureSampleCount", captureSampleCount)
            .put("finalizingCaptureId", finalizingCaptureId ?: JSONObject.NULL)
            .put("captureRecoveryError", captureRecoveryError ?: JSONObject.NULL).put("lastCaptureTimestampNs", lastCaptureTimestampNs)
            .put("captureAttemptInterrupted", captureAttemptInterrupted).put("captureSegmentId", captureSegmentId)
            .put("sensorClockNeedsCheck", sensorClockNeedsCheck)
            .put("bootCount", captureBootCount ?: currentBootCount() ?: JSONObject.NULL)
            .put("runtimeElapsedMs", android.os.SystemClock.elapsedRealtime())
            .put("patternShadowCommandId", patternShadowCommandId).put("shadowUseConfirmed", shadowUseConfirmed)
            .put("shadowCount", shadowCount).put("shadowInterrupted", shadowInterrupted)
            .put("patternShadowTrace", shadowTraceSnapshot().orEmpty())
            .put("patternBinding", patternBinding).put("activeUseConfirmed", activeUseConfirmed)
            .put("patternWearing", patternWearing)
            .put("patternSelectionMode", patternSelectionMode)
            .put("startWristContext", startWristContext).put("startWristDeviceId", startWristDeviceId)
            .put("startDirectionSnapshot", startDirectionSnapshot).put("startDirectionStatus", startDirectionStatus)
            .put("countingStartNs", countingStartNs ?: JSONObject.NULL)
            .put("tempoSettings", tempoSettings).put("tempoMuted", tempoMuted).put("tempoHint", tempoHint)
            .put("lastTempoAlertNs", lastTempoAlertNs)
            .put("activeInterrupted", activeInterrupted)
            .put("activeSampleCalls", activeSampleCalls)

    private fun persistRuntime(): Boolean = runCatching {
        val state = runtimeState()
        val saved = runtimePrefs.edit().putString("state", state.toString()).putLong("commandRevision", lastCommandRevision).putString("lastAppliedCommand", lastAppliedCommand).commit()
        if (saved && chunkJournal.exists()) check(chunkJournal.delete()) { "Chunk checkpoint journal could not be retired" }
        saved
    }.onFailure { Log.e(TAG, "watch runtime persistence failed", it) }.getOrDefault(false)

    private fun restoreRuntime() {
        lastCommandRevision = runtimePrefs.getLong("commandRevision", 0)
        lastAppliedCommand = runtimePrefs.getString("lastAppliedCommand", "").orEmpty()
        val encoded = runCatching {
            if (chunkJournal.isFile) chunkJournal.readText() else runtimePrefs.getString("state", null)
        }.getOrElse {
            captureRecoveryError = "Chunk checkpoint cannot be read"
            paused = true
            LocalExitDiagnostics.event("recovery-failed", sessionId, currentPhase(), lastCaptureTimestampNs, it)
            return
        } ?: return
        runCatching {
            val state = JSONObject(encoded)
            sessionId = state.getString("session"); exerciseId = state.getString("exerciseId"); exerciseName = state.getString("exerciseName")
            setIndex = state.getInt("setIndex"); setId = state.optString("setId"); attemptId = state.optString("attemptId"); targetReps = state.getInt("targetReps")
            weightKg = if (state.isNull("weightKg")) null else state.getDouble("weightKg")
            phoneRev = state.optLong("phoneRev", 0); mySeq = state.optLong("mySeq", 0)
            restSeconds = state.getInt("restSeconds"); restEndAtMs = state.getLong("restEndAtMs")
            pausedRestRemainingMs = state.getLong("pausedRestRemainingMs")
            running = state.getBoolean("running"); resting = state.getBoolean("resting"); paused = state.getBoolean("paused")
            countingPaused = state.optBoolean("countingPaused") && running && !resting
            countingResetAfterNs = state.optLong("countingResetAfterNs")
            editStatus = state.optString("editStatus")
            reps = state.getInt("reps"); heartRate = 0 // A persisted reading is not a new sensor measurement.
            calibrationStage = state.optString("calibrationStage").let { name -> CalibrationStage.entries.firstOrNull { it.name == name } }
            pendingCompletion = if (state.isNull("pendingCompletion")) null else state.getString("pendingCompletion")
            finalizingCaptureId = state.optString("finalizingCaptureId").takeIf { it.isNotBlank() && it != "null" }
            lastCaptureTimestampNs = state.optLong("lastCaptureTimestampNs")
            LocalExitDiagnostics.event("recovery-attempt", sessionId, "RECOVERING", lastCaptureTimestampNs)
            captureAttemptInterrupted = state.optBoolean("captureAttemptInterrupted", false)
            captureSegmentId = state.optString("captureSegmentId", "")
            captureBootCount = if (state.has("bootCount") && !state.isNull("bootCount")) state.getInt("bootCount") else null
            val currentBoot = currentBootCount()
            val bootChanged = if (captureBootCount != null && currentBoot != null) captureBootCount != currentBoot
                else state.has("runtimeElapsedMs") && android.os.SystemClock.elapsedRealtime() < state.getLong("runtimeElapsedMs")
            sensorClockNeedsCheck = state.optBoolean("sensorClockNeedsCheck", false) || (!bootChanged && (captureBootCount == null || currentBoot == null) && lastCaptureTimestampNs > 0L)
            captureChunkFiles.clear()
            val chunks = state.getJSONArray("chunks")
            for (i in 0 until chunks.length()) {
                val name = chunks.getString(i)
                require(name == File(name).name && name.startsWith(".capture-"))
                captureChunkFiles.add(File(filesDir, name))
            }
            val expectedCount = if (state.has("captureSampleCount")) state.getInt("captureSampleCount") else null
            if (bootChanged) {
                recoverCaptureChunks(expectedCount) // A bad old chunk still belongs to the previous clock domain.
                if (!separateSensorBoot(currentBoot)) return@runCatching
            } else if (!recoverCaptureChunks(expectedCount)) return@runCatching
            captureChunkIndex = (captureChunkFiles.mapNotNull { it.name.removeSuffix(".kcs.part").substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: -1) + 1
            paired = false; lastPhoneHeartbeatAtMs = 0
            val ownsWorkingSet = running || (paused && pausedRestRemainingMs == 0L)
            patternSelectionMode = state.optString("patternSelectionMode")
            patternBinding = state.optString("patternBinding")
            patternWearing = state.optString("patternWearing")
            startWristContext = state.optString("startWristContext")
            startWristDeviceId = state.optString("startWristDeviceId")
            if (startWristContext.isNotEmpty()) {
                val start = dev.kyungmin.kcoach.core.StartWristContextCodec.decode(startWristContext)
                val wearing = dev.kyungmin.kcoach.core.PatternWearingContext.decode(patternWearing)
                require(start.owner == wearing.owner && start.side == wearing.side && start.placement == wearing.placement)
                require(startWristDeviceId == WearClockEpoch.current(this).deviceId)
            }
            startDirectionSnapshot = state.optString("startDirectionSnapshot")
            if (startDirectionSnapshot.isNotEmpty()) {
                val profile = dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.decode(startDirectionSnapshot)
                require(profile.key.deviceId == startWristDeviceId &&
                    profile.key.context == dev.kyungmin.kcoach.core.StartWristContextCodec.decode(startWristContext))
            }
            configureTracking(state.optString("trackingProfileSnapshot").takeIf { it.isNotBlank() && ownsWorkingSet })
            if (!bootChanged && startDirectionSnapshot.isNotEmpty()) {
                val previousStatus = state.optString("startDirectionStatus")
                countingStartNs = if (state.has("countingStartNs") && !state.isNull("countingStartNs")) state.getLong("countingStartNs").also { require(it > 0) } else null
                require(previousStatus in setOf("WAITING", "CONFIRMED", "BYPASSED"))
                startDirectionStatus = previousStatus
                if (previousStatus in setOf("CONFIRMED", "BYPASSED")) {
                    measurementReady = true; measurementArmed = true
                    forearmRotationDetector?.beginCounting(); profiledMotionDetector?.beginCounting()
                }
            }
            tempoSettings = state.optString("tempoSettings")
            val tempo = tempoSettings.takeIf { it.isNotEmpty() }?.let(dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec::decode)
            require(!ownsWorkingSet || tempo == null || tempo.exerciseId == dev.kyungmin.kcoach.core.ExerciseMotionCatalog.normalizeExerciseId(exerciseId))
            tempoMuted = state.optBoolean("tempoMuted"); tempoHint = state.optString("tempoHint")
            lastTempoAlertNs = if (bootChanged) 0 else state.optLong("lastTempoAlertNs")
            tempoPolicy.configure(if (tempoMuted || tempo == null) dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Config() else tempo.policyConfig())
            configureActive()
            activeUseConfirmed = state.optBoolean("activeUseConfirmed")
            activeInterrupted = state.optBoolean("activeInterrupted")
            activeSampleCalls = state.optInt("activeSampleCalls")
            if (activeDetector != null && captureSampleCount > 0)
                readCaptureChunks(captureSampleCount).forEach { chunk -> chunk.records.filter {
                    !countingPaused && it.sample.timestampNs > countingResetAfterNs &&
                        (startDirectionSnapshot.isEmpty() || countingStartNs?.let { start -> it.sample.timestampNs >= start } == true)
                }.forEach { record -> activeDetector!!.add(if (dev.kyungmin.kcoach.core.SensorRecordQuality.hasAccelerationFault(record))
                    record.sample.copy(sensorAccuracy = 0) else record.sample) } }
            patternShadowCommandId = state.optString("patternShadowCommandId")
            shadowCount = state.optInt("shadowCount")
            shadowInterrupted = state.optBoolean("shadowInterrupted") || patternShadowCommandId.isNotEmpty()
            configureShadow()
            state.optString("patternShadowTrace").takeIf { it.isNotEmpty() }?.let { encoded ->
                val trace = dev.kyungmin.kcoach.core.PatternTrialTraceCodec.decode(encoded)
                require(trace.runtime == frozenShadow?.runtime && trace.sessionId == sessionId && trace.setId == setId && trace.attemptId == attemptId)
                shadowSampleCalls = trace.sampleCalls
                trace.processingHistogram.forEachIndexed { index, count -> shadowProcessingHistogram[index] = count }
                shadowEvents.addAll(trace.events)
            }
            shadowUseConfirmed = state.optBoolean("shadowUseConfirmed")
            if (shadowDetector != null && captureSampleCount > 0)
                readCaptureChunks(captureSampleCount).forEach { chunk -> chunk.records.filter { !countingPaused && it.sample.timestampNs > countingResetAfterNs }.forEach { record ->
                    shadowDetector!!.add(if (dev.kyungmin.kcoach.core.SensorRecordQuality.hasAccelerationFault(record))
                        record.sample.copy(sensorAccuracy = 0) else record.sample)
                } }
            if (pendingCompletion != null && captureSampleCount > 0) {
                saveCapture()?.let { sendCaptureToPhone(it); resetCaptureBuffer(deleteChunks = true); persistRuntime() }
            }
            val manual = manualOutbox.latest(sessionId, setId, attemptId)
            if (manual != null && !resting) {
                running = false
                reps = manual.command.performedReps
            }
            if (running && !countingPaused && reps >= targetReps && trackingProfile.mode != TrackingMode.MANUAL_REQUIRED &&
                dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId).stopAtTarget) completeSet() else if (running) registerSensors()
            LocalExitDiagnostics.event("recovery-restored", sessionId, currentPhase(), lastCaptureTimestampNs)
            // Recovery is an interruption. No samples are invented for the process downtime.
            // The phone resynchronizes an unfinished rest when the heartbeat reconnects.
        }.onFailure {
            running = false; resting = false; paused = true
            captureRecoveryError = it.message ?: "Invalid saved capture runtime"
            LocalExitDiagnostics.event("recovery-failed", sessionId, currentPhase(), lastCaptureTimestampNs, it)
            Log.e(TAG, "watch runtime recovery failed; stored state retained", it)
        }
    }

    private fun currentBootCount(): Int? = runCatching {
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }.getOrNull()

    /** Sensor timestamps are boot-relative: retain the old stream rather than joining two clocks. */
    private fun separateSensorBoot(currentBoot: Int?): Boolean {
        val interrupted = captureAttemptInterrupted || captureSampleCount > 0 || reps > 0 || captureChunkFiles.isNotEmpty() || captureRecords.isNotEmpty()
        if (captureSampleCount > 0 || captureChunkFiles.isNotEmpty() || captureRecords.isNotEmpty()) {
            captureRecoveryError = "Sensor time base changed across boot" + captureRecoveryError?.let { ": $it" }.orEmpty()
            if (!quarantineIncompleteCapture()) {
                running = false; resting = false; paused = true
                persistRuntime()
                return false
            }
        } else resetCaptureBuffer(deleteChunks = false)
        captureAttemptInterrupted = interrupted
        captureBootCount = currentBoot
        countingResetAfterNs = 0L
        sensorClockNeedsCheck = false
        if (startDirectionSnapshot.isNotEmpty()) {
            startDirectionStatus = "WAITING"; countingStartNs = null
            measurementReady = false; measurementArmed = true
            startPoseGate = StartPoseStabilityGate(dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.decode(startDirectionSnapshot).gateConfig)
        }
        persistRuntime()
        return true
    }

    /** Every referenced chunk must exist and validate before the set can be treated as complete. */
    private fun readCaptureChunks(expectedCount: Int? = null): List<dev.kyungmin.kcoach.core.SensorCaptureDocument> {
        val documents = captureChunkFiles.map { file ->
            require(file.canonicalFile.parentFile == filesDir.canonicalFile && file.name.startsWith(".capture-")) { "Invalid capture chunk path" }
            require(file.isFile) { "Missing capture chunk: ${file.name}" }
            require(file.length() in 20L..(20L + MAX_CAPTURE_SAMPLES.toLong() * SensorCaptureFormat.recordBytes())) { "Invalid capture chunk size: ${file.name}" }
            java.io.DataInputStream(file.inputStream()).use { header ->
                header.skipBytes(4)
                val version = header.readInt()
                val count = header.readInt()
                require(count in 0..MAX_CAPTURE_SAMPLES && file.length() == 20L + count.toLong() * SensorCaptureFormat.recordBytes(version)) { "Invalid capture chunk header: ${file.name}" }
            }
            SensorCaptureFormat.read(file.inputStream())
        }
        val count = documents.sumOf { it.sampleCount }
        require(count <= MAX_CAPTURE_SAMPLES && (expectedCount == null || count == expectedCount)) { "Saved capture sample count mismatch" }
        documents.zipWithNext().forEach { (a, b) ->
            require(a.endMonotonicNs == null || b.startMonotonicNs == null || a.endMonotonicNs!! < b.startMonotonicNs!!) { "Capture chunks overlap" }
        }
        return documents
    }

    private fun recoverCaptureChunks(expectedCount: Int?): Boolean {
        if (captureRecords.isNotEmpty() && !flushCaptureChunk()) return false
        val recovered = runCatching { readCaptureChunks(expectedCount) }
        if (recovered.isSuccess) {
            captureSampleCount = recovered.getOrThrow().sumOf { it.sampleCount }
            if (captureAttemptInterrupted) {
                captureRecoveryError = "Sensor attempt was interrupted; original retained"
                return false
            }
            captureRecoveryError = null
            captureChunkIndex = (captureChunkFiles.mapNotNull { it.name.removeSuffix(".kcs.part").substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: -1) + 1
            return true
        }
        // A crash after finalization can leave an old runtime pointing at chunks already replaced by
        // a durable complete outbox capture. Verify that exact record before declaring anything lost.
        if (recoverFinalizedCapture(expectedCount)) {
            resetCaptureBuffer(deleteChunks = false)
            running = false; resting = false; paused = true
            persistRuntime()
            return true
        }
        captureSampleCount = expectedCount?.coerceIn(0, MAX_CAPTURE_SAMPLES) ?: captureSampleCount
        captureRecoveryError = recovered.exceptionOrNull()?.message ?: "Capture recovery failed"
        running = false; resting = false; paused = true
        stopRestTicker()
        sensorManager.unregisterListener(this)
        persistRuntime()
        Log.e(TAG, "capture recovery blocked; every original reference retained", recovered.exceptionOrNull())
        return false
    }

    private fun recoverFinalizedCapture(expectedCount: Int?): Boolean =
        capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().any { id ->
            runCatching {
                require(id.matches(Regex("[A-Za-z0-9._-]{1,160}")))
                val raw = File(filesDir, "$id.kcs")
                require(raw.isFile && raw.length() <= dev.kyungmin.kcoach.core.CaptureValidator.MAX_CAPTURE_BYTES)
                val manifest = WireCodec.decode(File(filesDir, "$id.manifest").readText())
                require(manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.CAPTURE_ID] == id && manifest.sessionId == sessionId)
                require(manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.EXERCISE_ID] == exerciseId)
                if (setId.isNotBlank()) require(manifest.setId == setId && manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.ATTEMPT_ID] == attemptId)
                else require(manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.ATTEMPT_ID].isNullOrBlank() && manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.SET_INDEX]?.toIntOrNull() == setIndex)
                val validated = dev.kyungmin.kcoach.core.CaptureValidator.validate(manifest, raw.readBytes())
                require(expectedCount == null || validated.document.sampleCount == expectedCount)
                require(lastCaptureTimestampNs == 0L || validated.document.endMonotonicNs == lastCaptureTimestampNs)
                true
            }.getOrDefault(false)
        }

    /** Retain the incomplete original and its binding before accepting an explicit replacement/end. */
    private fun quarantineIncompleteCapture(): Boolean = runCatching {
        check(flushCaptureChunk()) { "Incomplete original could not be flushed" }
        val record = JSONObject().put("state", "INCOMPLETE").put("session", sessionId).put("exerciseId", exerciseId)
            .put("setId", setId).put("attemptId", attemptId).put("setIndex", setIndex).put("expectedSamples", captureSampleCount)
            .put("reason", captureRecoveryError).put("chunks", JSONArray(captureChunkFiles.map { it.name }))
            .put("runtime", runtimePrefs.getString("state", null)).put("savedAtMs", System.currentTimeMillis())
        atomicWrite(File(filesDir, "capture-incomplete-${UUID.randomUUID()}.json"), record.toString().toByteArray(Charsets.UTF_8))
        resetCaptureBuffer(deleteChunks = false)
        writeStorageReport()
        true
    }.onFailure { Log.e(TAG, "incomplete capture quarantine failed; runtime retained", it) }.getOrDefault(false)

    private fun markPhoneAlive() {
        val reconnected = !paired
        lastPhoneHeartbeatAtMs = System.currentTimeMillis()
        if (!paired) paired = true
        broadcastState(force = true)
        if (reconnected) {
            retryDataCleanup()
            replayCompletion()
            replayManualSets()
            retryPendingCaptures()
            sendToPhone(WearSync.PATH_RECONNECT, WearSync.watchUiReady(sessionId), coalescible = false)
        }
    }

    private fun startPhoneWatchdog() {
        phoneWatchdog?.let(handler::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                val last = lastPhoneHeartbeatAtMs
                if (last > 0L && System.currentTimeMillis() - last > PHONE_HEARTBEAT_TIMEOUT_MS) {
                    try { resetForPhoneDisconnect() }
                    catch (error: Exception) { runtimeFailure("phone-watchdog", error) }
                }
                handler.postDelayed(this, PHONE_WATCHDOG_INTERVAL_MS)
            }
        }
        phoneWatchdog = tick
        handler.post(tick)
    }

    private fun resetForPhoneDisconnect() {
        if (!paired && !running && !resting && !paused) return
        paired = false
        lastPhoneHeartbeatAtMs = 0L
        check(checkpoint()) { "Disconnect checkpoint failed" }
        broadcastState(force = true)
    }

    private fun sameSet(intent: Intent): Boolean {
        val incomingId = intent.getStringExtra("setId").orEmpty()
        val incomingAttempt = intent.getStringExtra("attemptId").orEmpty()
        return if (incomingId.isNotBlank() || incomingAttempt.isNotBlank()) incomingId == setId && incomingAttempt == attemptId
        else setId.isBlank() && attemptId.isBlank() && intent.getIntExtra("setIndex", 0) == setIndex
    }

    private fun startSession(intent: Intent) {
        markPhoneAlive()
        val incomingSessionId = intent.getStringExtra("session").orEmpty().ifBlank { sessionId }
        val incomingExerciseId = intent.getStringExtra("exerciseId").orEmpty()
        val incomingSetIndex = intent.getIntExtra("setIndex", 0)
        if (
            running && incomingSessionId == sessionId && incomingExerciseId == exerciseId &&
            sameSet(intent)
        ) {
            setIndex = incomingSetIndex
            targetReps = intent.getIntExtra("targetReps", targetReps).coerceAtLeast(0)
            weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
            restSeconds = intent.getIntExtra("restSeconds", restSeconds).coerceAtLeast(0)
            Log.i(TAG, "ignored duplicate active-set command session=$sessionId exercise=$exerciseId set=$setIndex")
            sendLiveState(currentWorkingPhase(), true, force = true)
            broadcastState(force = true)
            return
        }
        if (captureSampleCount > 0 && (incomingSessionId != sessionId || incomingExerciseId != exerciseId || !sameSet(intent))) {
            val oldCapture = saveCapture()
            check(captureSampleCount == 0 || oldCapture != null) { "Previous capture could not be saved" }
            oldCapture?.let(::sendCaptureToPhone)
            resetCaptureBuffer(deleteChunks = true)
        }
        stopRestTicker()
        cancelMeasurementPreparation()
        resting = false
        paused = false
        pausedRestRemainingMs = 0L
        if (incomingSessionId != sessionId) heartRate = 0
        if (sessionId != incomingSessionId) { tempoPolicy = dev.kyungmin.kcoach.core.TempoFeedbackPolicy(); lastTempoAlertNs = 0L }
        WatchHomeStore(this).leaveCompletion()
        sessionId = incomingSessionId
        exerciseId = incomingExerciseId
        exerciseName = intent.getStringExtra("exerciseName").orEmpty().ifBlank { exerciseId.replace('_', ' ') }
        setIndex = incomingSetIndex
        setId = intent.getStringExtra("setId").orEmpty()
        attemptId = intent.getStringExtra("attemptId").orEmpty()
        targetReps = intent.getIntExtra("targetReps", 10).coerceAtLeast(0)
        weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
        restSeconds = intent.getIntExtra("restSeconds", 60).coerceAtLeast(0)
        running = true
        reps = 0
        lastAccel = FloatArray(3); lastGyro = FloatArray(3)
        lastAccelerometerNs = null; lastAccelerometerAccuracy = null; lastHeartRateNs = null
        calibrationStage = if (dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId) == dev.kyungmin.kcoach.core.CapturePurpose.LEGACY_CALIBRATION) CalibrationStage.BASELINE else null
        calibrationCameraState.begin(sessionId, setId, attemptId, exerciseId, exerciseName)
        preparationHoldMs = 0L
        patternSelectionMode = intent.getStringExtra("patternSelectionMode").orEmpty()
        // Any unresolved previous chunks stay on disk instead of being erased
        // when a replacement set starts.
        countingPaused = false; countingResetAfterNs = 0L; editStatus = ""
        resetCaptureBuffer(deleteChunks = false)
        patternWearing = intent.getStringExtra("patternWearing").orEmpty()
        startWristContext = intent.getStringExtra("startWristContext").orEmpty()
        startWristDeviceId = if (startWristContext.isEmpty()) "" else WearClockEpoch.current(this).deviceId
        startDirectionSnapshot = intent.getStringExtra("startDirectionSnapshot").orEmpty()
        countingStartNs = null
        tempoSettings = intent.getStringExtra("tempoSettings").orEmpty()
        tempoMuted = false; tempoHint = ""
        tempoPolicy.configure(tempoSettings.takeIf { it.isNotEmpty() }?.let {
            dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.decode(it).policyConfig()
        } ?: dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Config())
        configureTracking()
        patternBinding = incomingPatternBinding(intent)
        configureActive()
        patternShadowCommandId = intent.getStringExtra("patternShadowCommandId").orEmpty()
        shadowCount = 0; shadowInterrupted = false
        shadowSampleCalls = 0L; shadowProcessingHistogram.fill(0); shadowEvents.clear()
        configureShadow()
        sensorEventCount = 0
        uiBroadcastCount = 0
        liveStateCount = 0
        maxObservedOutboundQueue = 0
        lastSensorAccuracy = -1
        if (sessionId.isBlank()) sessionId = "watch-${System.currentTimeMillis()}"
        registerSensors()
        sendLiveState(currentWorkingPhase(), true, force = true)
        if (calibrationStage == CalibrationStage.BASELINE) {
            Log.i(CALIBRATION_TAG, "stage=BASELINE session=$sessionId exerciseId=$exerciseId")
        } else if (!measurementReady) {
            sendLiveState(currentWorkingPhase(), true, force = true)
        }
        broadcastState(force = true)
    }

    private fun configureShadow() {
        frozenShadow = null; shadowDetector = null; shadowUseConfirmed = false
        if (patternShadowCommandId.isBlank()) return
        val selected = patternProfiles.beginRequestedShadow(patternShadowCommandId, sessionId, setId, attemptId,
            WearClockEpoch.current(this).id).getOrThrow()
        require(selected.runtime.compatibility.recipe == dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.resolve(exerciseId).recipe)
        shadowDetector = dev.kyungmin.kcoach.core.ReviewedPatternDetector(exerciseId, selected.runtime.compatibility, selected.runtime)
        frozenShadow = selected
    }

    private fun incomingPatternBinding(intent: Intent): String {
        val explicit = intent.getStringExtra("patternBinding").orEmpty()
        val context = intent.getStringExtra("patternWearing").orEmpty()
        if (context.isEmpty()) return explicit
        val wearing = dev.kyungmin.kcoach.core.PatternWearingContext.decode(context)
        val recipe = dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.resolve(intent.getStringExtra("exerciseId").orEmpty()).recipe
        if (recipe in setOf(dev.kyungmin.kcoach.core.MeasurementRecipe.MANUAL, dev.kyungmin.kcoach.core.MeasurementRecipe.SQUAT)) {
            require(explicit.isEmpty())
            return ""
        }
        val actual = dev.kyungmin.kcoach.core.PatternCompatibilityCodec.encode(wearing.compatibility(recipe, WearClockEpoch.current(this).deviceId))
        require(explicit.isEmpty() || explicit == actual) { "Explicit binding disagrees with actual device and wearing" }
        return actual
    }

    private fun configureActive() {
        disableUseConfirmed = false
        activeSampleCalls = 0
        frozenActive = null; activeDetector = null; activeUseConfirmed = false; activeInterrupted = false
        if (patternBinding.isBlank() || calibrationStage != null || trackingProfile.mode == TrackingMode.MANUAL_REQUIRED ||
            (!running && !(paused && pausedRestRemainingMs == 0L))) return
        val key = dev.kyungmin.kcoach.core.PatternCompatibilityCodec.decode(patternBinding)
        require(key.recipe == dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.resolve(exerciseId).recipe)
        val selected = patternProfiles.beginSet(key, sessionId, setId, attemptId, WearClockEpoch.current(this).id, active = true)
            .getOrThrow() ?: return
        activeDetector = dev.kyungmin.kcoach.core.ReviewedPatternDetector(exerciseId, key, selected.runtime)
        frozenActive = selected
        // No partial-cycle evidence is available in this detector. Preserve any cycle
        // accepted by the frozen profile, plus its 100ms search/120ms emission delay.
        learnedRepWaitNs = maxOf(3_000_000_000L, selected.runtime.maximumDurationNs + 300_000_000L)
        // A complete learned return supplies its own start/turn/end evidence. Do not run the legacy counter in parallel as primary.
        forearmRotationDetector = null; profiledMotionDetector = null
        // Repetition-pattern selection and wrist preparation are independent. Keep the frozen
        // direction gate (or its restored ready state) when switching the primary counter.
        if (startDirectionSnapshot.isEmpty() && !requiresStillStart()) {
            startPoseGate = null; measurementReady = true; measurementArmed = true
        }
    }

    private fun observeActive(sample: SensorSample): dev.kyungmin.kcoach.core.ReviewedPatternDetection? {
        val detector = activeDetector ?: return null
        return runCatching {
            if (activeSampleCalls < 30_000) activeSampleCalls++ else activeInterrupted = true
            val detection = detector.add(sample)
            if (dev.kyungmin.kcoach.core.SignalAxis.entries.any { !it.value(sample).isFinite() } || sample.sensorAccuracy == 0) {
                activeInterrupted = true
                return@runCatching null
            }
            if (!activeUseConfirmed) {
                val ack = patternProfiles.confirmUsed(requireNotNull(frozenActive), sample.timestampNs,
                    android.os.SystemClock.elapsedRealtimeNanos()).getOrThrow()
                activeUseConfirmed = true
                sendToPhone(WearSync.PATH_PATTERN_PROFILE_ACK, ack.encode(), coalescible = false)
                persistRuntime()
            }
            detection
        }.onFailure { activeInterrupted = true; Log.w(TAG, "Active profile processing failed; snapshot retained", it) }.getOrNull()
    }

    private fun observeShadow(sample: SensorSample) {
        val detector = shadowDetector ?: return
        if (dev.kyungmin.kcoach.core.SignalAxis.entries.any { !it.value(sample).isFinite() } || sample.sensorAccuracy == 0) {
            detector.add(sample) // Reset its unfinished window; do not bridge a known invalid interval.
            shadowInterrupted = true; return
        }
        runCatching {
            val startNs = android.os.SystemClock.elapsedRealtimeNanos()
            val detection = detector.add(sample)
            val processedNs = android.os.SystemClock.elapsedRealtimeNanos()
            require(processedNs >= sample.timestampNs && processedNs >= startNs)
            if (shadowSampleCalls < 30_000) {
                shadowSampleCalls++
                val duration = processedNs - startNs
                val bucket = if (duration == 0L) 0 else 64 - java.lang.Long.numberOfLeadingZeros(duration)
                shadowProcessingHistogram[bucket.coerceAtMost(63)]++
            } else shadowInterrupted = true
            if (detection != null) {
                shadowCount++
                if (shadowEvents.size < 600) shadowEvents += dev.kyungmin.kcoach.core.PatternTrialEvent(
                    detection.cycle, detection.emittedAtNs, processedNs, detection.distance)
                else shadowInterrupted = true
            }
            if (!shadowUseConfirmed) {
                val ack = patternProfiles.confirmUsed(requireNotNull(frozenShadow), sample.timestampNs,
                    android.os.SystemClock.elapsedRealtimeNanos()).getOrThrow()
                shadowUseConfirmed = true
                sendToPhone(WearSync.PATH_PATTERN_PROFILE_ACK, ack.encode(), coalescible = false)
                persistRuntime()
            }
        }.onFailure { shadowInterrupted = true; Log.w(TAG, "Comparison profile use retained without confirmation", it) }
    }

    private fun shadowTraceSnapshot(): String? {
        val frozen = frozenShadow ?: return null
        val epoch = WearClockEpoch.current(this)
        return dev.kyungmin.kcoach.core.PatternTrialTraceCodec.encode(dev.kyungmin.kcoach.core.PatternTrialTrace(
            frozen.runtime, frozen.sessionId, frozen.setId, frozen.attemptId, frozen.watchClockId,
            epoch.verified && epoch.id == frozen.watchClockId,
            if (isEmulator || Build.FINGERPRINT.contains("robolectric", true)) dev.kyungmin.kcoach.core.PatternTrialSource.EMULATED
            else dev.kyungmin.kcoach.core.PatternTrialSource.WATCH_SENSOR_EVENT,
            shadowSampleCalls, shadowProcessingHistogram.toList(), shadowEvents.toList(), shadowInterrupted))
    }

    private fun configureTracking(savedSnapshot: String? = null) {
        setEndGate.reset()
        learnedRepWaitNs = 3_000_000_000L
        handler.removeCallbacks(setEndTicker)
        handler.post(setEndTicker)
        val genericProfile = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        // Legacy self-labeled threshold profiles have no reviewed non-regression evidence.
        // A restored in-progress attempt keeps its snapshot; a new attempt starts generic.
        trackingProfile = if (savedSnapshot != null) {
            dev.kyungmin.kcoach.core.TrackingProfileSnapshot.decode(savedSnapshot).also {
                require(it.exerciseId == genericProfile.exerciseId) { "recorded profile belongs to another exercise" }
            }
        } else genericProfile
        forearmRotationDetector = if (trackingProfile.signal == TrackingSignal.FOREARM_ROTATION) {
            ForearmRotationRepDetector(trackingProfile)
        } else {
            null
        }
        profiledMotionDetector = if (
            trackingProfile.mode != TrackingMode.MANUAL_REQUIRED &&
            trackingProfile.signal != TrackingSignal.FOREARM_ROTATION
        ) {
            ProfiledMotionRepDetector(trackingProfile)
        } else {
            null
        }
        // No exercise name alone has a hardware-reviewed wrist target, so this is not a posture verdict:
        // a workout set only waits for the wrist to hold still for a few seconds after START or the end
        // of a rest, then buzzes and starts counting. The legacy calibration baseline is a separate task.
        val needsCalibrationBaseline = (calibrationStage == CalibrationStage.BASELINE || sessionId.startsWith("calibration-paired-")) &&
            trackingProfile.mode != TrackingMode.MANUAL_REQUIRED
        val direction = startDirectionSnapshot.takeIf { it.isNotEmpty() }?.let(dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec::decode)
        startDirectionStatus = if (direction != null) "WAITING" else dev.kyungmin.kcoach.core.StartWristOrientationPolicy.uncheckedStartStatus(exerciseId)
        val needsStillStart = direction == null && requiresStillStart()
        measurementReady = !needsCalibrationBaseline && direction == null && !needsStillStart
        measurementArmed = measurementReady || needsStillStart
        startPoseGate = if (measurementReady) null else
            StartPoseStabilityGate(direction?.gateConfig ?: StartPoseGateConfig.forProfile(trackingProfile).copy(requireSetupMotion = false,
                stableDurationMs = if (sessionId.startsWith("calibration-paired-") || needsStillStart) StartPoseGateConfig.WORKOUT_HOLD_MS else 2_000L))
        if (measurementReady && trackingProfile.mode != TrackingMode.MANUAL_REQUIRED) {
            forearmRotationDetector?.beginCounting()
            profiledMotionDetector?.beginCounting()
        }
    }

    /** A workout set that is counted by sensors waits for the wrist to hold still before it starts measuring. */
    private fun requiresStillStart() = dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId) == dev.kyungmin.kcoach.core.CapturePurpose.WORKOUT &&
        trackingProfile.mode != TrackingMode.MANUAL_REQUIRED

    private fun startRest(intent: Intent) {
        countingPaused = false
        if (captureSampleCount > 0 && (!sameSet(intent) || intent.getStringExtra("exerciseId").orEmpty().let { it.isNotBlank() && it != exerciseId })) {
            val oldCapture = saveCapture()
            check(captureSampleCount == 0 || oldCapture != null) { "Completed capture must be saved before rest changes its identity" }
            oldCapture?.let(::sendCaptureToPhone); resetCaptureBuffer(deleteChunks = true)
        }
        markPhoneAlive()
        intent.getStringExtra("session")?.takeIf { it.isNotBlank() }?.let {
            if (it != sessionId) heartRate = 0
            sessionId = it
        }
        running = false
        resting = true
        paused = false
        pausedRestRemainingMs = 0L
        unregisterMotionSensors()
        cancelMeasurementPreparation()
        restSeconds = intent.getIntExtra("restSeconds", 60).coerceAtLeast(0)
        restEndAtMs = intent.getLongExtra("restEndAtMs", 0L)
            .takeIf { it > 0L } ?: (System.currentTimeMillis() + restSeconds * 1_000L)
        intent.getStringExtra("exerciseId")?.takeIf { it.isNotBlank() }?.let { exerciseId = it }
        intent.getStringExtra("exerciseName")?.takeIf { it.isNotBlank() && it != "휴식" }
            ?.let { exerciseName = it }
        setIndex = intent.getIntExtra("setIndex", setIndex).coerceAtLeast(0)
        setId = intent.getStringExtra("setId").orEmpty()
        attemptId = intent.getStringExtra("attemptId").orEmpty()
        targetReps = intent.getIntExtra("targetReps", targetReps).coerceAtLeast(0)
        weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
        heartRateSensor?.let { sensor ->
            if (hasBodySensorPermission()) registerSensor(sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sendLiveState("RESTING", false, force = true)
        stopRestTicker()
        val tick = object : Runnable {
            override fun run() {
                if (!resting) return
                try {
                    val left = ((restEndAtMs - System.currentTimeMillis() + 999) / 1_000).coerceAtLeast(0)
                    sendLiveState("RESTING", false, force = true)
                    broadcastState()
                    if (left <= 0) {
                        resting = false
                        restEndAtMs = 0L
                        check(persistRuntime()) { "Rest completion checkpoint failed" }
                        sendLiveState("REST_COMPLETE", false, force = true)
                        broadcastState(force = true)
                    } else {
                        handler.postDelayed(this, 1_000L)
                    }
                } catch (error: Exception) { runtimeFailure("rest-timer", error) }
            }
        }
        restTicker = tick
        handler.post(tick)
        broadcastState(force = true)
    }

    private fun pauseSession() {
        countingPaused = false
        setEndGate.reset()
        tempoPolicy.pause()
        markPhoneAlive()
        if (!running && !resting) return
        if (resting) pausedRestRemainingMs = (restEndAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        running = false
        resting = false
        paused = true
        stopRestTicker()
        cancelMeasurementPreparation()
        sensorManager.unregisterListener(this)
        heartRate = 0
        sendLiveState("PAUSED", false, force = true)
        broadcastState(force = true)
    }

    private fun resumeSession(restDeadline: Long? = null) {
        markPhoneAlive()
        if (!paused) return
        paused = false
        if (pausedRestRemainingMs > 0L) {
            restEndAtMs = restDeadline ?: (System.currentTimeMillis() + pausedRestRemainingMs)
            pausedRestRemainingMs = 0L
            val synthetic = Intent(this, WearWorkoutService::class.java).putExtra(EXTRA_COMMAND, "REST")
                .putExtra("session", sessionId).putExtra("exerciseId", exerciseId).putExtra("exerciseName", exerciseName)
                .putExtra("setIndex", setIndex).putExtra("setId", setId).putExtra("attemptId", attemptId)
                .putExtra("targetReps", targetReps).putExtra("weightKg", weightKg ?: Double.NaN)
                .putExtra("restSeconds", restSeconds)
                .putExtra("restEndAtMs", restEndAtMs)
            startRest(synthetic)
        } else {
            running = true
            registerSensors()
            if (measurementReady || trackingProfile.mode == TrackingMode.MANUAL_REQUIRED) {
                sendLiveState(currentWorkingPhase(), true, force = true)
            } else {
                startPoseGate = StartPoseStabilityGate(startDirectionSnapshot.takeIf { it.isNotEmpty() }
                    ?.let(dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec::decode)?.gateConfig
                    ?: StartPoseGateConfig.forProfile(trackingProfile).copy(requireSetupMotion = false,
                        stableDurationMs = if (requiresStillStart()) StartPoseGateConfig.WORKOUT_HOLD_MS else StartPoseGateConfig().stableDurationMs))
                measurementArmed = true
                sendLiveState(currentWorkingPhase(), true, force = true)
            }
            broadcastState(force = true)
        }
    }

    private fun finishSession(phase: String, startId: Int = 0) {
        markPhoneAlive()
        if (sessionId.isBlank()) {
            resetForPhoneDisconnect()
            return
        }
        Log.i(TAG, "finishSession begin phase=$phase session=$sessionId set=$setIndex samples=$captureSampleCount cacheDir=${filesDir.absolutePath}")
        if (measurementReady) flushForearmRotation()
        countingPaused = false
        running = false
        resting = false
        paused = false
        stopRestTicker()
        cancelMeasurementPreparation()
        sensorManager.unregisterListener(this)
        val savedCapture = saveCapture()
        check(captureSampleCount == 0 || savedCapture != null) { "Session end deferred; original capture retained" }
        savedCapture?.let(::sendCaptureToPhone)
        calibrationCameraState.finish(sessionId, reps)
        sendLiveState(phase, false, force = true)
        sendToPhone(
            WearSync.PATH_COMPLETE,
            WearSync.completion(sessionId, exerciseId, setIndex, reps, heartRate, phase, captureSampleCount),
        )
        if (phase == "COMPLETE" && !sessionId.startsWith("calibration-")) WatchHomeStore(this).complete(sessionId)
        else WatchHomeStore(this).leaveCompletion()
        restEndAtMs = 0L
        closeSessionId(sessionId)
        resetSessionFields()
        check(persistRuntime()) { "Session closure checkpoint failed" }
        broadcastState(force = true)
        val pendingCaptures = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty()
        if (pendingCaptures.isEmpty() && pendingCompletion == null) stopRuntimeService(startId)
        Log.i(TAG, "finishSession end phase=$phase")
    }

    private fun registerSensors() {
        if (hasBodySensorPermission()) {
            accelerometer?.let { registerSensor(it, SensorManager.SENSOR_DELAY_GAME) }
            gyroscope?.let { registerSensor(it, SensorManager.SENSOR_DELAY_GAME) }
            heartRateSensor?.let { registerSensor(it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    private fun registerSensor(sensor: Sensor, delay: Int) {
        try { sensorManager.registerListener(this, sensor, delay) }
        catch (error: RuntimeException) { runtimeFailure("sensor-register", error); throw error }
    }

    private fun unregisterMotionSensors() {
        accelerometer?.let { sensorManager.unregisterListener(this, it) }
        gyroscope?.let { sensorManager.unregisterListener(this, it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        try { processSensorEvent(event) }
        catch (error: Exception) { runtimeFailure("sensor", error) }
    }

    private fun runtimeFailure(boundary: String, error: Throwable) {
        if (running && captureSampleCount > 0) captureAttemptInterrupted = true
        running = false; resting = false; paused = true
        captureRecoveryError = "Runtime interrupted at $boundary"
        handler.removeCallbacks(setEndTicker)
        stopRestTicker()
        cancelMeasurementPreparation()
        runCatching { sensorManager.unregisterListener(this) }
        // Keep the original clocks and identifiers; do not emit a completion or an applied ACK.
        runCatching { checkpoint() }
        LocalExitDiagnostics.event("runtime-interrupted-$boundary", sessionId, currentPhase(), lastCaptureTimestampNs, error)
        broadcastState(force = true)
    }

    private fun processSensorEvent(event: SensorEvent) {
        if (captureRecoveryError != null) return
        sensorEventCount += 1
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                lastAccelerometerNs = event.timestamp
                lastAccelerometerAccuracy = event.accuracy.takeIf { it >= 0 }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (sensorClockNeedsCheck && running) {
                    if (event.timestamp < lastCaptureTimestampNs && !separateSensorBoot(currentBootCount())) return
                    sensorClockNeedsCheck = false
                }
                lastGyro = event.values.copyOf()
                val accelerationFault = dev.kyungmin.kcoach.core.SensorRecordQuality.hasAccelerationFault(
                    lastAccelerometerAccuracy, lastAccelerometerNs, event.timestamp)
                if (running && !countingPaused && event.timestamp > countingResetAfterNs) observeShadow(SensorSample(event.timestamp, lastAccel[0], lastAccel[1], lastAccel[2],
                    lastGyro[0], lastGyro[1], lastGyro[2], sensorAccuracy = if (accelerationFault) 0 else event.accuracy))
                if (running && (dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId).preservePreparation || startDirectionSnapshot.isNotEmpty())) {
                    captureSensorSample(SensorSample(event.timestamp, lastAccel[0], lastAccel[1], lastAccel[2],
                        lastGyro[0], lastGyro[1], lastGyro[2], sensorAccuracy = event.accuracy))
                }
                if (running && (countingPaused || event.timestamp <= countingResetAfterNs)) {
                    captureSensorSample(SensorSample(event.timestamp, lastAccel[0], lastAccel[1], lastAccel[2],
                        lastGyro[0], lastGyro[1], lastGyro[2], sensorAccuracy = event.accuracy))
                    broadcastState()
                    return
                }
                if (running && trackingProfile.mode != TrackingMode.MANUAL_REQUIRED) {
                    val sensorSample = SensorSample(
                        timestampNs = event.timestamp,
                        accelerationX = lastAccel[0],
                        accelerationY = lastAccel[1],
                        accelerationZ = lastAccel[2],
                        gyroscopeX = lastGyro[0],
                        gyroscopeY = lastGyro[1],
                        gyroscopeZ = lastGyro[2],
                        sensorAccuracy = event.accuracy,
                    )
                    if (!measurementReady) {
                        val gate = startPoseGate?.observe(sensorSample, lastAccelerometerNs, lastAccelerometerAccuracy)
                        measurementArmed = gate?.armed == true
                        preparationHoldMs = gate?.stableDurationMs ?: 0L
                        if (gate?.ready == true) {
                            if (startDirectionSnapshot.isNotEmpty()) {
                                startDirectionStatus = "CONFIRMED"
                            }
                            if (calibrationStage == CalibrationStage.BASELINE) {
                                calibrationStage = CalibrationStage.CONTROLLED_REPS
                                Log.i(CALIBRATION_TAG, "stage=CONTROLLED_REPS session=$sessionId exerciseId=$exerciseId")
                            }
                            beginMeasurementCounting()
                            if (startDirectionSnapshot.isNotEmpty()) check(checkpoint())
                            sendLiveState(currentWorkingPhase(), true, force = true)
                            broadcastState(force = true)
                        }
                    } else {
                        if (startDirectionSnapshot.isNotEmpty() && countingStartNs == null) {
                            require(startDirectionStatus in setOf("CONFIRMED", "BYPASSED"))
                            countingStartNs = sensorSample.timestampNs
                            check(checkpoint())
                        }
                        // Persist the same sample used by the live detector before it can
                        // complete the set. Callback delivery time must not discard a batch.
                        captureSensorSample(sensorSample)
                        val countingSample = if (accelerationFault) sensorSample.copy(sensorAccuracy = 0) else sensorSample
                        val candidate = forearmRotationDetector?.candidateStartNs ?: profiledMotionDetector?.candidateStartNs
                        if (calibrationStage == null && dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId) == dev.kyungmin.kcoach.core.CapturePurpose.WORKOUT &&
                            setEndGate.shouldEnd(sensorSample.timestampNs, candidate,
                                profiledMotionDetector?.maximumCycleNs ?: (trackingProfile.maximumRepMs * 1_000_000L),
                                setEndWaitNs())) {
                            completeSet()
                            return
                        }
                        val marker = if (activeDetector != null) observeActive(countingSample)
                            else forearmRotationDetector?.add(countingSample) ?: profiledMotionDetector?.add(countingSample)
                        if (activeDetector == null && patternBinding.isNotBlank() && !disableUseConfirmed &&
                            (forearmRotationDetector != null || profiledMotionDetector != null) && countingSample.sensorAccuracy != 0 &&
                            dev.kyungmin.kcoach.core.SignalAxis.entries.all { it.value(sensorSample).isFinite() }) {
                            patternProfiles.confirmDisabled(dev.kyungmin.kcoach.core.PatternCompatibilityCodec.decode(patternBinding),
                                sessionId, setId, attemptId, WearClockEpoch.current(this).id, sensorSample.timestampNs,
                                android.os.SystemClock.elapsedRealtimeNanos()).onSuccess { ack ->
                                if (ack != null) {
                                    disableUseConfirmed = true
                                    sendToPhone(WearSync.PATH_PATTERN_PROFILE_ACK, ack.encode(), coalescible = false)
                                }
                            }.onFailure { Log.w(TAG, "Basic detector receipt failed", it) }
                        }
                        if (marker != null) {
                            val repEndNs = when (marker) {
                                is dev.kyungmin.kcoach.core.ReviewedPatternDetection -> marker.cycle.endNs
                                is dev.kyungmin.kcoach.core.ForearmRotationRepEvent -> marker.endTimestampNs
                                is dev.kyungmin.kcoach.core.ProfiledMotionRepEvent -> marker.endTimestampNs
                                else -> sensorSample.timestampNs
                            }
                            recordDetectedRepAt(repEndNs)
                            if (activeDetector != null && marker is dev.kyungmin.kcoach.core.ReviewedPatternDetection)
                                observeTempo(marker, countingSample)
                            if (!running) return
                        }
                    }
                }
            }
            Sensor.TYPE_HEART_RATE -> {
                val reading = event.values.firstOrNull()
                heartRate = reading?.takeIf { it.isFinite() && it in 20f..240f }?.toInt() ?: 0
                lastHeartRateNs = event.timestamp.takeIf { heartRate > 0 }
                val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
                if (heartRate > 0 && event.accuracy in 1..3 && ageMs in 0..10_000 && sessionId.isNotBlank()) {
                    sendToPhone(WearSync.PATH_HEART_RATE_SAMPLE, listOf("1", sessionId, event.timestamp, heartRate, ageMs, event.accuracy,
                        if (paused) "PAUSED" else if (resting) "RESTING" else "WORKING").joinToString("|"))
                }
            }
        }
        lastSensorAccuracy = event.accuracy
        if (running && measurementReady && event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            captureSensorSample(SensorSample(
                timestampNs = event.timestamp,
                accelerationX = lastAccel[0], accelerationY = lastAccel[1], accelerationZ = lastAccel[2],
                gyroscopeX = lastGyro[0], gyroscopeY = lastGyro[1], gyroscopeZ = lastGyro[2],
                sensorAccuracy = lastSensorAccuracy.takeIf { it >= 0 },
            ))
        }
        if (running) sendLiveState(currentWorkingPhase(), true)
        broadcastState()
    }

    private fun captureSensorSample(sample: SensorSample) {
        if (sample.timestampNs <= lastCaptureTimestampNs) return
        lastCaptureTimestampNs = sample.timestampNs
        appendCaptureRecord(SensorCaptureRecord(sample, heartRate.takeIf { it in 20..240 && lastHeartRateNs != null },
            lastAccelerometerNs, sample.timestampNs, android.os.SystemClock.elapsedRealtimeNanos(), lastAccelerometerAccuracy,
            lastHeartRateNs))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun stopRestTicker() {
        restTicker?.let(handler::removeCallbacks)
        restTicker = null
    }

    private fun completeSet(manual: Boolean = false) {
        if (!running) return
        handler.removeCallbacks(setEndTicker)
        if (countingPaused) resetCountingWindow()
        countingPaused = false
        Log.i(TAG, "completeSet begin manual=$manual session=$sessionId set=$setIndex reps=$reps samples=$captureSampleCount")
        if (manual && calibrationStage == null && measurementReady) flushForearmRotation()
        val rotationDiagnostics = forearmRotationDetector?.diagnostics()
        val wasCalibration = calibrationStage != null || sessionId.startsWith("calibration-")
        running = false
        unregisterMotionSensors()
        cancelMeasurementPreparation()
        if (!wasCalibration) {
            pendingCompletion = WearSync.liveState(sessionId, exerciseId, exerciseName, setIndex, reps, targetReps,
                weightKg, heartRate, "SET_COMPLETE", false, restEndAtMs, captureSampleCount, trackingProfile.id,
                trackingProfile.calibrationProfileVersion != null, UUID.randomUUID().toString(), setId, attemptId)
            if (!checkpoint()) {
                pendingCompletion = null
                throw java.io.IOException("Completion checkpoint failed; raw capture retained")
            }
        }
        val savedCapture = saveCapture()
        check(captureSampleCount == 0 || savedCapture != null) { "Completion capture save failed" }
        savedCapture?.let(::sendCaptureToPhone)
        if (wasCalibration) {
            Log.i(CALIBRATION_TAG, "stage=CALIBRATION_RESULT session=$sessionId exerciseId=$exerciseId reps=$reps")
        }
        if (wasCalibration) sendLiveState("CALIBRATION_RESULT", false, force = true) else replayCompletion()
        if (wasCalibration) vibrateCompleted()
        calibrationStage = null
        measurementReady = false
        measurementArmed = false
        startPoseGate = null
        broadcastState(force = true)
        if (savedCapture != null || captureSampleCount == 0) resetCaptureBuffer(deleteChunks = true)
        persistRuntime()
        Log.i(
            TAG,
            "runtime load sensorEvents=$sensorEventCount uiBroadcasts=$uiBroadcastCount " +
                "liveStates=$liveStateCount maxOutboundQueue=$maxObservedOutboundQueue",
        )
        if (rotationDiagnostics != null) {
            Log.i(TAG, "forearm rotation diagnostics=$rotationDiagnostics")
        }
        Log.i(TAG, "completeSet end manual=$manual session=$sessionId set=$setIndex reps=$reps")
    }

    private fun beginMeasurementCounting() {
        setEndGate.reset()
        // configureActive already chose the frozen profile's wait; preparation must retain it.
        handler.removeCallbacks(setEndTicker)
        handler.post(setEndTicker)
        forearmRotationDetector?.beginCounting()
        profiledMotionDetector?.beginCounting()
        measurementReady = true
        measurementArmed = true
        vibrateReady()
    }

    private fun cancelMeasurementPreparation() {
        startPoseGate?.reset()
    }

    private fun recordDetectedRep() = recordDetectedRepAt(android.os.SystemClock.elapsedRealtimeNanos())

    private fun recordDetectedRepAt(endNs: Long) {
        if (!running || countingPaused || trackingProfile.mode == TrackingMode.MANUAL_REQUIRED) return
        if (!setEndGate.rep(endNs)) return
        val callbackNs = android.os.SystemClock.elapsedRealtimeNanos()
        reps += 1
        // Haptic first: the synchronous checkpoint writes below must not delay the cue.
        // Exactly the target rep gets the triple pulse; reps past the target go back to the short tick.
        if (reps == targetReps) vibrateTargetReached() else vibrateShort()
        val vibratedNs = android.os.SystemClock.elapsedRealtimeNanos()
        checkpoint()
        Log.i(TAG, "rep=$reps profile=${trackingProfile.id} eventNs=$endNs detectLagMs=${(callbackNs - endNs) / 1_000_000} " +
            "vibrateCallMs=${(vibratedNs - callbackNs) / 1_000_000} checkpointMs=${(android.os.SystemClock.elapsedRealtimeNanos() - vibratedNs) / 1_000_000}")
        if (reps >= targetReps && dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId).stopAtTarget) completeSet()
    }

    private fun observeTempo(detection: dev.kyungmin.kcoach.core.ReviewedPatternDetection, sample: SensorSample) {
        if (tempoSettings.isEmpty() || tempoMuted) return
        val settings = dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.decode(tempoSettings)
        val cycle = detection.cycle
        val feedback = tempoPolicy.observe(dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Observation(setId, attemptId, reps, cycle.endNs,
            cycle.durationMs, cycle.concentricMs, cycle.eccentricMs,
            phasesValidated = activeUseConfirmed && cycle.departurePhase != dev.kyungmin.kcoach.core.MusclePhase.UNKNOWN,
            qualitySufficient = activeUseConfirmed && !activeInterrupted && sample.sensorAccuracy in 1..3 && lastAccelerometerAccuracy in 1..3)) ?: return
        if (lastTempoAlertNs > 0 && (feedback.endNs - lastTempoAlertNs) / 1_000_000 < settings.minimumAlertIntervalMs) return
        tempoHint = feedback.deviations.joinToString(" · ") { deviation ->
            val label = when (deviation.metric) {
                dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Metric.REPETITION -> "1회"
                dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Metric.CONCENTRIC -> "힘쓰는 구간"
                dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Metric.ECCENTRIC -> "복귀 구간"
            }
            "$label ${"%.1f".format(java.util.Locale.US, deviation.observedMs / 1000.0)}초 · 목표보다 ${if (deviation.direction == dev.kyungmin.kcoach.core.TempoFeedbackPolicy.Direction.LONGER) "느림" else "빠름"}"
        }
        lastTempoAlertNs = feedback.endNs
        vibrateReady(); check(checkpoint()); broadcastState(force = true)
    }

    private fun currentWorkingPhase(): String = when (calibrationStage) {
        CalibrationStage.BASELINE -> "BASELINE"
        CalibrationStage.CONTROLLED_REPS -> "CONTROLLED_REPS"
        null -> when {
            trackingProfile.mode == TrackingMode.MANUAL_REQUIRED -> "MANUAL_REQUIRED"
            startDirectionSnapshot.isNotEmpty() && !measurementReady -> "START_DIRECTION_WAITING"
            startDirectionSnapshot.isNotEmpty() && startDirectionStatus == "CONFIRMED" -> "WORKING_START_CONFIRMED"
            startDirectionSnapshot.isNotEmpty() && startDirectionStatus == "BYPASSED" -> "WORKING_START_BYPASSED"
            !measurementReady && !measurementArmed -> "POSITIONING"
            !measurementReady -> "PREPARING"
            dev.kyungmin.kcoach.core.StartWristOrientationPolicy.uncheckedStartStatus(exerciseId) == "UNCONFIRMED" -> "WORKING_START_UNCHECKED"
            else -> "WORKING"
        }
    }

    private fun sendLiveState(phase: String, recording: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStateAtMs < 350L) return
        lastStateAtMs = now
        val critical = phase == "SET_COMPLETE" || phase == "CALIBRATION_RESULT" || phase == "REST_COMPLETE"
        sendToPhone(
            WearSync.PATH_LIVE,
            WearSync.liveState(
                sessionId,
                exerciseId,
                exerciseName,
                setIndex,
                reps,
                targetReps,
                weightKg,
                heartRate,
                phase,
                recording,
                restEndAtMs,
                captureSampleCount,
                trackingProfile.id,
                trackingProfile.calibrationProfileVersion != null,
                setId = setId, attemptId = attemptId,
            ),
            coalescible = !critical,
        )
        sendToPhone(WearSync.PATH_STATE, WearSync.state(sessionId, exerciseId, setIndex, reps, heartRate, recording))
        liveStateCount += 1
    }

    private fun sendToPhone(path: String, payload: String, coalescible: Boolean = true) {
        val priority = when {
            !coalescible -> PRIORITY_CONTROL
            path == WearSync.PATH_LIVE || path == WearSync.PATH_STATE || path == WearSync.PATH_SAMPLE -> PRIORITY_LIVE
            path == WearSync.PATH_CAPTURE_MANIFEST_V3 -> PRIORITY_CAPTURE
            else -> PRIORITY_CONTROL
        }
        val task = OutboundTask(path, priority, outboundSequence.incrementAndGet()) {
            runCatching {
                Tasks.await(Wearable.getNodeClient(this).connectedNodes, 2, TimeUnit.SECONDS).forEach { node ->
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, path, payload.toByteArray()), 2, TimeUnit.SECONDS)
                }
            }
            if (isEmulator) emulatorBridge.send(path, payload)
        }
        synchronized(outboundQueue) {
            if (priority == PRIORITY_LIVE && coalescible) {
                outboundQueue.removeIf { queued -> queued is OutboundTask && queued.path == path }
            }
            if (outboundQueue.size >= MAX_OUTBOUND_QUEUE) {
                if (priority == PRIORITY_LIVE) return
                val disposable = outboundQueue
                    .filterIsInstance<OutboundTask>()
                    .filter { it.priority > priority }
                    .maxByOrNull { it.sequence }
                if (disposable != null) outboundQueue.remove(disposable) else {
                    Log.w(TAG, "outbound queue full path=$path")
                    return
                }
            }
            outboundExecutor.execute(task)
            maxObservedOutboundQueue = maxOf(maxObservedOutboundQueue, outboundQueue.size)
        }
    }

    private class OutboundTask(
        val path: String,
        val priority: Int,
        val sequence: Long,
        private val action: () -> Unit,
    ) : Runnable, Comparable<OutboundTask> {
        override fun run() = action()

        override fun compareTo(other: OutboundTask): Int {
            val priorityOrder = priority.compareTo(other.priority)
            return if (priorityOrder != 0) priorityOrder else sequence.compareTo(other.sequence)
        }
    }

    private data class SavedCapture(
        val captureId: String,
        val fileName: String,
        val bytes: ByteArray,
        val manifest: String,
    )

    private fun saveCapture(): SavedCapture? {
        if (captureSampleCount == 0 || sessionId.isBlank() || captureRecoveryError != null) return null
        if (captureAttemptInterrupted) {
            captureRecoveryError = "Sensor attempt was interrupted"
            quarantineIncompleteCapture()
            return null // Both boot streams stay recoverable; neither is a complete set analysis.
        }
        return runCatching {
            check(flushCaptureChunk()) { "failed to flush capture chunk" }
            val allRecords = readCaptureChunks(captureSampleCount).flatMap { it.records }
            check(allRecords.size == captureSampleCount) { "capture chunk sample count mismatch" }
            val captureId = finalizingCaptureId ?: "capture-${UUID.randomUUID()}".also { finalizingCaptureId = it }
            check(persistRuntime()) { "Capture identity checkpoint failed" }
            require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}")))
            val fileName = "$captureId.kcs"
            val bytes = ByteArrayOutputStream().use { output ->
                SensorCaptureFormat.write(output, allRecords, 50.0)
                output.toByteArray()
            }
            atomicWrite(File(filesDir, fileName), bytes)
            val document = SensorCaptureFormat.read(ByteArrayInputStream(bytes))
            val startNs = document.startMonotonicNs ?: 0L
            val endNs = document.endMonotonicNs ?: startNs
            val durationSeconds = ((endNs - startNs).coerceAtLeast(0L)) / 1_000_000_000.0
            val observedRate = if (document.sampleCount > 1 && durationSeconds > 0.0) {
                (document.sampleCount - 1) / durationSeconds
            } else {
                0.0
            }
            val manifest = WireMessages.captureManifest(
                sessionId = sessionId,
                exerciseSessionId = exerciseId,
                setId = setId.ifBlank { "$sessionId-set-$setIndex" },
                captureId = captureId,
                formatVersion = SensorCaptureFormat.FORMAT_VERSION,
                compression = "NONE",
                sampleCount = document.sampleCount,
                sampleRateRequestedHz = document.sampleRateRequestedHz,
                sampleRateObservedHz = observedRate,
                startMonotonicNs = startNs,
                endMonotonicNs = endNs,
                sha256 = SensorCaptureFormat.sha256(bytes),
                assetSizeBytes = bytes.size.toLong(),
                watchDetectedReps = reps,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                setIndex = setIndex,
                attemptId = attemptId,
            ).let { manifest ->
                val epoch = WearClockEpoch.current(this)
                manifest.copy(payload = manifest.payload + mapOf(
                    "capturePurpose" to dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId).name,
                    "watchClockId" to epoch.id,
                    "watchDeviceId" to epoch.deviceId,
                    "watchEpochVerified" to epoch.verified.toString(),
                    "patternShadowCommandId" to patternShadowCommandId,
                    "patternShadowSnapshot" to frozenShadow?.runtime?.let { Base64.getEncoder().encodeToString(dev.kyungmin.kcoach.core.PatternRuntimeCodec.encode(it)) }.orEmpty(),
                    "patternShadowUsed" to shadowUseConfirmed.toString(),
                    "patternShadowCount" to shadowCount.toString(),
                    "patternShadowInterrupted" to shadowInterrupted.toString(),
                    "patternShadowTrace" to shadowTraceSnapshot().orEmpty(),
                    "patternBinding" to patternBinding,
                    "patternWearing" to patternWearing,
                    "patternSelectionMode" to patternSelectionMode,
                    "startDirectionStatus" to startDirectionStatus,
                    "startDirectionSnapshot" to startDirectionSnapshot,
                    "countingStartNs" to countingStartNs?.toString().orEmpty(),
                    "tempoSettings" to tempoSettings, "tempoMuted" to tempoMuted.toString(), "lastTempoHint" to tempoHint,
                    "startDirectionCandidate" to dev.kyungmin.kcoach.core.StartWristOrientationPolicy.candidate(exerciseId).name,
                    "startWristContext" to startWristContext,
                    "startWristDeviceId" to startWristDeviceId,
                    "patternActiveCommandId" to frozenActive?.commandId.orEmpty(),
                    "patternBaselineCount" to reps.toString(),
                    "patternBaselineSampleCalls" to activeSampleCalls.toString(),
                    "patternActiveCandidateHash" to frozenActive?.runtime?.candidateHash.orEmpty(),
                    "patternActiveSnapshot" to frozenActive?.runtime?.let { Base64.getEncoder().encodeToString(dev.kyungmin.kcoach.core.PatternRuntimeCodec.encode(it)) }.orEmpty(),
                    "patternActiveUsed" to activeUseConfirmed.toString(),
                    "patternActiveInterrupted" to activeInterrupted.toString(),
                    dev.kyungmin.kcoach.core.WirePayloadKeys.TRACKING_PROFILE_ID to trackingProfile.id,
                    dev.kyungmin.kcoach.core.WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT to
                        dev.kyungmin.kcoach.core.TrackingProfileSnapshot.encode(trackingProfile),
                ))
            }.let(WireCodec::encode)
            atomicWrite(File(filesDir, "$captureId.manifest"), manifest.toByteArray(Charsets.UTF_8))
            rememberPendingCapture(captureId)
            writeStorageReport()
            Log.i(TAG, "capture saved id=$captureId samples=${document.sampleCount} bytes=${bytes.size}")
            val finalizedChunks = captureChunkFiles.toList()
            captureChunkFiles.clear()
            if (!persistRuntime()) {
                captureChunkFiles.addAll(finalizedChunks)
                throw java.io.IOException("Finalized capture checkpoint failed")
            }
            finalizedChunks.forEach(File::delete)
            sendToPhone(WearSync.PATH_CAPTURE_MANIFEST_V3, manifest)
            SavedCapture(captureId, fileName, bytes, manifest)
        }.onFailure {
            captureRecoveryError = it.message ?: "Capture finalization failed"
            running = false; resting = false; paused = true
            persistRuntime()
            Log.e(TAG, "capture save failed session=$sessionId set=$setIndex samples=$captureSampleCount", it)
        }.getOrNull()
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, "${destination.name}.incoming")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temp.renameTo(destination)) { "failed to commit ${destination.name}" }
    }

    private fun appendCaptureRecord(record: SensorCaptureRecord) {
        if (captureSampleCount >= MAX_CAPTURE_SAMPLES) {
            if (!captureLimitLogged) {
                Log.w(TAG, "capture sample limit reached session=$sessionId set=$setIndex")
                captureLimitLogged = true
            }
            return
        }
        captureRecords += record
        captureSampleCount += 1
        if (captureRecords.size >= CAPTURE_CHUNK_SAMPLES && !flushCaptureChunk())
            throw java.io.IOException("Capture checkpoint failed")
    }

    private fun flushCaptureChunk(): Boolean {
        if (captureRecords.isEmpty()) return !chunkJournal.isFile || persistRuntime()
        return runCatching {
            val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val identityKey = "$setId:$attemptId" + captureSegmentId.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()
            val identity = UUID.nameUUIDFromBytes(identityKey.toByteArray(Charsets.UTF_8))
            val chunk = File(filesDir, ".capture-$safeSession-$identity-${captureChunkIndex}.kcs.part")
            val bytes = ByteArrayOutputStream().also { output ->
                SensorCaptureFormat.write(output, captureRecords.toList(), 50.0)
            }.toByteArray()
            captureChunkFiles += chunk
            try {
                // The journal pins the original session/set/attempt before the new chunk is committed.
                atomicWrite(chunkJournal, runtimeState().toString().toByteArray(Charsets.UTF_8))
                atomicWrite(chunk, bytes)
            } catch (error: Exception) {
                captureChunkFiles.remove(chunk) // The original records remain in memory for a retry.
                throw error
            }
            captureChunkIndex++
            captureRecords.clear()
            persistRuntime()
        }.getOrElse {
            Log.e(TAG, "capture chunk flush failed", it)
            false
        }
    }

    private fun resetCaptureBuffer(deleteChunks: Boolean) {
        captureSegmentId = UUID.randomUUID().toString()
        captureBootCount = currentBootCount()
        sensorClockNeedsCheck = false
        captureAttemptInterrupted = false
        captureRecoveryError = null
        captureRecords.clear()
        if (deleteChunks) captureChunkFiles.forEach(File::delete)
        captureChunkFiles.clear()
        captureSampleCount = 0
        captureChunkIndex = 0
        finalizingCaptureId = null
        captureLimitLogged = false
        lastCaptureTimestampNs = 0L
    }

    private fun sendCaptureToPhone(capture: SavedCapture) {
        if (!captureSendInFlight.add(capture.captureId)) return
        val emulatorPayload = listOf(
            capture.captureId,
            Base64.getEncoder().encodeToString(capture.manifest.toByteArray(Charsets.UTF_8)),
            Base64.getEncoder().encodeToString(capture.bytes),
        ).joinToString("|")
        if (isEmulator) {
            // completeSet() runs on the service main looper. Network I/O here
            // throws NetworkOnMainThreadException before the relay can receive
            // the asset, so keep the emulator lane off the main thread too.
            submitCaptureTransfer(capture.captureId) {
                val relayWrite = emulatorBridge.send(WearSync.PATH_CAPTURE_ASSET_EMULATOR, emulatorPayload)
                handler.post {
                    Log.i(TAG, "capture send id=${capture.captureId} emulator=true relayWrite=$relayWrite bytes=${capture.bytes.size}")
                    // A relay TCP write only proves that the host accepted bytes;
                    // keep the outbox retryable until the phone parses, persists,
                    // and returns CAPTURE_ACK.
                    handler.postDelayed({
                        captureSendInFlight.remove(capture.captureId)
                        val stillPending = capturePrefs
                            .getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet())
                            .orEmpty()
                            .contains(capture.captureId)
                        if (stillPending) {
                            Log.i(TAG, "capture retry id=${capture.captureId}")
                            schedulePendingCaptureRetry()
                        }
                    }, CAPTURE_RETRY_DELAY_MS)
                }
            }
            return
        }
        submitCaptureTransfer(capture.captureId) {
            runCatching {
                val request = PutDataMapRequest.create(WearSync.captureAssetPath(capture.captureId))
                request.dataMap.putString("wireManifest", capture.manifest)
                request.dataMap.putAsset("captureAsset", Asset.createFromBytes(capture.bytes))
                request.dataMap.putLong("updatedAt", System.currentTimeMillis())
                Tasks.await(
                    Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent()),
                    CAPTURE_TRANSFER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            }.onFailure { Log.w(TAG, "capture transfer deferred id=${capture.captureId}", it) }
            handler.post {
                captureSendInFlight.remove(capture.captureId)
                // A DataItem write does not acknowledge reception on the phone.
                if (capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().contains(capture.captureId)) schedulePendingCaptureRetry()
                else if (capturePrefs.getString(KEY_ACKED_CAPTURE_IDS, "").orEmpty().lines().contains(capture.captureId)) {
                    val cleanup = capturePrefs.getStringSet(KEY_PENDING_DATA_CLEANUP, emptySet()).orEmpty().toMutableSet()
                    cleanup.add(capture.captureId)
                    if (capturePrefs.edit().putStringSet(KEY_PENDING_DATA_CLEANUP, cleanup).commit()) retryDataCleanup()
                }
            }
        }
    }

    private fun submitCaptureTransfer(captureId: String, action: () -> Unit) {
        try {
            captureTransferExecutor.execute(Runnable { action() })
        } catch (error: RejectedExecutionException) {
            captureSendInFlight.remove(captureId)
            Log.w(TAG, "capture upload queue full id=$captureId", error)
            schedulePendingCaptureRetry()
        }
    }

    private fun schedulePendingCaptureRetry(delayMs: Long = CAPTURE_RETRY_DELAY_MS) {
        if (serviceDestroyed) return
        handler.removeCallbacks(pendingCaptureRetry)
        handler.postDelayed(pendingCaptureRetry, delayMs)
    }

    private fun acknowledgeCapture(captureId: String) {
        if (!captureId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) return
        val pending = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().toMutableSet()
        if (!pending.remove(captureId)) return
        val cleanup = capturePrefs.getStringSet(KEY_PENDING_DATA_CLEANUP, emptySet()).orEmpty().toMutableSet()
        if (!isEmulator) cleanup.add(captureId)
        val acked = (capturePrefs.getString(KEY_ACKED_CAPTURE_IDS, "").orEmpty().lines().filter { it.isNotBlank() } + captureId).distinct().takeLast(256)
        check(capturePrefs.edit().putStringSet(KEY_PENDING_CAPTURE_IDS, pending).putStringSet(KEY_PENDING_DATA_CLEANUP, cleanup)
            .putString(KEY_ACKED_CAPTURE_IDS, acked.joinToString("\n")).commit()) { "Failed to persist capture acknowledgement" }
        deleteFile("$captureId.kcs"); deleteFile("$captureId.manifest")
        captureSendInFlight.remove(captureId)
        retryDataCleanup(); writeStorageReport()
        if (sessionId.isBlank() && pendingCompletion == null && pending.isEmpty()) stopRuntimeService()
    }

    /** Only transport copies with a durable phone ACK may be removed. Failed removals stay queued. */
    private fun retryDataCleanup() {
        if (serviceDestroyed || !dataCleanupInFlight.compareAndSet(false, true)) return
        val id = capturePrefs.getStringSet(KEY_PENDING_DATA_CLEANUP, emptySet()).orEmpty().firstOrNull()
        if (id == null) { dataCleanupInFlight.set(false); return }
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                dataCleanupInFlight.set(false)
                Log.w(TAG, "DataItem cleanup timed out; retained id=$id")
            }
        }
        handler.postDelayed(timeout, CAPTURE_TRANSFER_TIMEOUT_MS)
        runCatching {
            val uri = android.net.Uri.parse("wear://*${WearSync.captureAssetPath(id)}")
            com.google.android.gms.wearable.Wearable.getDataClient(this)
                .deleteDataItems(uri, com.google.android.gms.wearable.DataClient.FILTER_LITERAL)
                .addOnCompleteListener { task ->
                    if (!settled.compareAndSet(false, true)) return@addOnCompleteListener
                    handler.removeCallbacks(timeout)
                    dataCleanupInFlight.set(false)
                    if (task.isSuccessful) {
                        val raw = File(filesDir, "$id.kcs"); val manifest = File(filesDir, "$id.manifest")
                        if ((raw.isFile && !raw.delete()) || (manifest.isFile && !manifest.delete())) return@addOnCompleteListener
                        val latest = capturePrefs.getStringSet(KEY_PENDING_DATA_CLEANUP, emptySet()).orEmpty().toMutableSet()
                        latest.remove(id)
                        if (capturePrefs.edit().putStringSet(KEY_PENDING_DATA_CLEANUP, latest).commit()) {
                            writeStorageReport(); retryDataCleanup()
                        }
                    } else Log.w(TAG, "Acknowledged DataItem cleanup deferred id=$id", task.exception)
                }
        }.onFailure {
            if (settled.compareAndSet(false, true)) { handler.removeCallbacks(timeout); dataCleanupInFlight.set(false) }
            Log.w(TAG, "DataItem cleanup retained id=$id", it)
        }
    }

    private fun writeStorageReport() {
        runCatching {
            val pending = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty()
            val cleanup = capturePrefs.getStringSet(KEY_PENDING_DATA_CLEANUP, emptySet()).orEmpty()
            val files = filesDir.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".kcs") || it.name.endsWith(".manifest") || it.name.endsWith(".kcs.part")) }
            val referenced = captureChunkFiles.map { it.name }.toSet() + (pending + cleanup).flatMap { listOf("$it.kcs", "$it.manifest") }
            val incomplete = filesDir.listFiles().orEmpty().filter { it.name.startsWith("capture-incomplete-") && it.name.endsWith(".json") }
            val report = JSONObject().put("generatedAtMs", System.currentTimeMillis()).put("queuedCaptures", pending.size)
                .put("incompleteCaptures", incomplete.size).put("captureRecoveryRequired", captureRecoveryError != null)
                .put("missingRuntimeChunks", captureChunkFiles.count { !it.isFile })
                .put("missingQueuedFiles", pending.count { !File(filesDir, "$it.kcs").isFile || !File(filesDir, "$it.manifest").isFile })
                .put("pendingDataCleanup", cleanup.size).put("storedBytes", files.sumOf { it.length() })
                .put("retainedUnknownBytes", files.filter { it.name !in referenced }.sumOf { it.length() })
            atomicWrite(File(filesDir, "capture-storage.json"), report.toString().toByteArray(Charsets.UTF_8))
        }.onFailure { Log.w(TAG, "Watch storage report unavailable", it) }
    }

    private fun rememberPendingCapture(captureId: String) {
        val pending = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().toMutableSet()
        if (pending.add(captureId)) check(capturePrefs.edit().putStringSet(KEY_PENDING_CAPTURE_IDS, pending).commit()) { "Failed to save pending capture" }
    }

    private fun retryPendingCaptures() {
        capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().forEach { captureId ->
            val captureFile = File(filesDir, "$captureId.kcs")
            val manifestFile = File(filesDir, "$captureId.manifest")
            if (!captureFile.isFile || !manifestFile.isFile) {
                Log.e(TAG, "Pending capture has missing files; delivery is not acknowledged id=$captureId")
                writeStorageReport()
                return@forEach
            }
            val manifest = runCatching { manifestFile.readText() }.getOrNull() ?: return@forEach
            val bytes = runCatching { captureFile.readBytes() }.getOrNull() ?: return@forEach
            sendCaptureToPhone(SavedCapture(captureId, captureFile.name, bytes, manifest))
        }
    }

    private fun resetSessionFields() {
        countingPaused = false; countingResetAfterNs = 0L; editStatus = ""
        shadowSampleCalls = 0L; shadowProcessingHistogram.fill(0); shadowEvents.clear()
        patternShadowCommandId = ""; frozenShadow = null; shadowDetector = null; shadowUseConfirmed = false; shadowCount = 0; shadowInterrupted = false
        patternBinding = ""; patternWearing = ""; patternSelectionMode = ""; frozenActive = null; activeDetector = null; activeUseConfirmed = false; activeInterrupted = false
        startWristContext = ""; startWristDeviceId = ""
        startDirectionSnapshot = ""; countingStartNs = null; startDirectionStatus = "UNCONFIRMED"
        tempoSettings = ""; tempoMuted = false; tempoHint = ""; lastTempoAlertNs = 0L
        tempoPolicy = dev.kyungmin.kcoach.core.TempoFeedbackPolicy()
        sessionId = ""
        setId = ""; attemptId = ""
        exerciseId = ""
        exerciseName = "운동 대기 중"
        setIndex = 0
        reps = 0
        heartRate = 0
        // A failed finalization must not erase the only durable raw chunks.
        resetCaptureBuffer(deleteChunks = false)
        forearmRotationDetector = null
        profiledMotionDetector = null
        trackingProfile = ExerciseTrackingProfiles.resolve("", "")
        calibrationStage = null
        measurementReady = false
        measurementArmed = false
        cancelMeasurementPreparation()
    }

    /** Sensors and foreground notification are removed once the session is over. */
    private fun stopRuntimeService(startId: Int = 0) {
        stopRestTicker()
        phoneWatchdog?.let(handler::removeCallbacks)
        phoneWatchdog = null
        sensorManager.unregisterListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (startId > 0) stopSelfResult(startId) else stopSelf()
    }

    private fun broadcastState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        recordState.observe(sessionId, setId, attemptId, running || paused && !resting && pausedRestRemainingMs == 0L)
        WatchSessionTally.get(this).observe(sessionId, setId, attemptId, running, resting, paused, reps, weightKg, heartRate, now)
        recordState.currentRecord()?.let { WatchSessionTally.get(this).noteSetRecord(it.sessionId, it.setId) }
        if (!force && now - lastUiBroadcastAtMs < UI_BROADCAST_INTERVAL_MS) return
        LocalExitDiagnostics.workoutPhase(currentPhase(), sessionId, lastCaptureTimestampNs)
        lastUiBroadcastAtMs = now
        uiBroadcastCount += 1
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            recordState.addExtras(this)
            putExtra("paired", paired)
            putExtra("running", running)
            putExtra("resting", resting)
            putExtra("paused", paused)
            putExtra("countingPaused", countingPaused)
            putExtra("session", sessionId)
            putExtra("exerciseId", exerciseId)
            putExtra("exerciseName", exerciseName)
            putExtra("setIndex", setIndex)
            putExtra("setId", setId)
            putExtra("attemptId", attemptId)
            putExtra("manualRequired", trackingProfile.mode == TrackingMode.MANUAL_REQUIRED)
            putExtra("manualStatus", editStatus.ifEmpty { workoutControls.latest()?.takeIf {
                it.command.sessionId == sessionId && it.command.setId == setId && it.command.attemptId == attemptId &&
                    it.command.action in setOf(dev.kyungmin.kcoach.core.WatchWorkoutAction.REST_ADJUST, dev.kyungmin.kcoach.core.WatchWorkoutAction.REST_PAUSE, dev.kyungmin.kcoach.core.WatchWorkoutAction.REST_RESUME, dev.kyungmin.kcoach.core.WatchWorkoutAction.EDIT_SET)
            }?.let { it.ack?.status?.name ?: "PENDING" } ?: run {
                manualOutbox.latest(sessionId, setId, attemptId)?.let { it.ack?.status?.name ?: "PENDING" }.orEmpty()
            } })
            putExtra("targetReps", targetReps)
            putExtra("weightKg", weightKg ?: Double.NaN)
            putExtra("restSeconds", restSeconds)
            putExtra("restRev", phoneRev)
            putExtra("watchRestSeq", mySeq)
            putExtra("restEndAtMs", restEndAtMs)
            // A rest paused from the phone keeps the rest screen, frozen at the time that was left.
            putExtra("restPaused", paused && pausedRestRemainingMs > 0L)
            putExtra("pausedRestRemainingMs", pausedRestRemainingMs)
            putExtra("reps", reps)
            putExtra("heartRate", heartRate)
            putExtra("captureSampleCount", captureSampleCount.coerceAtMost(MAX_CAPTURE_SAMPLES))
            putExtra("phase", currentPhase())
            putExtra("completedSessionId", WatchHomeStore(this@WearWorkoutService).completion()?.sessionId.orEmpty())
            putExtra("showCompletion", WatchHomeStore(this@WearWorkoutService).showsCompletion())
            putExtra("trackingProfileId", trackingProfile.id)
            putExtra("personalized", trackingProfile.calibrationProfileVersion != null)
            putExtra("patternShadowCommandId", patternShadowCommandId)
            putExtra("patternShadowCount", shadowCount)
            putExtra("tempoHint", tempoHint)
            putExtra("tempoEnabled", tempoSettings.takeIf { it.isNotEmpty() }?.let {
                dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.decode(it).enabled && !tempoMuted
            } == true)
            putExtra("patternShadowEventIndex", shadowEvents.lastIndex)
            putExtra("patternShadowEmissionNs", shadowEvents.lastOrNull()?.sourceEmissionNs ?: -1L)
            if (dev.kyungmin.kcoach.core.CapturePurpose.forSession(sessionId) != dev.kyungmin.kcoach.core.CapturePurpose.WORKOUT && !measurementReady) {
                if (running) putExtra("phase", "BASELINE")
                putExtra("preparationHoldMs", preparationHoldMs)
                putExtra("preparationHoldTargetMs", if (sessionId.startsWith("calibration-paired-")) 3_000L else StartPoseGateConfig.forProfile(trackingProfile).stableDurationMs)
            }
            calibrationCameraState.applyTo(this, sessionId)
        })
    }

    private fun currentPhase(): String = when {
        captureRecoveryError != null -> "CAPTURE_RECOVERY_REQUIRED"
        !resting && manualOutbox.latest(sessionId, setId, attemptId)?.ack?.status == dev.kyungmin.kcoach.core.ManualSetStatus.CONFLICT -> "MANUAL_CONFLICT"
        !resting && manualOutbox.latest(sessionId, setId, attemptId)?.let { it.ack == null } == true -> "MANUAL_PENDING"
        paused -> "PAUSED"
        resting -> "RESTING"
        running -> currentWorkingPhase()
        sessionId.isNotBlank() -> "READY"
        else -> "IDLE"
    }

    private fun hasBodySensorPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    private fun flushForearmRotation() {
        if (countingPaused) return
        val rotationMarker = forearmRotationDetector?.finish()
        val profiledMarker = profiledMotionDetector?.finish()
        if (rotationMarker != null || profiledMarker != null) {
            reps += 1
            Log.i(
                TAG,
                "rep flushed external=${rotationMarker != null} profiled=${profiledMarker != null}",
            )
        }
    }

    private fun vibrateShort() {
        if (!running) return
        val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return
        // Same 35 ms length. A fixed mid-high strength (below the target pulse's 255) where the motor supports it.
        val amplitude = if (vibrator.hasAmplitudeControl()) REP_TICK_AMPLITUDE else VibrationEffect.DEFAULT_AMPLITUDE
        vibrator.vibrate(VibrationEffect.createOneShot(35, amplitude))
    }

    private fun vibrateTargetReached() {
        if (!running) return
        val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return
        val timings = longArrayOf(0L, 70L, 60L, 70L, 60L, 110L)
        vibrator.vibrate(
            if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255, 0, 255), -1)
            else VibrationEffect.createWaveform(timings, -1),
        )
    }

    private fun vibrateReady() {
        if (!running) return
        getSystemService(android.os.Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 90L, 70L, 130L), -1),
        )
    }

    private fun vibrateCompleted() {
        getSystemService(android.os.Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 150L, 70L, 220L), -1),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "워치짐 워치 운동", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("워치짐")
        .setContentText("워치 센서 세션을 유지하는 중")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic", ignoreCase = true) || Build.MODEL.contains("sdk_gwear", ignoreCase = true)

    companion object {
        private val MUTATING_COMMANDS = setOf("START", "SET", "SYNC", "REST", "PAUSE", "RESUME", "STOP", "END", "COMPLETE", "SET_COMPLETE")
        private const val TAG = "KCoachCapture"
        private const val EVENT_TAG = "KCoachEvent"
        private const val CALIBRATION_TAG = "KCoachCalibration"
        const val ACTION_STATE = "dev.kyungmin.kcoach.wear.RUNTIME_STATE"
        const val ACTION_WORKOUT_CONTROL = "dev.kyungmin.kcoach.wear.WORKOUT_CONTROL"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "kcoach_wear_workout"
        private const val NOTIFICATION_ID = 4102
        private const val REP_TICK_AMPLITUDE = 180
        private const val PHONE_HEARTBEAT_TIMEOUT_MS = 12_000L
        private const val PHONE_WATCHDOG_INTERVAL_MS = 3_000L
        private const val CAPTURE_PREFS = "capture-outbox"
        private const val KEY_PENDING_CAPTURE_IDS = "pendingCaptureIds"
        private const val KEY_PENDING_DATA_CLEANUP = "pendingDataCleanup"
        private const val KEY_ACKED_CAPTURE_IDS = "ackedCaptureIds"
        private const val CAPTURE_RETRY_DELAY_MS = 5_000L
        private const val CAPTURE_TRANSFER_TIMEOUT_MS = 12_000L
        private const val CAPTURE_CHUNK_SAMPLES = 250
        private const val MAX_CAPTURE_SAMPLES = 60_000
        private const val UI_BROADCAST_INTERVAL_MS = 125L
        private const val MAX_OUTBOUND_QUEUE = 32
        private const val PRIORITY_CONTROL = 0
        private const val PRIORITY_CAPTURE = 1
        private const val PRIORITY_LIVE = 2
        private const val CALIBRATION_BASELINE_MS = 2_000L
        private const val MEASUREMENT_PREPARATION_MS = 2_000L
    }
}
