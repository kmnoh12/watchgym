package dev.kyungmin.kcoach.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WorkoutPhase
import dev.kyungmin.kcoach.core.WorkoutSessionReducer

/** Testable boundary for Android's background foreground-promotion refusal. */
internal fun promoteForegroundSafely(start: () -> Unit): Boolean = try {
    start()
    true
} catch (error: RuntimeException) {
    Log.e("KCoachEvent", "foreground promotion refused; durable work retained", error)
    false
}

/**
 * Owns the phone-to-watch liveness loop independently from MainActivity.
 * The UI may disappear while an active workout continues, so heartbeat must
 * not be tied to Activity.onStart/onStop.
 */
class PhoneWorkoutService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val wearBridge by lazy { PhoneWearBridge(this) }
    private var heartbeatTicker: Runnable? = null
    private var restTicker: Runnable? = null
    private val sessionStore by lazy { SessionStore(this) }
    private val viewingDetector by lazy { PhoneViewingDetector(this) }
    private val sessionController by lazy { PhoneSessionController(sessionStore, wearBridge::sendRaw) }
    private var idleSinceMs = -1L
    private var taskRemoved = false
    private var foregroundReady = false
    // Newest start request this instance has seen; idle stops are only valid against it.
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        // Meet the foreground-start deadline before recovery can touch capture files or locks.
        createNotificationChannel()
        if (!promoteToForeground()) {
            // Background start restrictions can refuse the promotion even after startForegroundService.
            // Durable commands stay in the outbox; a later foreground start retries them.
            stopSelf()
            return
        }
        CalibrationCaptureStore(this).recoverAfterProcessRestart().onSuccess { previous ->
            if (previous != null && sessionStore.loadActive().getOrNull() == null) {
                runCatching { PhoneCommandOutbox(this).enqueue(WearSync.command("STOP", previous.id, "", 0, 0)) }
                    .onFailure { Log.w("KCoachEvent", "camera owner STOP retained; enqueue deferred", it) }
            }
        }.onFailure { Log.w("KCoachEvent", "interrupted camera owner recovery retained", it) }
        wearBridge.sendHello()
        viewingDetector.start()
        startHeartbeat()
        startRestTicker()
        if (PhoneCommandOutbox(this).pending() == null) {
            sessionController.resync().onFailure { Log.e("KCoachEvent", "initial watch resync failed", it) }
        } else wearBridge.retryPendingCommand()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground request can reach this same instance after stopForeground/stopSelf
        // but before onDestroy. onCreate alone cannot acknowledge that new request.
        lastStartId = startId
        if (!promoteToForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        idleSinceMs = -1L
        startHeartbeat()
        startRestTicker()
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_PING) {
            wearBridge.sendHeartbeat(viewingDetector.isViewing())
        }
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_RETRY) wearBridge.retryPendingCommand()
        val path = intent?.getStringExtra(EXTRA_PATH)
        val payload = intent?.getStringExtra(EXTRA_PAYLOAD)
        if (!path.isNullOrBlank() && payload != null) {
            if (intent.getBooleanExtra(EXTRA_DURABLE_COMMAND, false)) wearBridge.retryPendingCommand()
            else runCatching { wearBridge.sendRaw(path, payload) }.onFailure {
                Log.w("KCoachEvent", "foreground command rejected; state retained", it)
                notifyCommandRejected(this, "BINDING")
            }
            if (path == WearSync.PATH_COMMAND) startRestTicker()
        }
        return if (!taskRemoved && hasActiveRuntime()) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        WorkoutTermination(this).stop(saveCompleted = false).onFailure {
            Log.e("KCoachEvent", "Task removal discard deferred; stop intent retained", it)
        }
        CalibrationCaptureStore(this).recoverInterrupted().mapCatching { previous ->
            if (previous != null) PhoneCommandOutbox(this).enqueue(WearSync.command("STOP", previous.id, "", 0, 0))
        }.onFailure { Log.w("KCoachEvent", "Removed camera session recovery deferred", it) }
        // Best effort now; durable outbox survives until reconnect if delivery fails.
        runCatching { wearBridge.retryPendingCommand() }
            .onFailure { Log.w("KCoachEvent", "Removed task STOP delivery deferred", it) }
        heartbeatTicker?.let(handler::removeCallbacks)
        restTicker?.let(handler::removeCallbacks)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundReady = false
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // The persisted session and command outbox remain available for reconnection.
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundReady = false
        stopSelf()
    }

    override fun onDestroy() {
        heartbeatTicker?.let(handler::removeCallbacks)
        heartbeatTicker = null
        restTicker?.let(handler::removeCallbacks)
        restTicker = null
        viewingDetector.stop()
        if (taskRemoved) wearBridge.closeAfterPendingDelivery() else wearBridge.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHeartbeat() {
        heartbeatTicker?.let(handler::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                if (stopIfIdle(SystemClock.elapsedRealtime())) return
                if (hasActiveRuntime()) wearBridge.sendHeartbeat(viewingDetector.isViewing()) else wearBridge.retryPendingCommand()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatTicker = tick
        handler.post(tick)
    }

    private fun startRestTicker() {
        restTicker?.let(handler::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                if (stopIfIdle(SystemClock.elapsedRealtime())) return
                sessionController.advanceRest(System.currentTimeMillis())
                    .onSuccess { if (it != null) publishSessionChanged(this@PhoneWorkoutService) }
                    .onFailure { Log.e("KCoachEvent", "background rest transition failed", it) }
                val snapshot = sessionStore.loadActive().getOrNull()
                val resting = snapshot?.phase == WorkoutPhase.RESTING && !snapshot.paused
                handler.postDelayed(this, if (resting) 250L else HEARTBEAT_INTERVAL_MS)
            }
        }
        restTicker = tick
        handler.post(tick)
    }

    private fun hasActiveRuntime(): Boolean {
        if (taskRemoved || WorkoutTermination(this).pending()) return false
        if (CalibrationCaptureStore(this).active().getOrElse { return true } != null) return true
        val snapshot = sessionStore.loadActive().getOrElse { return true } ?: return false
        LocalExitDiagnostics.workoutPhase(snapshot.phase.name)
        return WorkoutSessionReducer.nextPendingSet(snapshot) != null || snapshot.phase == WorkoutPhase.RESTING || snapshot.paused
    }

    private fun stopIfIdle(nowMs: Long): Boolean {
        if (hasActiveRuntime()) { idleSinceMs = -1L; return false }
        if (idleSinceMs < 0) idleSinceMs = nowMs
        val delivering = PhoneCommandOutbox(this).pendingForDelivery() != null || wearBridge.hasPendingDelivery() ||
            PatternTransferOutbox(this).pending().getOrElse { emptyList() }.isNotEmpty()
        if (delivering && nowMs - idleSinceMs < IDLE_DELIVERY_GRACE_MS) return false
        // A plain stopSelf() would also drop a startForegroundService request that is already queued for this
        // instance, which then never calls startForeground and crashes the app. Stop only if no newer start exists.
        val stopped = if (lastStartId > 0) stopSelfResult(lastStartId) else { stopSelf(); true }
        if (!stopped) {
            idleSinceMs = -1L
            return false
        }
        heartbeatTicker?.let(handler::removeCallbacks)
        restTicker?.let(handler::removeCallbacks)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundReady = false
        return true
    }

    /**
     * Android can refuse a foreground promotion after a background start. The service must not
     * let that exception escape the main looper; durable commands remain in the outbox.
     */
    private fun promoteToForeground(): Boolean {
        if (foregroundReady) return true
        val promoted = promoteForegroundSafely {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (promoted) foregroundReady = true
        return promoted
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "워치짐 운동 세션",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("워치짐")
        .setContentText("워치 연결 상태를 유지하는 중")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val ACTION_PING = "dev.kyungmin.kcoach.phone.PING"
        const val ACTION_RETRY = "dev.kyungmin.kcoach.phone.RETRY_COMMAND"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PATH = "path"
        const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_DURABLE_COMMAND = "durableCommand"
        const val ACTION_SESSION_CHANGED = "dev.kyungmin.kcoach.phone.SESSION_CHANGED"
        private const val CHANNEL_ID = "kcoach_workout"
        private const val NOTIFICATION_ID = 4101
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val IDLE_DELIVERY_GRACE_MS = 30_000L

        fun retryPending(context: android.content.Context) {
            if ((PhoneCommandOutbox(context).pendingForDelivery() == null && PatternTransferOutbox(context).pending().getOrElse { emptyList() }.isEmpty()) ||
                SessionStore(context).loadActive().getOrNull() != null) return
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context,
                    Intent(context, PhoneWorkoutService::class.java).putExtra(EXTRA_ACTION, ACTION_RETRY))
            }.onFailure { Log.w("KCoachEvent", "watch command retry retained until service can start", it) }
        }

        fun publishSessionChanged(context: android.content.Context) {
            context.sendBroadcast(Intent(ACTION_SESSION_CHANGED).setPackage(context.packageName))
        }

        /** Returns false when the command could not be frozen or handed to the service; never throws for those cases. */
        fun send(context: android.content.Context, path: String, payload: String): Boolean {
            val type = runCatching { WearSync.parse(payload).getOrNull(1) }.getOrNull()
            if (path == WearSync.PATH_COMMAND && type == null) {
                Log.w("KCoachEvent", "watch command rejected: invalid payload")
                notifyCommandRejected(context, "INVALID")
                return false
            }
            val durable = path == WearSync.PATH_COMMAND && type != "QUERY"
            val bound: String = if (durable) {
                try {
                    WorkoutWearingStore(context).bind(payload).getOrThrow()
                } catch (error: RuntimeException) {
                    // A set identity frozen to one exercise cannot be reused for a replacement.
                    Log.w("KCoachEvent", "watch command rejected before it reached the service", error)
                    notifyCommandRejected(context, "BINDING")
                    return false
                }
            } else payload
            if (durable) {
                try {
                    PhoneCommandOutbox(context).enqueue(bound)
                } catch (error: RuntimeException) {
                    Log.w("KCoachEvent", "watch command could not be persisted", error)
                    notifyCommandRejected(context, "OUTBOX")
                    return false
                }
            }
            val intent = Intent(context, PhoneWorkoutService::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_PAYLOAD, bound)
                putExtra(EXTRA_DURABLE_COMMAND, durable)
            }
            return runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.w("KCoachEvent", "watch service start deferred; durable commands retained", it)
                    notifyCommandRejected(context, "SERVICE_START")
                }.isSuccess
        }

        internal fun notifyCommandRejected(context: android.content.Context, reason: String) {
            runCatching {
                context.sendBroadcast(Intent(PhoneWearListenerService.ACTION_COMMAND_REJECTED)
                    .setPackage(context.packageName).putExtra("reason", reason))
            }.onFailure { Log.w("KCoachEvent", "rejection notice deferred", it) }
        }
    }
}
