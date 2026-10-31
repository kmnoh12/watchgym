package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import dev.kyungmin.kcoach.core.PhoneRepAnalyzer
import dev.kyungmin.kcoach.core.SensorCaptureFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.*
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurlCaptureTest {
    @Test fun slowCurlsCountThroughTheRealServiceAndPersistAllFiveForPhoneReplay() {
        val service = Robolectric.buildService(WearWorkoutService::class.java).get()
        fun field(name: String) = WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("sensorManager").set(service, RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        val sink = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
            override fun execute(command: Runnable) {}
        }
        field("outboundExecutor").set(service, sink)
        field("captureTransferExecutor").set(service, sink)
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "curl-capture")
            .putExtra("exerciseId", "barbell_biceps_curl").putExtra("exerciseName", "바벨 컬")
            .putExtra("setId", "curl-set").putExtra("attemptId", "curl-attempt").putExtra("targetReps", 5)
            .putExtra("commandRevision", 1L), 0, 1)
        var t = 1_000_000_000L
        fun send(angle: Double, rate: Double) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            fun event(type: Int, values: FloatArray) {
                val e = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
                e.sensor = ShadowSensor.newInstance(type); e.timestamp = t; e.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                values.copyInto(e.values); service.onSensorChanged(e)
            }
            event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, (9.80665 * sin(angle)).toFloat(), (9.80665 * cos(angle)).toFloat()))
            event(Sensor.TYPE_GYROSCOPE, floatArrayOf(rate.toFloat(), 0f, 0f))
            t += 20_000_000L
        }
        repeat(3) { send(0.0, 1.2) }
        repeat(160) { send(0.0, 0.0) }
        assertEquals(true, field("measurementReady").get(service))
        val amplitude = 100.0 * PI / 180.0
        repeat(5) {
            for (i in 1..50) { val p = i / 50.0; send(amplitude * (1 - cos(PI * p)) / 2, amplitude * PI * sin(PI * p) / 2) }
            for (i in 1..150) { val p = i / 150.0; send(amplitude * (1 + cos(PI * p)) / 2, -amplitude * PI * sin(PI * p) / 6) }
        }
        repeat(25) { send(0.0, 0.0) }
        assertEquals(5, field("reps").get(service))
        assertEquals("Reaching the target must keep collecting the user's movement", true, field("running").get(service))
        // The set ends after 1.2 s of stillness from the last rep, so stay inside that window before the explicit stop.
        repeat(15) { send(0.0, 0.0) }
        service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "curl-capture")
            .putExtra("commandRevision", 2L), 0, 2)
        assertEquals(false, field("running").get(service))
        val capture = RuntimeEnvironment.getApplication().filesDir.listFiles()!!.single { it.name.endsWith(".kcs") }
        val manifest = dev.kyungmin.kcoach.core.WireCodec.decode(
            java.io.File(capture.parentFile, capture.nameWithoutExtension + ".manifest").readText())
        val frozen = dev.kyungmin.kcoach.core.TrackingProfileSnapshot.decode(
            manifest.payload.getValue(dev.kyungmin.kcoach.core.WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT))
        assertEquals(dev.kyungmin.kcoach.core.ExerciseTrackingProfiles.resolve("barbell_biceps_curl").id, frozen.id)
        val document = SensorCaptureFormat.read(capture.inputStream())
        assertTrue("Sensor-time samples lost during fast callback delivery: ${document.sampleCount}", document.sampleCount >= 950)
        assertEquals((document.endMonotonicNs!! - document.startMonotonicNs!!) / 20_000_000L + 1L, document.sampleCount.toLong())
        assertEquals(emptyList<String>(), document.validateTimestamps())
        assertEquals("The samples after reaching the target are preserved until explicit stop", t - 20_000_000L, document.endMonotonicNs)
        assertEquals(5, PhoneRepAnalyzer.analyze(document, "barbell_biceps_curl").result.phoneReanalyzedReps)
    }
}
