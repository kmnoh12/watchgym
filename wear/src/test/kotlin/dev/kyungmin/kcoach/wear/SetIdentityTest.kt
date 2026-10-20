package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetIdentityTest {
    private fun field(s: WearWorkoutService, name: String) = WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(s: WearWorkoutService, name: String, value: Any?) = field(s, name).set(s, value)
    private fun get(s: WearWorkoutService, name: String) = field(s, name).get(s)
    private fun call(s: WearWorkoutService, name: String) = WearWorkoutService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(s)
    private fun service(): WearWorkoutService {
        val s = Robolectric.buildService(WearWorkoutService::class.java).get()
        set(s, "sensorManager", RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        val sink = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { override fun execute(command: Runnable) {} }
        set(s, "outboundExecutor", sink); set(s, "captureTransferExecutor", sink)
        return s
    }
    private fun command(type: String, session: String = "session-new", revision: Long = 1) = Intent()
        .putExtra("command", type).putExtra("session", session).putExtra("exerciseId", "external_rotation")
        .putExtra("exerciseName", "외회전").putExtra("setIndex", 0).putExtra("targetReps", 10)
        .putExtra("restSeconds", 90).putExtra("commandRevision", revision)
    private fun start(s: WearWorkoutService) { s.onStartCommand(command("START"), 0, 1) }

    @Test fun listenerPreservesSetAndAttemptIdsInCommands() {
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        val payload = dev.kyungmin.kcoach.core.WearSync.richCommand("START", "session-new", "external_rotation", "rotation", 0, 10, 5.0, 90,
            setId = "set-a", attemptId = "attempt-a")
        WearCommandService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(listener, dev.kyungmin.kcoach.core.WearSync.PATH_COMMAND, payload)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals("set-a", intent.getStringExtra("setId")); assertEquals("attempt-a", intent.getStringExtra("attemptId"))
    }
    private fun append(s: WearWorkoutService) {
        val add = WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", dev.kyungmin.kcoach.core.SensorCaptureRecord::class.java).apply { isAccessible = true }
        repeat(2) { i -> add.invoke(s, dev.kyungmin.kcoach.core.SensorCaptureRecord(dev.kyungmin.kcoach.core.SensorSample(1000L + i * 20_000_000L, 0f, 0f, 9.8f, 1f, 0f, 0f))) }
    }
    @Test fun changingOnlyTheIndexKeepsRepsAndTheCaptureIdentity() {
        val s = service(); s.onStartCommand(command("START").putExtra("setId", "set-a").putExtra("attemptId", "attempt-a"), 0, 1)
        set(s, "reps", 6); append(s)
        s.onStartCommand(command("SET", revision = 2).putExtra("setId", "set-a").putExtra("attemptId", "attempt-a").putExtra("setIndex", 1), 0, 2)
        assertEquals(6, get(s, "reps")); assertEquals(2, get(s, "captureSampleCount")); assertEquals(1, get(s, "setIndex"))
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(s, true)
        val pending = dev.kyungmin.kcoach.core.WearSync.parse(get(s, "pendingCompletion") as String)
        assertEquals("set-a", pending[17]); assertEquals("attempt-a", pending[18])
        val app = RuntimeEnvironment.getApplication()
        val file = app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }
        val manifest = dev.kyungmin.kcoach.core.WireCodec.decode(file.readText())
        assertEquals("set-a", manifest.setId)
        assertEquals("attempt-a", manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.ATTEMPT_ID])
        assertEquals("1", manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.SET_INDEX])
    }
    @Test fun recoveryRetainsIdsAndAnAckForAnotherAttemptCannotEraseCompletion() {
        val s = service(); s.onStartCommand(command("START").putExtra("setId", "set-a").putExtra("attemptId", "attempt-a"), 0, 1)
        set(s, "reps", 10)
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(s, true)
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals("set-a", get(restored, "setId")); assertEquals("attempt-a", get(restored, "attemptId"))
        val original = get(restored, "pendingCompletion") as String; val f = dev.kyungmin.kcoach.core.WearSync.parse(original)
        val wrong = dev.kyungmin.kcoach.core.WearSync.setAck(f[1], f[2], f[4].toInt(), f[16], "set-a", "attempt-old")
        restored.onStartCommand(Intent().putExtra("command", "SET_ACK").putExtra("payload", wrong), 0, 2)
        assertEquals(original, get(restored, "pendingCompletion"))
        val ack = dev.kyungmin.kcoach.core.WearSync.setAck(f[1], f[2], f[4].toInt(), f[16], "set-a", "attempt-a")
        restored.onStartCommand(Intent().putExtra("command", "SET_ACK").putExtra("payload", ack), 0, 3)
        assertNull(get(restored, "pendingCompletion"))
    }
    @Test fun switchingAttemptsSavesTheOldCaptureBeforeResettingTheCounter() {
        val s = service(); s.onStartCommand(command("START").putExtra("setId", "set-a").putExtra("attemptId", "old"), 0, 1)
        set(s, "reps", 6); append(s)
        s.onStartCommand(command("SET", revision = 2).putExtra("setId", "set-a").putExtra("attemptId", "new"), 0, 2)
        assertEquals(0, get(s, "reps")); assertEquals("new", get(s, "attemptId"))
        val manifest = dev.kyungmin.kcoach.core.WireCodec.decode(RuntimeEnvironment.getApplication().filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
        assertEquals("old", manifest.payload[dev.kyungmin.kcoach.core.WirePayloadKeys.ATTEMPT_ID])
    }
    @Test fun unknownCaptureAckCannotDeleteAnUnownedRawFile() {
        val s = service()
        val file = java.io.File(RuntimeEnvironment.getApplication().filesDir, "unknown.kcs").apply { writeText("original") }
        s.onStartCommand(Intent().putExtra("command", "CAPTURE_ACK").putExtra("captureId", "unknown"), 0, 1)
        assertTrue(file.isFile)
    }
    @Test fun missingLocalRawDoesNotPretendThatThePhoneAcknowledgedIt() {
        val s = service(); val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("capture-transfer", android.content.Context.MODE_PRIVATE)
        // Use the service's actual outbox preferences rather than a replacement store.
        val actual = WearWorkoutService::class.java.getDeclaredField("capturePrefs\$delegate").apply { isAccessible = true }.get(s) as kotlin.Lazy<*>
        val capturePrefs = actual.value as android.content.SharedPreferences
        capturePrefs.edit().putStringSet("pendingCaptureIds", setOf("missing")).commit()
        call(s, "retryPendingCaptures")
        assertTrue(capturePrefs.getStringSet("pendingCaptureIds", emptySet())!!.contains("missing"))
        assertTrue(capturePrefs.getString("ackedCaptureIds", "").orEmpty().isBlank())
    }
    @Test fun explicitNewSessionCanReplaceAnOldUnacknowledgedCompletion() {
        val s = service(); start(s); set(s, "reps", 10); append(s)
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(s, true)
        assertNotNull(get(s, "pendingCompletion"))
        s.onStartCommand(command("START", "next-session", 2).putExtra("setId", "next-set").putExtra("attemptId", "next-attempt"), 0, 2)
        assertEquals("next-session", get(s, "sessionId")); assertNull(get(s, "pendingCompletion"))
        assertTrue(RuntimeEnvironment.getApplication().filesDir.listFiles()!!.any { it.name.endsWith(".kcs") })
    }

    @Test fun failedCaptureFinalizationRetainsTheRuntimeChunkReferences() {
        val s = service(); start(s); append(s); call(s, "checkpoint")
        val chunks = get(s, "captureChunkFiles") as List<*>
        (chunks.single() as java.io.File).writeBytes(byteArrayOf(0))
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(s, true)
        assertEquals(2, get(s, "captureSampleCount"))
        assertEquals(1, (get(s, "captureChunkFiles") as List<*>).size)
        assertNotNull(get(s, "pendingCompletion"))
    }

    @Test fun missingChunkCannotBeFinalizedAsACompleteCaptureOnRecovery() {
        val s = service(); start(s); append(s); call(s, "checkpoint")
        val first = (get(s, "captureChunkFiles") as List<*>).first() as java.io.File
        val append = WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", dev.kyungmin.kcoach.core.SensorCaptureRecord::class.java).apply { isAccessible = true }
        repeat(2) { i -> append.invoke(s, dev.kyungmin.kcoach.core.SensorCaptureRecord(dev.kyungmin.kcoach.core.SensorSample(100_000_000L + i * 20_000_000L, 0f, 0f, 9.8f, 1f, 0f, 0f))) }
        set(s, "pendingCompletion", dev.kyungmin.kcoach.core.WearSync.liveState("session-new", "external_rotation", "rotation", 0, 6, 10, 5.0, 0, "SET_COMPLETE", false, completionId = "receipt"))
        call(s, "checkpoint"); first.delete()
        val restored = service(); call(restored, "restoreRuntime")
        assertFalse("Partial raw must never acquire a valid complete manifest", RuntimeEnvironment.getApplication().filesDir.listFiles()!!.any { it.name.endsWith(".manifest") })
        assertEquals(2, (get(restored, "captureChunkFiles") as List<*>).size)
        assertEquals(false, get(restored, "running"))
    }
    @Test fun corruptChunkRecoveryCannotLeaveTheRuntimePretendingToMeasureNormally() {
        val s = service(); start(s); append(s); call(s, "checkpoint")
        ((get(s, "captureChunkFiles") as List<*>).single() as java.io.File).writeBytes(byteArrayOf(0))
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals(false, get(restored, "running"))
        assertEquals(true, get(restored, "paused"))
        assertEquals(1, (get(restored, "captureChunkFiles") as List<*>).size)
    }

    @Test fun aDurableFinalizedOutboxCaptureRepairsTheCrashWindowAfterChunkRemoval() {
        val s = service(); start(s); append(s)
        val completion = dev.kyungmin.kcoach.core.WearSync.liveState("session-new", "external_rotation", "rotation", 0, 6, 10, 5.0, 0, "SET_COMPLETE", false, completionId = "receipt")
        set(s, "pendingCompletion", completion); call(s, "checkpoint")
        assertNotNull(call(s, "saveCapture"))
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals(0, get(restored, "captureSampleCount"))
        assertNull(get(restored, "captureRecoveryError"))
        assertEquals(completion, get(restored, "pendingCompletion"))
        assertEquals(1, RuntimeEnvironment.getApplication().filesDir.listFiles()!!.count { it.name.endsWith(".manifest") })
    }
    @Test fun aCaptureFromAnotherAttemptCannotSubstituteForMissingChunks() {
        val s = service(); s.onStartCommand(command("START").putExtra("setId", "set").putExtra("attemptId", "old"), 0, 1)
        append(s); call(s, "checkpoint")
        val runtime = RuntimeEnvironment.getApplication().getSharedPreferences("workout-runtime", android.content.Context.MODE_PRIVATE)
        // Finalization persists an empty chunk list and then deletes the chunks, so the crash window is the state saved
        // before finalization: it still references the chunks, which are gone after saveCapture.
        val beforeFinalize = runtime.getString("state", null)!!
        call(s, "saveCapture")
        val saved = org.json.JSONObject(beforeFinalize).put("attemptId", "new")
        runtime.edit().putString("state", saved.toString()).commit()
        val restored = service(); call(restored, "restoreRuntime")
        assertNotNull(get(restored, "captureRecoveryError"))
        assertEquals(false, get(restored, "running"))
        assertEquals(1, (get(restored, "captureChunkFiles") as List<*>).size)
    }
    @Test fun explicitReplacementQuarantinesAnIncompleteOriginalWithoutDeletingItsChunks() {
        val s = service(); start(s); append(s); call(s, "checkpoint")
        val chunk = (get(s, "captureChunkFiles") as List<*>).single() as java.io.File
        chunk.writeBytes(byteArrayOf(0))
        val restored = service(); call(restored, "restoreRuntime")
        restored.onStartCommand(command("START", "next-session", 2).putExtra("setId", "next-set").putExtra("attemptId", "next-attempt"), 0, 2)
        assertEquals("next-session", get(restored, "sessionId"))
        assertTrue(chunk.isFile); assertArrayEquals(byteArrayOf(0), chunk.readBytes())
        val quarantine = RuntimeEnvironment.getApplication().filesDir.listFiles()!!.single { it.name.startsWith("capture-incomplete-") }
        val record = org.json.JSONObject(quarantine.readText())
        assertEquals("INCOMPLETE", record.getString("state")); assertEquals("session-new", record.getString("session"))
        assertEquals(chunk.name, record.getJSONArray("chunks").getString(0))
        assertEquals(2, record.getInt("expectedSamples"))
        assertFalse(RuntimeEnvironment.getApplication().filesDir.listFiles()!!.any { it.name.endsWith(".manifest") })
        call(restored, "writeStorageReport")
        val report = org.json.JSONObject(java.io.File(RuntimeEnvironment.getApplication().filesDir, "capture-storage.json").readText())
        assertEquals(1, report.getInt("incompleteCaptures"))
        assertFalse(report.getBoolean("captureRecoveryRequired"))
    }
    @Test fun repairingAChunkCanResumeWithoutOverwritingThatOriginalChunk() {
        val s = service(); start(s); append(s); call(s, "checkpoint")
        val chunk = (get(s, "captureChunkFiles") as List<*>).single() as java.io.File
        val original = chunk.readBytes(); chunk.delete()
        val restored = service(); call(restored, "restoreRuntime")
        assertNotNull(get(restored, "captureRecoveryError"))
        chunk.writeBytes(original)
        restored.onStartCommand(command("SYNC", revision = 2).putExtra("syncPhase", "WORKING"), 0, 2)
        assertNull(get(restored, "captureRecoveryError")); assertEquals(true, get(restored, "running"))
        val add = WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", dev.kyungmin.kcoach.core.SensorCaptureRecord::class.java).apply { isAccessible = true }
        repeat(2) { i -> add.invoke(restored, dev.kyungmin.kcoach.core.SensorCaptureRecord(dev.kyungmin.kcoach.core.SensorSample(100_000_000L + i * 20_000_000L, 0f, 0f, 9.8f, 1f, 0f, 0f))) }
        call(restored, "checkpoint")
        assertArrayEquals(original, chunk.readBytes())
        assertEquals(2, (get(restored, "captureChunkFiles") as List<*>).size)
        assertEquals(4, get(restored, "captureSampleCount"))
    }

}
