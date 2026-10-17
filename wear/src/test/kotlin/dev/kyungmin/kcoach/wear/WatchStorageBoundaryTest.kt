package dev.kyungmin.kcoach.wear

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.SensorManager
import dev.kyungmin.kcoach.core.SensorCaptureFormat
import dev.kyungmin.kcoach.core.SensorCaptureRecord
import dev.kyungmin.kcoach.core.SensorSample
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchStorageBoundaryTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private fun field(name: String) = WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(s: WearWorkoutService, name: String): Any? = WearWorkoutService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(s)
    private fun service(): WearWorkoutService {
        val s = Robolectric.buildService(WearWorkoutService::class.java).get()
        field("sensorManager").set(s, app.getSystemService(SensorManager::class.java))
        val sink = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
            override fun execute(command: Runnable) = Unit
        }
        field("outboundExecutor").set(s, sink); field("captureTransferExecutor").set(s, sink)
        return s
    }
    private fun command(type: String, revision: Long) = Intent().putExtra("command", type)
        .putExtra("session", "original-session").putExtra("exerciseId", "external_rotation")
        .putExtra("exerciseName", "외회전").putExtra("setId", "original-set").putExtra("attemptId", "original-attempt")
        .putExtra("setIndex", 0).putExtra("targetReps", 10).putExtra("commandRevision", revision)
    private fun append(s: WearWorkoutService) {
        WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", SensorCaptureRecord::class.java)
            .apply { isAccessible = true }.invoke(s, SensorCaptureRecord(SensorSample(1_000_000_000L, 0f, 0f, 9.8f, 0f, 0f, 0f)))
        field("lastCaptureTimestampNs").set(s, 1_000_000_000L)
    }
    private fun failCommits(s: WearWorkoutService) {
        val real = app.getSharedPreferences("workout-runtime", 0)
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") method.invoke(real, *(args ?: emptyArray())) else {
                val editor = real.edit()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
                    if (edit.name == "commit") false else {
                        val result = edit.invoke(editor, *(values ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                }
            }
        } as SharedPreferences
        field("runtimePrefs\$delegate").set(s, lazyOf(prefs))
    }
    @Test fun chunkWritePlusFailedCheckpointReturnsFailureAndRestoresTheOriginalIdentity() {
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); append(s); failCommits(s)
        assertEquals(false, call(s, "flushCaptureChunk"))
        val journal = File(app.filesDir, "capture-checkpoint.json")
        assertTrue(journal.isFile)
        val chunks = field("captureChunkFiles").get(s) as List<*>
        assertEquals(1, chunks.size)
        assertEquals(1, SensorCaptureFormat.read((chunks.single() as File).inputStream()).sampleCount)
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals("original-session", field("sessionId").get(restored))
        assertEquals("original-set", field("setId").get(restored))
        assertEquals("original-attempt", field("attemptId").get(restored))
        assertEquals(1, field("captureSampleCount").get(restored))
    }
    @Test fun failedCommandCheckpointCannotAdvanceTheAppliedRevision() {
        val s = service(); s.onStartCommand(command("START", 1), 0, 1)
        val applied = field("lastAppliedCommand").get(s)
        failCommits(s); s.onStartCommand(command("PAUSE", 2), 0, 2)
        assertEquals(1L, field("lastCommandRevision").get(s))
        assertEquals(applied, field("lastAppliedCommand").get(s))
        assertEquals(false, field("running").get(s)); assertEquals(true, field("paused").get(s))
    }
    @Test fun aFailedChunkWriteRetainsTheInMemoryOriginalUntilTheSameCheckpointIsRetried() {
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); append(s)
        val obstruction = File(app.filesDir, "capture-checkpoint.json.incoming").apply { mkdirs() }
        assertEquals(false, call(s, "flushCaptureChunk"))
        assertEquals(1, (field("captureRecords").get(s) as List<*>).size)
        assertEquals(0, (field("captureChunkFiles").get(s) as List<*>).size)
        assertTrue(obstruction.delete()); assertEquals(true, call(s, "flushCaptureChunk"))
        assertEquals(1, field("captureSampleCount").get(s))
    }
    @Test fun failedStartupCleanupDoesNotOverwriteTheOriginalRecoverableState() {
        val original = service(); original.onStartCommand(command("START", 1), 0, 1); append(original)
        assertEquals(true, call(original, "checkpoint"))
        val prefs = app.getSharedPreferences("workout-runtime", 0)
        val saved = prefs.getString("state", null)
        val failed = service(); field("startupFailed").set(failed, true)
        failed.onDestroy()
        assertEquals(saved, prefs.getString("state", null))
    }
    @Test fun malformedManualInputIsNotAppliedAndDoesNotInterruptAnUnchangedWorkingSet() {
        val s = service(); s.onStartCommand(command("START", 1), 0, 1)
        s.onStartCommand(Intent().putExtra("command", "MANUAL_SUBMIT").putExtra("payload", "invalid"), 0, 2)
        assertEquals(true, field("running").get(s))
        assertEquals("original-set", field("setId").get(s))
        assertEquals(1L, field("lastCommandRevision").get(s))
    }
    @Test fun failedFinalizationKeepsItsCaptureIdForRetry() {
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); append(s)
        assertEquals(true, call(s, "checkpoint")); failCommits(s)
        assertNull(call(s, "saveCapture"))
        val id = field("finalizingCaptureId").get(s) as String
        assertEquals(1, (field("captureChunkFiles").get(s) as List<*>).size)
        field("runtimePrefs\$delegate").set(s, lazyOf(app.getSharedPreferences("workout-runtime", 0)))
        assertEquals(true, WearWorkoutService::class.java.getDeclaredMethod("recoverCaptureChunks", Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(s, 1))
        assertNotNull(call(s, "saveCapture"))
        assertTrue(File(app.filesDir, "$id.kcs").isFile)
        assertTrue(File(app.filesDir, "$id.manifest").isFile)
        assertEquals(id, field("finalizingCaptureId").get(s))
    }

}
