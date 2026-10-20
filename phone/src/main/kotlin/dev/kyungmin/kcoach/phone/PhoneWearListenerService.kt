package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Build
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.CaptureState
import dev.kyungmin.kcoach.core.ObservedMotionMetricsCodec
import dev.kyungmin.kcoach.core.PhoneRepAnalyzer
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WireMessages
import dev.kyungmin.kcoach.core.WirePayloadKeys
import dev.kyungmin.kcoach.core.WireSource
import dev.kyungmin.kcoach.core.KCoachEventMarker
import dev.kyungmin.kcoach.core.KCoachEventTrace
import dev.kyungmin.kcoach.core.PersonalCalibrationBuilder
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import dev.kyungmin.kcoach.core.CalibrationBuildResult
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Receives both small messages and large set capture Assets from Wear OS. */
class PhoneWearListenerService : WearableListenerService() {
    private val prefs by lazy { getSharedPreferences("wear-sync", MODE_PRIVATE) }
    private val commandOutbox by lazy { PhoneCommandOutbox(this) }
    private val sessionController by lazy {
        PhoneSessionController(SessionStore(this)) { path, payload -> PhoneWorkoutService.send(this, path, payload) }
    }
    private var emulatorBridge: EmulatorBridgeClient? = null
    private lateinit var captureInbox: CaptureInbox
    private val attemptedCaptures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val assetRecoveryRequested = AtomicBoolean(false)
    private val assetRecoveryQueued = AtomicBoolean(false)
    private val recoveryQueued = AtomicBoolean(false)
    private val recoveryRequested = AtomicBoolean(false)
    private val analysisExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "kcoach-capture-analysis").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val inboxExecutor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "kcoach-capture-inbox").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun onCreate() {
        super.onCreate()
        dev.kyungmin.kcoach.updates.OtaCoordinator.get(this).recover()
        captureInbox = CaptureInbox(this, prefs)
        if (Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true)) emulatorBridge = EmulatorBridgeClient(::handlePayload).also { it.start() }
        inboxExecutor.execute {
            runCatching { FinishedWorkoutStore(this).replay() }
                .onSuccess { count -> if (count > 0) sendBroadcast(Intent(PhoneWorkoutService.ACTION_SESSION_CHANGED).setPackage(packageName).putExtra("recordsRecovered", true)) }
                .onFailure { Log.e(TAG, "workout recording recovery failed", it) }
            scheduleRecovery()
        }
        recoverCachedAssets()
    }

    private fun scheduleRecovery() {
        recoveryRequested.set(true)
        if (inboxExecutor.isShutdown || !recoveryQueued.compareAndSet(false, true)) return
        try {
            inboxExecutor.execute {
                try {
                    recoveryRequested.set(false)
                    captureInbox.pendingAnalysis(excluded = attemptedCaptures).firstOrNull()?.let(::scheduleAnalysis)
                    runCatching { captureInbox.compressSuccessfulCaptures(); captureInbox.storageReport() }
                        .onFailure { Log.w(TAG, "capture storage maintenance deferred", it) }
                } finally {
                    recoveryQueued.set(false)
                    if (recoveryRequested.get() && !inboxExecutor.isShutdown) scheduleRecovery()
                }
            }
        } catch (error: RejectedExecutionException) {
            recoveryQueued.set(false)
            Log.w(TAG, "capture recovery retained for next available worker", error)
        }
    }

    override fun onDestroy() {
        emulatorBridge?.stop()
        emulatorBridge = null
        inboxExecutor.shutdown()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            if (dev.kyungmin.kcoach.updates.OtaDataLayer.receive(this, event.path, String(event.data), event.sourceNodeId)) return
            handlePayload(event.path, String(event.data))
        }.onFailure { Log.e(TAG, "wear message rejected path=${event.path}; durable state retained", it) }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        runCatching { dev.kyungmin.kcoach.updates.OtaDataLayer.receive(this, dataEvents) }
            .onFailure { Log.w(TAG, "update data rejected; capture scan continues", it) }
        if (dataEvents.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path.orEmpty().startsWith(WearSync.PATH_CAPTURE_ASSET_V3) }) {
            runCatching { recoverCachedAssets() }.onFailure { Log.w(TAG, "cached capture scan deferred", it) }
        }
    }

    override fun onPeerConnected(peer: com.google.android.gms.wearable.Node) {
        super.onPeerConnected(peer)
        dev.kyungmin.kcoach.updates.OtaCoordinator.get(this).recover()
        WatchOverviewPublisher.publish(this)
    }

    /** Identical retransmissions need not produce another change event; also inspect the cache on restart. */
    private fun recoverCachedAssets() {
        assetRecoveryRequested.set(true)
        if (inboxExecutor.isShutdown || !assetRecoveryQueued.compareAndSet(false, true)) return
        assetRecoveryRequested.set(false)
        val settled = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                assetRecoveryQueued.set(false)
                Log.w(TAG, "cached capture scan timed out; retained for reconnect")
            }
        }
        handler.postDelayed(timeout, CAPTURE_ASSET_TIMEOUT_MS)
        runCatching {
            Wearable.getDataClient(this).getDataItems(
                android.net.Uri.parse("wear://*${WearSync.PATH_CAPTURE_ASSET_V3}"),
                com.google.android.gms.wearable.DataClient.FILTER_PREFIX,
            ).addOnFailureListener { error ->
                if (settled.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout); assetRecoveryQueued.set(false)
                    Log.w(TAG, "cached capture scan deferred", error)
                }
            }.addOnSuccessListener { buffer ->
                consumeCachedAssets(settled, handler, timeout, { buffer.map { it.freeze() } }, { buffer.release() })
            }
        }.onFailure { error ->
            if (settled.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout); assetRecoveryQueued.set(false)
                Log.w(TAG, "cached capture scan unavailable", error)
            }
        }
    }

    /** The success callback runs later: its buffer work needs its own boundary and always releases once. */
    internal fun consumeCachedAssets(
        settled: AtomicBoolean,
        handler: Handler,
        timeout: Runnable,
        freeze: () -> List<com.google.android.gms.wearable.DataItem>,
        release: () -> Unit,
    ) {
        val claimed = settled.compareAndSet(false, true)
        val frozen = runCatching { if (claimed) freeze() else emptyList() }
        val released = runCatching(release)
        if (!claimed) {
            released.onFailure { Log.w(TAG, "late cached capture buffer release failed", it) }
            return
        }
        handler.removeCallbacks(timeout)
        val error = frozen.exceptionOrNull() ?: released.exceptionOrNull()
        if (error != null) {
            assetRecoveryQueued.set(false)
            assetRecoveryRequested.set(true)
            Log.e(TAG, "cached capture buffer failed; retained for reconnect", error)
            released.exceptionOrNull()?.takeIf { it !== error }?.let { Log.w(TAG, "cached capture buffer release also failed", it) }
            runCatching {
                prefs.edit().putString("assetScanError", error.message ?: "cached capture buffer failure").apply()
                sendBroadcast(Intent(ACTION_CAPTURE_ERROR).setPackage(packageName)
                    .putExtra("stage", "asset_scan").putExtra("error", "워치 기록을 불러오지 못했어요. 연결 후 다시 시도해 주세요"))
            }.onFailure { Log.e(TAG, "cached capture failure status could not be published", it) }
            return
        }
        runCatching {
            prefs.edit().remove("assetScanError").apply()
            val items = frozen.getOrThrow()
            val pending = java.util.ArrayDeque(items)
            fun next() {
                if (inboxExecutor.isShutdown || pending.isEmpty()) {
                    assetRecoveryQueued.set(false)
                    if (assetRecoveryRequested.get() && !inboxExecutor.isShutdown) recoverCachedAssets()
                    return
                }
                runCatching { receiveCaptureAsset(pending.removeFirst()) { handler.post { next() } } }
                    .onFailure { Log.w(TAG, "cached capture read deferred", it); handler.post { next() } }
            }
            next()
        }.onFailure {
            assetRecoveryQueued.set(false)
            assetRecoveryRequested.set(true)
            Log.e(TAG, "cached capture dispatch deferred", it)
        }
    }

    private fun receiveCaptureAsset(item: com.google.android.gms.wearable.DataItem, finished: () -> Unit = {}) {
        val map = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrElse { finished(); return }
        val encodedManifest = map.getString("wireManifest") ?: run { finished(); return }
        val manifest = runCatching { WireCodec.decode(encodedManifest) }.getOrNull() ?: run { finished(); return }
        if (manifest.messageType.name != "CAPTURE_MANIFEST" || manifest.source != WireSource.WATCH) { finished(); return }
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        val asset = map.getAsset("captureAsset") ?: run { finished(); return }
        val sourceNodeId = item.uri.host.orEmpty()
        if (captureId.isBlank() || sourceNodeId.isBlank()) { finished(); return }

        val fdSettled = AtomicBoolean(false)
        val fdHandler = Handler(Looper.getMainLooper())
        val fdTimeout = Runnable {
            if (fdSettled.compareAndSet(false, true)) {
                reportCaptureError(manifest, TimeoutException("asset fd timeout"), "asset_fd")
                finished()
            }
        }
        fdHandler.postDelayed(fdTimeout, CAPTURE_ASSET_TIMEOUT_MS)
        Wearable.getDataClient(this).getFdForAsset(asset)
            .addOnFailureListener { error ->
                if (fdSettled.compareAndSet(false, true)) {
                    fdHandler.removeCallbacks(fdTimeout)
                    reportCaptureError(manifest, error, "asset_fd")
                    finished()
                }
            }
            .addOnSuccessListener { response ->
            if (!fdSettled.compareAndSet(false, true)) {
                runCatching { response.release() }
                return@addOnSuccessListener
            }
            fdHandler.removeCallbacks(fdTimeout)
            try {
                inboxExecutor.execute {
                    runCatching {
                        val bytes = response.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= dev.kyungmin.kcoach.core.CaptureValidator.MAX_CAPTURE_BYTES) { "capture exceeds size limit" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        response.release()
                        logEvent(KCoachEventMarker.CAPTURE_ASSET_RECEIVED, manifest)
                        enqueueCapture(manifest, bytes) {
                            sendCaptureAck(sourceNodeId, manifest.sessionId, manifest.messageId, captureId)
                                .addOnSuccessListener {
                                    finishAck(manifest)
                                    Wearable.getDataClient(this).deleteDataItems(item.uri)
                                }
                        }
                    }.onFailure {
                        runCatching { response.release() }
                        reportCaptureError(manifest, it, "asset_read")
                    }
                    finished()
                }
            } catch (error: RejectedExecutionException) {
                runCatching { response.release() }
                reportCaptureError(manifest, error, "inbox_queue")
                finished()
            }
        }
    }

    private fun receiveEmulatorCapture(payload: String) {
        runCatching {
            val fields = payload.split("|", limit = 3)
            require(fields.size == 3) { "invalid emulator capture payload" }
            val captureId = fields[0]
            val manifestText = String(Base64.getDecoder().decode(fields[1]), Charsets.UTF_8)
            val bytes = Base64.getDecoder().decode(fields[2])
            Log.i(TAG, "emulator capture received id=$captureId bytes=${bytes.size}")
            val manifest = WireCodec.decode(manifestText)
            require(manifest.payload[WirePayloadKeys.CAPTURE_ID] == captureId) { "capture id mismatch" }
            logEvent(KCoachEventMarker.CAPTURE_ASSET_RECEIVED, manifest)
            enqueueCapture(manifest, bytes) {
                val ack = WireCodec.encode(
                    WireMessages.ack(WireSource.PHONE, manifest.sessionId, manifest.messageId, captureId),
                )
                check(emulatorBridge?.send(WearSync.PATH_ACK_V3, ack) == true) {
                    "emulator ACK send failed"
                }
                finishAck(manifest)
            }
        }.onFailure {
            Log.e(TAG, "emulator capture rejected", it)
            sendBroadcast(Intent(ACTION_CAPTURE_ERROR).setPackage(packageName)
                .putExtra("error", it.message ?: "emulator capture verification failed"))
        }
    }

    private fun enqueueCapture(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        bytes: ByteArray,
        acknowledge: () -> Unit,
    ) {
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        val received = captureInbox.receive(manifest, bytes).getOrElse {
            reportCaptureError(manifest, it, "validation")
            return
        }
        if (prefs.getBoolean("capture:$captureId:acked", false) || captureInbox.analysisSucceeded(captureId) ||
            captureInbox.state(captureId) == CaptureState.ANALYZING) {
            runCatching(acknowledge).onFailure { reportCaptureError(manifest, it, "ack") }
            scheduleRecovery()
            return
        }
        logEvent(KCoachEventMarker.CAPTURE_VALIDATED, manifest)
        broadcastCaptureState(manifest, CaptureState.RECEIVED)
        if (dev.kyungmin.kcoach.core.CapturePurpose.forSession(manifest.sessionId) == dev.kyungmin.kcoach.core.CapturePurpose.LEGACY_CALIBRATION) {
            prefs.edit()
                .putString("calibration:${manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()}:status", "RAW_SAVED")
                .putLong("calibration:${manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()}:rawSavedAt", System.currentTimeMillis())
                .commit()
            sendBroadcast(
                Intent(ACTION_CALIBRATION_RAW_SAVED).setPackage(packageName)
                    .putExtra("captureId", captureId)
                    .putExtra("sessionId", manifest.sessionId)
                    .putExtra("exerciseId", manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty())
                    .putExtra("sampleCount", received.validated.document.sampleCount),
            )
        }
        // Raw bytes and metadata are already atomically committed on the phone.
        // Release the watch outbox without waiting for profile analysis.
        runCatching(acknowledge).onFailure { reportCaptureError(manifest, it, "ack") }
        scheduleAnalysis(received)
    }

    private fun scheduleAnalysis(received: ReceivedCapture) {
        val manifest = received.manifest
        val captureId = received.validated.captureId
        if (!attemptedCaptures.add(captureId)) return
        captureInbox.markState(captureId, CaptureState.ANALYZING)
        broadcastCaptureState(manifest, CaptureState.ANALYZING)
        try {
            analysisExecutor.execute {
                val succeeded = runCatching {
                    analyzeAndPersistCapture(received)
                }.onFailure {
                    captureInbox.markState(captureId, CaptureState.ANALYSIS_FAILED, it.message)
                    reportCaptureError(manifest, it, "analysis")
                }.isSuccess
                captureInbox.markAnalysisAttempted(captureId, succeeded)
                if (succeeded && prefs.getBoolean("capture:$captureId:acked", false)) {
                    captureInbox.markState(captureId, CaptureState.ACKED)
                    broadcastCaptureState(manifest, CaptureState.ACKED)
                }
                scheduleRecovery()
            }
        } catch (error: RejectedExecutionException) {
            attemptedCaptures.remove(captureId)
            captureInbox.markState(captureId, CaptureState.ANALYSIS_FAILED, "analysis queue full")
            reportCaptureError(manifest, error, "queue")
        }
    }

    private fun analyzeAndPersistCapture(received: ReceivedCapture) {
        val manifest = received.manifest
        val rawDocument = received.validated.document
        val captureId = received.validated.captureId
        val watchReps = manifest.payload[WirePayloadKeys.WATCH_REPS]?.toIntOrNull()
        val exerciseId = manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()
        val exerciseName = manifest.payload[WirePayloadKeys.EXERCISE_NAME].orEmpty()
        logEvent(KCoachEventMarker.ANALYSIS_STARTED, manifest)
        if (manifest.payload["capturePurpose"] == dev.kyungmin.kcoach.core.CapturePurpose.PAIRED_REFERENCE.name) {
            CalibrationCaptureStore(this).attachSensor(received).getOrThrow()
            CalibrationPatternSafetyMonitor(this).check(manifest.sessionId)
            sendBroadcast(Intent(ACTION_PAIRED_RAW_SAVED).setPackage(packageName).putExtra("captureId", manifest.sessionId))
            return // Paired raw data is reviewed before learning; the legacy builder never adopts it.
        }
        if (dev.kyungmin.kcoach.core.CapturePurpose.forSession(manifest.sessionId) == dev.kyungmin.kcoach.core.CapturePurpose.LEGACY_CALIBRATION) {
            persistCalibrationResult(manifest, rawDocument, exerciseId, exerciseName)
        }
        val personal = prefs.getString("personalProfile:$exerciseId", null)
            ?.takeIf { PersonalizationSetting.isEnabled(this) }
            ?.let { runCatching { PersonalCalibrationProfileCodec.decode(it) }.getOrNull() }
        val generic = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        val recordedSnapshot = manifest.payload[WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT]
        val recordedId = manifest.payload[WirePayloadKeys.TRACKING_PROFILE_ID]
        val trackingProfile = if (recordedSnapshot != null) {
            dev.kyungmin.kcoach.core.TrackingProfileSnapshot.decode(recordedSnapshot).also {
                require(it.exerciseId == generic.exerciseId && it.id == recordedId) { "capture profile identity mismatch" }
            }
        } else if (recordedId == generic.id || personal == null) {
            generic
        } else {
            // Older captures have no frozen configuration; retain their legacy read path.
            PersonalCalibrationBuilder.personalizedTrackingProfile(personal, generic)
        }
        val activeSnapshot = manifest.payload["patternActiveSnapshot"]?.takeIf { it.isNotEmpty() }
        val directionSnapshot = manifest.payload["startDirectionSnapshot"].orEmpty()
        val document = if (directionSnapshot.isEmpty()) rawDocument else {
            val direction = dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.decode(directionSnapshot)
            require(direction.key.context == dev.kyungmin.kcoach.core.StartWristContextCodec.decode(manifest.payload.getValue("startWristContext")) &&
                direction.key.deviceId == manifest.payload["watchDeviceId"] && direction.key.context.exerciseId == generic.exerciseId)
            val status = manifest.payload["startDirectionStatus"]
            require(status in setOf("WAITING", "CONFIRMED", "BYPASSED"))
            val boundary = manifest.payload["countingStartNs"]?.takeIf { it.isNotEmpty() }?.toLong()
            if (status == "WAITING") require(boundary == null)
            dev.kyungmin.kcoach.core.StartDirectionMeasurementWindow.select(rawDocument,
                boundary.takeIf { status in setOf("CONFIRMED", "BYPASSED") })
        }
        val analysis = if (document == null) {
            PhoneRepAnalyzer.analyze(rawDocument.copy(records = emptyList()), trackingProfile, null).result.copy(
                trackingStatus = "준비만 기록됨 · 실제 횟수 입력 필요")
        } else if (activeSnapshot != null) {
            val runtime = dev.kyungmin.kcoach.core.PatternRuntimeCodec.decode(java.util.Base64.getDecoder().decode(activeSnapshot))
            val binding = dev.kyungmin.kcoach.core.PatternCompatibilityCodec.decode(manifest.payload.getValue("patternBinding"))
            require(runtime.compatibility == binding && runtime.candidateHash == manifest.payload["patternActiveCandidateHash"] &&
                binding.deviceId == manifest.payload["watchDeviceId"]) { "capture personal pattern identity mismatch" }
            PhoneRepAnalyzer.analyzePersonal(document, exerciseId, binding, runtime, watchReps,
                used = manifest.payload["patternActiveUsed"] == "true", interrupted = manifest.payload["patternActiveInterrupted"] != "false",
                wristMotionBaseline = ReviewedMotionBaselineStore(this).derive(runtime).getOrNull()).result
        } else {
            require(manifest.payload["patternActiveCandidateHash"].isNullOrEmpty() && manifest.payload["patternActiveUsed"] != "true") {
                "capture active pattern snapshot missing"
            }
            PhoneRepAnalyzer.analyze(document, trackingProfile, watchReps).result
        }
        logEvent(KCoachEventMarker.ANALYSIS_FINISHED, manifest)
        SetAnalysisStore(this).persistAnalysis(manifest, encodeAnalysis(analysis))
        FinishedWorkoutStore(this).reconcile(manifest.sessionId)
        PhoneWorkoutService.publishSessionChanged(this)
        logEvent(KCoachEventMarker.RESULT_SAVED, manifest)
        sendBroadcast(Intent(ACTION_CAPTURE).setPackage(packageName)
            .putExtra("captureId", captureId)
            .putExtra("sessionId", manifest.sessionId)
            .putExtra("exerciseId", manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty())
            .putExtra("exerciseName", manifest.payload[WirePayloadKeys.EXERCISE_NAME].orEmpty())
            .putExtra("setIndex", manifest.payload[WirePayloadKeys.SET_INDEX]?.toIntOrNull() ?: 0)
            .putExtra("sampleCount", rawDocument.sampleCount)
            .putExtra("watchReps", analysis.watchDetectedReps ?: -1)
            .putExtra("phoneReps", analysis.phoneReanalyzedReps ?: -1)
            .putExtra("confirmedReps", analysis.confirmedReps ?: -1)
            .putExtra("analysisConfidence", analysis.analysisConfidence)
            .putExtra("trackingMode", analysis.trackingMode.name)
            .putExtra("trackingProfileId", analysis.trackingProfileId)
            .putExtra("trackingStatus", analysis.trackingStatus)
            .putExtra("capabilityTier", analysis.capabilityTier.name)
            .putExtra("repConfidence", analysis.repConfidence ?: Double.NaN)
            .putExtra("wristMotionSpeed", analysis.wristMotionSpeed ?: Double.NaN)
            .putExtra("wristMotionRange", analysis.wristMotionRange ?: Double.NaN)
            .putExtra("tempoConsistency", analysis.tempoConsistency ?: -1)
            .putExtra("lateSetDegradation", analysis.lateSetDegradation ?: Double.NaN)
            .putExtra("signalQuality", analysis.signalQuality ?: Double.NaN)
            .putExtra("averageAngularSpeed", analysis.averageAngularSpeed ?: Double.NaN)
            .putExtra("relativeRom", analysis.relativeRom ?: Double.NaN))
    }

    private fun persistCalibrationResult(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        document: dev.kyungmin.kcoach.core.SensorCaptureDocument,
        exerciseId: String,
        exerciseName: String,
    ) {
        val result = PersonalCalibrationBuilder.build(document, exerciseId, exerciseName)
        val intent = Intent(ACTION_CALIBRATION_RESULT).setPackage(packageName)
            .putExtra("sessionId", manifest.sessionId)
            .putExtra("exerciseId", exerciseId)
        when (result) {
            is CalibrationBuildResult.Success -> {
                val encoded = PersonalCalibrationProfileCodec.encode(result.profile)
                check(
                    prefs.edit()
                        .putString("personalProfile:$exerciseId", encoded)
                        .putString("calibration:$exerciseId:status", "CALIBRATION_RESULT")
                        .putLong("calibration:$exerciseId:completedAt", System.currentTimeMillis())
                        .remove("calibration:$exerciseId:error")
                        .commit(),
                ) { "failed to persist personal calibration profile" }
                sendPersonalProfile(encoded)
                intent.putExtra("succeeded", true)
                    .putExtra("profile", encoded)
                    .putExtra("validReps", result.profile.validRepCount)
            }
            is CalibrationBuildResult.Failed -> {
                check(
                    prefs.edit()
                        .putString("calibration:$exerciseId:status", "CALIBRATION_FAILED")
                        .putString("calibration:$exerciseId:error", result.reason)
                        .putLong("calibration:$exerciseId:completedAt", System.currentTimeMillis())
                        .commit(),
                ) { "failed to persist calibration failure" }
                intent.putExtra("succeeded", false)
                    .putExtra("reason", result.reason)
                    .putExtra("validReps", result.detectedReps)
            }
        }
        sendBroadcast(intent)
    }

    /**
     * Profile delivery belongs to the listener service so it is not coupled to
     * MainActivity being visible when background capture analysis completes.
     */
    private fun sendPersonalProfile(encoded: String) {
        // While personalization is off the watch must not hold the profile, so the same call removes it instead.
        val (path, payload) = if (PersonalizationSetting.isEnabled(this)) WearSync.PATH_PERSONAL_PROFILE to encoded
        else WearSync.PATH_PERSONAL_PROFILE_DELETE to (runCatching { PersonalCalibrationProfileCodec.decode(encoded).exerciseId }.getOrNull() ?: return)
        emulatorBridge?.send(path, payload)
        PersonalizationSetting.deliver(this, path, payload)
    }

    private fun finishAck(manifest: dev.kyungmin.kcoach.core.WireEnvelope) {
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        prefs.edit().putBoolean("capture:$captureId:acked", true).commit()
        if (captureInbox.analysisSucceeded(captureId)) {
            captureInbox.markState(captureId, CaptureState.ACKED)
            broadcastCaptureState(manifest, CaptureState.ACKED)
        }
        logEvent(KCoachEventMarker.CAPTURE_ACK_SENT, manifest)
        scheduleRecovery()
    }

    private fun broadcastCaptureState(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        state: CaptureState,
    ) {
        sendBroadcast(
            Intent(ACTION_CAPTURE_STATE).setPackage(packageName)
                .putExtra("captureId", manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty())
                .putExtra("sessionId", manifest.sessionId)
                .putExtra("captureState", state.name),
        )
    }

    private fun reportCaptureError(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        error: Throwable,
        stage: String,
    ) {
        Log.e(TAG, "$stage capture failure", error)
        logEvent(KCoachEventMarker.EVENT_FAILED, manifest, "$stage:${error.message}")
        sendBroadcast(
            Intent(ACTION_CAPTURE_ERROR).setPackage(packageName)
                .putExtra("captureId", manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty())
                .putExtra("sessionId", manifest.sessionId)
                .putExtra("stage", stage)
                .putExtra("error", error.message ?: "$stage capture failure"),
        )
    }

    private fun encodeAnalysis(analysis: dev.kyungmin.kcoach.core.SetAnalysisResult): String = listOf(
        analysis.watchDetectedReps ?: "",
        analysis.phoneReanalyzedReps ?: "",
        analysis.confirmedReps ?: "",
        analysis.averageRepDurationMs ?: "",
        analysis.tempoVariance ?: "",
        analysis.amplitudeVariance ?: "",
        analysis.stabilityScore ?: "",
        analysis.consistencyScore ?: "",
        analysis.lateSetDegradation ?: "",
        analysis.averageHeartRate ?: "",
        analysis.peakHeartRate ?: "",
        analysis.analysisConfidence,
        analysis.algorithmVersion,
        analysis.trackingMode.name,
        analysis.trackingProfileId,
        analysis.trackingStatus,
        analysis.capabilityTier.name,
        analysis.repConfidence ?: "",
        analysis.wristMotionSpeed ?: "",
        analysis.wristMotionRange ?: "",
        analysis.tempoConsistency ?: "",
        analysis.signalQuality ?: "",
        analysis.averageAngularSpeed ?: "",
        analysis.relativeRom ?: "",
        analysis.motionMetrics?.let(ObservedMotionMetricsCodec::encode) ?: "",
    ).joinToString("|")

    private fun sendCaptureAck(nodeId: String, sessionId: String, messageId: String, captureId: String) =
        Wearable.getMessageClient(this).sendMessage(
            nodeId,
            WearSync.PATH_ACK_V3,
            WireCodec.encode(WireMessages.ack(WireSource.PHONE, sessionId, messageId, captureId)).toByteArray(),
        )

    private fun handlePayload(path: String, payload: String) {
        val receivedNs = android.os.SystemClock.elapsedRealtimeNanos()
        when (path) {
            WearSync.PATH_WORKOUT_CONTROL -> WatchWorkoutController(this) { outPath, value ->
                PhoneWorkoutService.send(this, outPath, value)
            }.receive(payload, System.currentTimeMillis()).onSuccess { result ->
                if (result.ack.status == dev.kyungmin.kcoach.core.WatchWorkoutControlStatus.APPLIED)
                    PhoneWorkoutService.publishSessionChanged(this)
            }.onFailure { Log.w(EVENT_TAG, "watch workout request retained without receipt", it) }
            WearSync.PATH_PATTERN_PROFILE_ACK -> runCatching {
                val ack = dev.kyungmin.kcoach.core.PatternProfileAck.decode(payload)
                if (PatternTransferOutbox(this).acknowledge(ack).getOrThrow())
                    sendBroadcast(Intent(ACTION_PATTERN_PROFILE).setPackage(packageName).putExtra("profileId", ack.profileId)
                        .putExtra("commandId", ack.commandId).putExtra("status", ack.status.name))
            }.onFailure { Log.w(EVENT_TAG, "profile acknowledgement rejected", it) }
            WearSync.PATH_DIAGNOSTICS -> LocalExitDiagnostics.storeWatchReports(payload)
            WearSync.PATH_CLOCK_REPLY -> runCatching {
                val reply = WireCodec.decode(payload)
                CaptureClockStore(this).acceptReply(reply, receivedNs, PhoneClockEpoch.current(this).id).getOrThrow()
                sendBroadcast(Intent(ACTION_CAPTURE_CLOCK).setPackage(packageName).putExtra("captureId", reply.sessionId))
            }.onFailure { Log.w(EVENT_TAG, "clock reply rejected: ${it.message}") }
            WearSync.PATH_MANUAL_SET -> sessionController.receiveManual(payload, System.currentTimeMillis())
                .onSuccess { result ->
                    if (result.ack.status == dev.kyungmin.kcoach.core.ManualSetStatus.APPLIED) PhoneWorkoutService.publishSessionChanged(this)
                }
                .onFailure { Log.e(EVENT_TAG, "manual set message retained without acknowledgement", it) }
            WearSync.PATH_RECONNECT -> {
                recoverCachedAssets()
                sessionController.resync().onFailure { Log.e(EVENT_TAG, "watch resync failed", it) }
                WatchWorkoutController(this) { outPath, value -> PhoneWorkoutService.send(this, outPath, value) }.sendOffer(System.currentTimeMillis())
                PhoneWorkoutService.retryPending(this)
                runCatching { syncPersonalProfiles() }.onFailure { Log.w(EVENT_TAG, "personal profile transfer retained", it) }
            }
            WearSync.PATH_COMMAND_ACK -> runCatching {
                when (commandOutbox.receiveAck(payload)) {
                    CommandAckResult.RESYNC -> sessionController.resync().onFailure { Log.e(EVENT_TAG, "watch session resync failed", it) }
                    CommandAckResult.BLOCKED -> {
                        val reason = WearSync.parse(payload).getOrNull(3).orEmpty()
                        Log.w(EVENT_TAG, "watch command retained without automatic delivery reason=$reason")
                        sendBroadcast(Intent(ACTION_COMMAND_REJECTED).setPackage(packageName).putExtra("reason", reason))
                    }
                    else -> Unit
                }
            }.onFailure { Log.w(EVENT_TAG, "command acknowledgement rejected; pending command retained", it) }
            WearSync.PATH_CAPTURE_ASSET_EMULATOR -> receiveEmulatorCapture(payload)
            WearSync.PATH_PAIR -> {
                recoverCachedAssets()
                prefs.edit().putLong("pairedAt", System.currentTimeMillis()).apply()
                sessionController.resync().onFailure { Log.e(EVENT_TAG, "paired watch resync failed", it) }
                PhoneWorkoutService.retryPending(this)
                sendBroadcast(Intent(ACTION_PAIR).setPackage(packageName).putExtra("payload", payload))
                runCatching { syncPersonalProfiles() }.onFailure { Log.w(EVENT_TAG, "personal profile transfer retained", it) }
            }
            WearSync.PATH_WATCH_UI_READY -> {
                recoverCachedAssets()
                prefs.edit().putLong("watchUiReadyAt", System.currentTimeMillis()).apply()
                sessionController.resync().onFailure { Log.e(EVENT_TAG, "ready watch resync failed", it) }
                WatchWorkoutController(this) { outPath, value -> PhoneWorkoutService.send(this, outPath, value) }.sendOffer(System.currentTimeMillis())
                PhoneWorkoutService.retryPending(this)
                sendBroadcast(Intent(ACTION_WATCH_UI_READY).setPackage(packageName).putExtra("payload", payload))
                runCatching { syncPersonalProfiles() }.onFailure { Log.w(EVENT_TAG, "personal profile transfer retained", it) }
            }
            WearSync.PATH_STATE, WearSync.PATH_LIVE -> {
                prefs.edit().putString("state", payload).putLong("stateAt", System.currentTimeMillis()).apply()
                if (path == WearSync.PATH_LIVE) {
                    val fields = WearSync.parse(payload)
                    if (fields.getOrNull(9) == "SET_COMPLETE" && !fields.getOrNull(1).orEmpty().startsWith("calibration-")) {
                        sessionController.receiveCompletion(payload, System.currentTimeMillis())
                            .onSuccess { result ->
                                if (result is dev.kyungmin.kcoach.core.SetCompletionResult.Applied) PhoneWorkoutService.publishSessionChanged(this)
                            }
                            .onFailure { Log.e(EVENT_TAG, "background set completion failed", it) }
                    }
                }
                sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("payload", payload))
                if (path == WearSync.PATH_LIVE) {
                    sendBroadcast(Intent(ACTION_LIVE).setPackage(packageName).putExtra("payload", payload))
                }
            }
            WearSync.PATH_HEART_RATE_SAMPLE -> {
                runCatching { WorkoutVitalsStore(this).receive(payload, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "fresh heart sample rejected") }
            }
            WearSync.PATH_SAMPLE -> {
                prefs.edit().putString("lastSample", payload).apply()
                sendBroadcast(Intent(ACTION_SAMPLE).setPackage(packageName).putExtra("payload", payload))
            }
            WearSync.PATH_COMPLETE -> {
                prefs.edit().putString("lastCompletion", payload)
                    .putLong("completionAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_COMPLETE).setPackage(packageName).putExtra("payload", payload))
            }
            WearSync.PATH_CAPTURE_MANIFEST_V3 -> {
                runCatching { WireCodec.decode(payload) }
                    .onSuccess { logEvent(KCoachEventMarker.CAPTURE_MANIFEST_RECEIVED, it) }
                prefs.edit().putString("lastCaptureManifest", payload)
                    .putLong("captureManifestAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_CAPTURE_MANIFEST).setPackage(packageName)
                    .putExtra("payload", payload))
            }
        }
    }

    private fun syncPersonalProfiles() {
        prefs.all
            .filterKeys { it.startsWith("personalProfile:") }
            .values
            .filterIsInstance<String>()
            .forEach(::sendPersonalProfile)
    }

    private fun logEvent(
        marker: KCoachEventMarker,
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        detail: String = "",
    ) {
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                marker = marker,
                sessionId = manifest.sessionId,
                exerciseId = manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty(),
                setIndex = manifest.payload[WirePayloadKeys.SET_INDEX]?.toIntOrNull(),
                captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty(),
                detail = detail,
            ),
        )
    }

    companion object {
        const val ACTION_PATTERN_PROFILE = "dev.kyungmin.kcoach.phone.PATTERN_PROFILE"
        const val ACTION_CAPTURE_CLOCK = "dev.kyungmin.kcoach.phone.CAPTURE_CLOCK"
        const val ACTION_PAIRED_RAW_SAVED = "dev.kyungmin.kcoach.phone.PAIRED_RAW_SAVED"
        private const val TAG = "KCoachCapture"
        private const val CAPTURE_ASSET_TIMEOUT_MS = 15_000L
        private const val EVENT_TAG = "KCoachEvent"
        const val ACTION_PAIR = "dev.kyungmin.kcoach.WEAR_PAIR"
        const val ACTION_COMMAND_REJECTED = "dev.kyungmin.kcoach.WEAR_COMMAND_REJECTED"
        const val ACTION_WATCH_UI_READY = "dev.kyungmin.kcoach.WATCH_UI_READY"
        const val ACTION_STATE = "dev.kyungmin.kcoach.WEAR_STATE"
        const val ACTION_LIVE = "dev.kyungmin.kcoach.WEAR_LIVE"
        const val ACTION_SAMPLE = "dev.kyungmin.kcoach.WEAR_SAMPLE"
        const val ACTION_COMPLETE = "dev.kyungmin.kcoach.WEAR_COMPLETE"
        const val ACTION_CAPTURE_MANIFEST = "dev.kyungmin.kcoach.WEAR_CAPTURE_MANIFEST"
        const val ACTION_CAPTURE_STATE = "dev.kyungmin.kcoach.WEAR_CAPTURE_STATE"
        const val ACTION_CALIBRATION_RAW_SAVED = "dev.kyungmin.kcoach.CALIBRATION_RAW_SAVED"
        const val ACTION_CALIBRATION_RESULT = "dev.kyungmin.kcoach.CALIBRATION_RESULT"
        const val ACTION_CAPTURE = "dev.kyungmin.kcoach.WEAR_CAPTURE"
        const val ACTION_CAPTURE_ERROR = "dev.kyungmin.kcoach.WEAR_CAPTURE_ERROR"
    }
}
