package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.WearSync
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneWearBridge(private val context: Context) {
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private val recordOutbox = PhoneRecordOutbox(context)
    private val commandOutbox = PhoneCommandOutbox(context)
    private val queueLock = Any()
    private val pendingMessages = linkedMapOf<String, Pair<String, String>>()
    private var draining = false
    private var closed = false
    private val outboundExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kcoach-phone-wear-outbound").apply { isDaemon = true }
    }

    fun sendHello() {
        sendPath(WearSync.PATH_PAIR, WearSync.hello("phone") + "|WORKOUT_CONTROL_V1")
    }

    fun sendStart(
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
    ) {
        sendPath(
            WearSync.PATH_COMMAND,
            WearSync.richCommand("START", sessionId, exerciseId, exerciseName, setIndex, targetReps, weightKg, restSeconds),
        )
    }

    fun sendSet(
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
    ) {
        sendPath(
            WearSync.PATH_COMMAND,
            WearSync.richCommand("SET", sessionId, exerciseId, exerciseName, setIndex, targetReps, weightKg, restSeconds),
        )
    }

    fun sendRest(sessionId: String, restSeconds: Int, restEndAtMs: Long) {
        sendPath(
            WearSync.PATH_COMMAND,
            WearSync.richCommand("REST", sessionId, "", "휴식", 0, 0, null, restSeconds, restEndAtMs),
        )
    }

    fun sendPause(sessionId: String) = sendLifecycle("PAUSE", sessionId)

    fun sendResume(sessionId: String) = sendLifecycle("RESUME", sessionId)

    fun sendStop(sessionId: String) = sendLifecycle("STOP", sessionId)

    fun sendComplete(sessionId: String) = sendLifecycle("COMPLETE", sessionId)

    fun sendHeartbeat(phoneViewing: Boolean = false) {
        sendPath(WearSync.PATH_HEARTBEAT, WearSync.heartbeat("phone", phoneViewing))
        retryPendingCommand()
    }

    fun retryPendingCommand() {
        runCatching { recordOutbox.pending()?.let { dispatch(WearSync.PATH_SET_RECORD, it) } }
            .onFailure { Log.w("KCoachEvent", "record retry retained", it) }
        runCatching { commandOutbox.pendingForDelivery()?.let { dispatch(WearSync.PATH_COMMAND, it) } }
            .onFailure { Log.w("KCoachEvent", "command retry retained", it) }
        PatternTransferOutbox(context).pending().onSuccess { commands ->
            commands.forEach { command ->
                runCatching { dispatch(WearSync.PATH_PATTERN_PROFILE, command.encode()) }
                    .onFailure { Log.w("KCoachEvent", "profile transfer retained", it) }
            }
        }.onFailure { Log.w("KCoachEvent", "profile transfers retained until recovery", it) }
    }

    fun hasPendingDelivery(): Boolean = synchronized(queueLock) { draining || pendingMessages.isNotEmpty() }

    fun sendRaw(path: String, payload: String) = sendPath(path, payload)

    /** Run after the already-queued STOP drain, rather than dropping it during Service teardown. */
    fun closeAfterPendingDelivery() {
        outboundExecutor.execute(::close)
    }

    fun close() {
        synchronized(queueLock) { closed = true; pendingMessages.clear() }
        outboundExecutor.shutdown()
        emulatorBridge.stop()
    }

    private fun sendLifecycle(type: String, sessionId: String) {
        sendPath(WearSync.PATH_COMMAND, WearSync.command(type, sessionId, "", 0, 0))
    }

    private fun sendPath(path: String, payload: String) {
        if (path == WearSync.PATH_SET_RECORD) {
            runCatching { recordOutbox.enqueue(payload)?.let { dispatch(path, it) } }
                .onFailure {
                    Log.w("KCoachEvent", "record announcement retained; state unchanged", it)
                    PhoneWorkoutService.notifyCommandRejected(context, "OUTBOX")
                }
            return
        }
        if (path != WearSync.PATH_COMMAND) { dispatch(path, payload); return }
        val durable = runCatching {
            commandOutbox.enqueue(WorkoutWearingStore(context).bind(payload).getOrThrow())
        }.getOrElse {
            Log.w("KCoachEvent", "watch command was not frozen; state retained", it)
            PhoneWorkoutService.notifyCommandRejected(context, "BINDING")
            return
        }
        dispatch(path, durable)
    }

    private fun dispatch(path: String, payload: String) {
        val key = runCatching {
            when {
                path == WearSync.PATH_COMMAND && WearSync.parse(payload).getOrNull(1) == "QUERY" -> "$path:query"
                path == WearSync.PATH_PATTERN_PROFILE -> {
                    val profile = dev.kyungmin.kcoach.core.PatternProfileCommand.decode(payload)
                    val channel = if (profile.operation == dev.kyungmin.kcoach.core.PatternProfileOperation.SHADOW) "shadow" else "active"
                    "$path:$channel:${profile.runtime.compatibility}"
                }
                else -> path
            }
        }.getOrElse {
            Log.w("KCoachEvent", "outbound message rejected before queueing path=$path", it)
            return
        }
        synchronized(queueLock) {
            if (closed) return
            pendingMessages[key] = path to payload
            if (draining) return
            draining = true
            outboundExecutor.execute(::drain)
        }
    }

    /** Coalesce repeated heartbeats/desired state while the link is down; never grow an unbounded task backlog. */
    private fun drain() {
        while (true) {
            val message = synchronized(queueLock) {
                val entry = pendingMessages.entries.minByOrNull {
                    when (it.value.first) { WearSync.PATH_SET_ACK -> 0; WearSync.PATH_COMMAND -> 1; else -> 2 }
                }
                if (entry == null || closed) { draining = false; return }
                pendingMessages.remove(entry.key)
                entry.value
            }
            val path = message.first
            val commandType = runCatching { WearSync.parse(message.second).getOrNull(1) }.getOrNull()
            val payload = if (path == WearSync.PATH_COMMAND && commandType != "QUERY") {
                commandOutbox.pendingForDelivery() ?: continue
            } else if (path == WearSync.PATH_PATTERN_PROFILE) {
                val decoded = runCatching { dev.kyungmin.kcoach.core.PatternProfileCommand.decode(message.second) }
                if (decoded.isFailure) {
                    Log.w("KCoachEvent", "stored profile transfer retained", decoded.exceptionOrNull())
                    continue
                }
                val requested = decoded.getOrThrow()
                val current = PatternTransferOutbox(context).pending().getOrNull()
                    ?.singleOrNull { it.commandId == requested.commandId } ?: continue
                if (current != requested) continue
                current.encode()
            } else if (path == WearSync.PATH_SET_RECORD) {
                recordOutbox.pending()?.takeIf { it == message.second } ?: continue
            } else message.second
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS)
                nodes.forEach { node ->
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, path, payload.toByteArray()), 2, TimeUnit.SECONDS)
                }
            }.onFailure { Log.w("KCoachEvent", "watch command delivery deferred path=$path", it) }
            // Deterministic local-emulator path. The host relay is only used
            // for the two emulator instances and never for a real Watch8.
            if (Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true)) emulatorBridge.send(path, payload)
        }
    }
}
