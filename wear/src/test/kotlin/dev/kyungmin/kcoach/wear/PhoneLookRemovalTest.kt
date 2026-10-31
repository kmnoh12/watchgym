package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import dev.kyungmin.kcoach.core.StartPoseGateConfig
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLookRemovalTest {
    @Test fun legacyPhoneViewingReportCannotBlockTheNormalStillStart() = verifyStillStart(false)

    @Test fun legacyPhoneViewingReportCannotBlockStillStartAfterRestEnds() = verifyStillStart(true)

    private fun verifyStillStart(afterRest: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        fun phase() = shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
            .getStringExtra("phase")!!
        fun still() {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            val now = SystemClock.elapsedRealtimeNanos()
            // An installed older version may have left a fresh report in preferences.
            app.getSharedPreferences("phone-viewing", 0).edit()
                .putBoolean("viewing", true).putLong("atElapsedNs", now).apply()
            for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(3)
                event.sensor = ShadowSensor.newInstance(type)
                event.timestamp = now
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                if (type == Sensor.TYPE_ACCELEROMETER) event.values[2] = 9.8f
                service.onSensorChanged(event)
            }
        }
        try {
            service.onStartCommand(Intent().putExtra("command", "START")
                .putExtra("session", "phone-look-removal").putExtra("exerciseId", "barbell_biceps_curl")
                .putExtra("setId", "set-2").putExtra("attemptId", "attempt-2")
                .putExtra("targetReps", 8).putExtra("commandRevision", 1L), 0, 1)
            assertEquals(1_500L, StartPoseGateConfig.WORKOUT_HOLD_MS)
            if (afterRest) {
                service.onStartCommand(Intent().putExtra("command", "REST")
                    .putExtra("session", "phone-look-removal").putExtra("exerciseId", "barbell_biceps_curl")
                    .putExtra("setId", "set-2").putExtra("attemptId", "attempt-2")
                    .putExtra("restEndAtMs", System.currentTimeMillis()).putExtra("commandRevision", 2L), 0, 2)
                val tick = WearWorkoutService::class.java.getDeclaredField("restTicker")
                    .apply { isAccessible = true }.get(service) as Runnable
                tick.run()
                assertEquals("READY", phase())
                // The phone remains the authority that starts the next attempt after REST_COMPLETE.
                service.onStartCommand(Intent().putExtra("command", "START")
                    .putExtra("session", "phone-look-removal").putExtra("exerciseId", "barbell_biceps_curl")
                    .putExtra("setId", "set-3").putExtra("attemptId", "attempt-3")
                    .putExtra("targetReps", 8).putExtra("commandRevision", 3L), 0, 3)
            }
            assertEquals("PREPARING", phase())
            repeat(65) { still() }
            service.onStartCommand(Intent().putExtra("command", "QUERY"), 0, 2)
            assertEquals("PREPARING", phase())
            repeat(15) { still() }
            service.onStartCommand(Intent().putExtra("command", "QUERY"), 0, 3)
            assertTrue(phase().startsWith("WORKING"))
        } finally { controller.destroy() }
    }
}
