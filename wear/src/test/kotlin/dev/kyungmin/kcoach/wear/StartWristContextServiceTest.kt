package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import com.google.android.gms.wearable.MessageEvent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartWristContextServiceTest {
    private val declared = StartWristContext("external_rotation", "owner", "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
    private fun command(context: StartWristContext = declared, revision: Long = 1,
        direction: StartDirectionRuntimeProfile? = null): Intent {
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        val base = WearSync.withVerifiedPatternSelection(WearSync.withPatternWearing(
            WearSync.richCommand("START", "session", "external_rotation", "회전", 0, 8, 5.0, 60,
                setId = "set", attemptId = "attempt"), PatternWearingContext("owner", "LEFT", "OUTSIDE")))
        val bound = WearSync.withStartWristContext(base, context)
        val payload = WearSync.parse(direction?.let { WearSync.withStartDirectionProfile(bound, it) } ?: bound)
            .toMutableList().apply { this[10] = revision.toString() }.joinToString("|")
        listener.onMessageReceived(object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = WearSync.PATH_COMMAND
            override fun getSourceNodeId() = "phone"
            override fun getData() = payload.toByteArray()
        })
        return shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    }
    private fun sample(service: WearWorkoutService, accelerationAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH, z: Float = 9.8f) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        val timestamp = SystemClock.elapsedRealtimeNanos()
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
            event.sensor = ShadowSensor.newInstance(type); event.timestamp = timestamp
            event.accuracy = if (type == Sensor.TYPE_ACCELEROMETER) accelerationAccuracy else SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            if (type == Sensor.TYPE_ACCELEROMETER) event.values[2] = z
            service.onSensorChanged(event)
        }
    }
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
    private fun target() = StartDirectionRuntimeProfile("synthetic-direction", 1,
        StartWristDirectionKey(declared, WearClockEpoch.current(RuntimeEnvironment.getApplication()).deviceId),
        "a".repeat(64), "b".repeat(64), StartPoseGateConfig.forProfile(ExerciseTrackingProfiles.resolve("external_rotation"))
            .copy(requireSetupMotion = false, maxSampleGapMs = 60, maxAccelerationAgeMs = 40,
                expectedGravity = StartWristGravityTarget(0.0, 0.0, -1.0, .1)))

    @Test fun aSelectedPersonalCounterDoesNotSkipItsSeparateStartDirectionHold() {
        val app = RuntimeEnvironment.getApplication()
        val direction = target()
        val key = PatternCompatibility("owner", ExerciseMeasurementContracts.resolve(declared.exerciseId).recipe,
            "LEFT", "OUTSIDE", direction.key.deviceId, "watch-native-si-v1")
        val counter = PatternRuntimeProfile("synthetic-counter", 1, "c".repeat(64), key,
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val operation = PatternProfileCommand("synthetic-activate", PatternProfileOperation.ACTIVATE, counter, 0,
            PatternValidationEvidence("d".repeat(64), "e".repeat(64)))
        val profiles = PatternProfileStore(app)
        profiles.stage(operation).getOrThrow()
        val service = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            service.get().onStartCommand(command(direction = direction), 0, 1)
            assertEquals("START_DIRECTION_WAITING", state().getStringExtra("phase"))
            repeat(120) { sample(service.get()) }
            assertEquals("START_DIRECTION_WAITING", state().getStringExtra("phase"))
            assertEquals(PatternProfileStatus.STAGED, profiles.stage(operation).getOrThrow().status)
            repeat(105) { sample(service.get(), z = -9.8f) }
            assertEquals("WORKING_START_CONFIRMED", state().getStringExtra("phase"))
            assertEquals(PatternProfileStatus.APPLIED, profiles.stage(operation).getOrThrow().status)
        } finally { service.destroy() }
    }

    @Test fun targetWaitsOnTheOppositeHoldAndDoesNotJoinPreparationAcrossServiceRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val profile = target()
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command(direction = profile), 0, 1)
        assertEquals("START_DIRECTION_WAITING", state().getStringExtra("phase"))
        repeat(150) { sample(first.get()) }
        assertEquals("START_DIRECTION_WAITING", state().getStringExtra("phase"))
        repeat(50) { sample(first.get(), z = -9.8f) }
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            repeat(60) { sample(restored.get(), z = -9.8f) }
            assertEquals("START_DIRECTION_WAITING", state().getStringExtra("phase"))
            repeat(45) { sample(restored.get(), z = -9.8f) }
            assertEquals("WORKING_START_CONFIRMED", state().getStringExtra("phase"))
            assertEquals(0, state().getIntExtra("reps", -1))
            restored.get().onStartCommand(Intent().putExtra("command", "PAUSE").putExtra("session", "session").putExtra("commandRevision", 2L), 0, 2)
            restored.get().onStartCommand(command(revision = 3, direction = profile.copy(version = 2)), 0, 3)
            assertEquals(2L, app.getSharedPreferences("workout-runtime", 0).getLong("commandRevision", -1))
            restored.get().onStartCommand(Intent().putExtra("command", "RESUME").putExtra("session", "session").putExtra("commandRevision", 4L), 0, 4)
            sample(restored.get(), z = -9.8f)
            restored.get().onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "session").putExtra("commandRevision", 5L), 0, 5)
            val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
            assertEquals("CONFIRMED", manifest.payload["startDirectionStatus"])
            assertEquals(profile, StartDirectionRuntimeCodec.decode(manifest.payload.getValue("startDirectionSnapshot")))
            val document = SensorCaptureFormat.read(app.filesDir.listFiles()!!.single { it.name.endsWith(".kcs") }.inputStream())
            assertEquals(306, document.sampleCount)
            val counting = StartDirectionMeasurementWindow.select(document, manifest.payload.getValue("countingStartNs").toLong())!!
            assertTrue(counting.sampleCount < 10)
        } finally { restored.destroy() }
    }

    @Test fun explicitlyBypassingTheGateUsesTheNextSensorTimestampAndSurvivesPause() {
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            service.get().onStartCommand(command(direction = target()), 0, 1)
            repeat(10) { sample(service.get()) }
            service.get().onStartCommand(Intent().putExtra("command", "START_DIRECTION_BYPASS")
                .putExtra("session", "session").putExtra("setId", "set").putExtra("attemptId", "wrong"), 0, 2)
            assertEquals("START_DIRECTION_WAITING", state().getStringExtra("phase"))
            service.get().onStartCommand(Intent().putExtra("command", "START_DIRECTION_BYPASS")
                .putExtra("session", "session").putExtra("setId", "set").putExtra("attemptId", "attempt"), 0, 3)
            assertEquals("WORKING_START_BYPASSED", state().getStringExtra("phase"))
            sample(service.get())
            val firstCounting = SystemClock.elapsedRealtimeNanos()
            service.get().onStartCommand(Intent().putExtra("command", "PAUSE").putExtra("session", "session").putExtra("commandRevision", 2L), 0, 4)
            service.get().onStartCommand(Intent().putExtra("command", "RESUME").putExtra("session", "session").putExtra("commandRevision", 3L), 0, 5)
            sample(service.get())
            service.get().onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "session").putExtra("commandRevision", 4L), 0, 6)
            val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
            assertEquals("BYPASSED", manifest.payload["startDirectionStatus"])
            assertEquals(firstCounting, manifest.payload.getValue("countingStartNs").toLong())
            assertEquals(0, state().getIntExtra("reps", -1))
        } finally { service.destroy() }
    }
    @Test fun unreliableAccelerationCannotFinishTheCalibrationHoldEvenWithAReliableGyroscope() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(Intent().putExtra("command", "START").putExtra("session", "calibration-quality")
                .putExtra("exerciseId", "external_rotation").putExtra("setId", "set").putExtra("attemptId", "attempt")
                .putExtra("targetReps", 8).putExtra("commandRevision", 1L), 0, 1)
            repeat(160) { sample(controller.get(), SensorManager.SENSOR_STATUS_UNRELIABLE) }
            assertEquals("BASELINE", state().getStringExtra("phase"))
            repeat(90) { sample(controller.get()) }
            assertEquals("BASELINE", state().getStringExtra("phase"))
            repeat(15) { sample(controller.get()) }
            assertEquals("CONTROLLED_REPS", state().getStringExtra("phase"))
            assertEquals(0, state().getIntExtra("reps", -1))
        } finally { controller.destroy() }
    }
    @Test fun actualListenerRuntimeAndRawManifestPreserveDeclarationsAndRejectChangesDuringPause() {
        val app = RuntimeEnvironment.getApplication()
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command(), 0, 1)
        assertEquals("PREPARING", state().getStringExtra("phase"))
        sample(first.get()); sample(first.get())
        first.get().onStartCommand(Intent().putExtra("command", "PAUSE").putExtra("session", "session").putExtra("commandRevision", 2L), 0, 2)
        first.get().onStartCommand(command(declared.copy(deviceTop = "HAND"), 3), 0, 3)
        assertEquals("PAUSED", state().getStringExtra("phase"))
        assertEquals(2L, app.getSharedPreferences("workout-runtime", 0).getLong("commandRevision", -1))
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            restored.get().onStartCommand(Intent().putExtra("command", "RESUME").putExtra("session", "session").putExtra("commandRevision", 4L), 0, 4)
            assertEquals("PREPARING", state().getStringExtra("phase"))
            repeat(135) { sample(restored.get()) } // a still wrist for two seconds starts the measurement and its capture
            restored.get().onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "session").putExtra("commandRevision", 5L), 0, 5)
            val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
            assertEquals(declared, StartWristContextCodec.decode(manifest.payload.getValue("startWristContext")))
            assertEquals(WearClockEpoch.current(app).deviceId, manifest.payload["startWristDeviceId"])
            assertEquals("UNCONFIRMED", manifest.payload["startDirectionStatus"])
            val raw = app.filesDir.listFiles()!!.single { it.name.endsWith(".kcs") }
            assertTrue("Only samples after the still start hold are captured", SensorCaptureFormat.read(raw.inputStream()).sampleCount in 1..80)
        } finally { restored.destroy() }
    }
    @Test fun authoritativeRestSyncDoesNotRequireAWorkingStartContext() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command(), 0, 1)
            controller.get().onStartCommand(Intent().putExtra("command", "SYNC").putExtra("session", "session")
                .putExtra("exerciseId", "external_rotation").putExtra("setId", "set").putExtra("attemptId", "attempt")
                .putExtra("syncPhase", "RESTING").putExtra("restSeconds", 60)
                .putExtra("restEndAtMs", System.currentTimeMillis() + 60_000L).putExtra("commandRevision", 2L), 0, 2)
            assertEquals("RESTING", state().getStringExtra("phase"))
        } finally { controller.destroy() }
    }
}
