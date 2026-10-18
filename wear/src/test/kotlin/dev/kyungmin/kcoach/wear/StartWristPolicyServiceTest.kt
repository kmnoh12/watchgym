package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import java.time.Duration
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import dev.kyungmin.kcoach.core.ExerciseMotionCatalog
import dev.kyungmin.kcoach.core.StartWristCandidate
import dev.kyungmin.kcoach.core.StartWristOrientationPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartWristPolicyServiceTest {
    private fun command(operation: String, exercise: String, revision: Long = 1L) = Intent()
        .putExtra("command", operation).putExtra("session", "explicit-session")
        .putExtra("exerciseId", exercise).putExtra("setId", "set-$exercise").putExtra("attemptId", "attempt-$exercise")
        .putExtra("targetReps", 8).putExtra("commandRevision", revision)
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        .last { it.action == WearWorkoutService.ACTION_STATE }

    @Test fun everyDeferredExerciseWaitsForTheStillStartHoldAfterTheExplicitCommand() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            var revision = 1L
            val deferred = ExerciseMotionCatalog.explicitBindings.filter {
                StartWristOrientationPolicy.candidate(it.exerciseId) == StartWristCandidate.DEFERRED
            }
            assertEquals(33, deferred.size)
            for (exercise in deferred) {
                controller.get().onStartCommand(command("START", exercise.exerciseId, revision++), 0, 1)
                assertEquals(exercise.exerciseId, state().getStringExtra("exerciseId"))
                assertEquals("PREPARING", state().getStringExtra("phase"))
                assertTrue(state().getBooleanExtra("running", false))
                assertEquals(0, state().getIntExtra("reps", -1))
            }
        } finally { controller.destroy() }
    }

    private fun still(service: WearWorkoutService, gyro: Float = 0f) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        val timestamp = SystemClock.elapsedRealtimeNanos()
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
            event.sensor = ShadowSensor.newInstance(type); event.timestamp = timestamp; event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            if (type == Sensor.TYPE_ACCELEROMETER) event.values[2] = 9.8f else event.values[0] = gyro
            service.onSensorChanged(event)
        }
    }

    @Test fun measuringStartsOnlyAfterTwoAndAHalfStillSecondsAndAShakeRestartsTheHold() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command("START", "barbell_biceps_curl"), 0, 1)
            assertEquals("PREPARING", state().getStringExtra("phase"))
            repeat(115) { still(controller.get()) }
            assertEquals("2.3 s of stillness is not enough", "PREPARING", state().getStringExtra("phase"))
            still(controller.get(), gyro = 1.5f)
            repeat(115) { still(controller.get()) }
            controller.get().onStartCommand(Intent().putExtra("command", "QUERY"), 0, 2)
            assertEquals("a shake restarts the two and a half seconds", "PREPARING", state().getStringExtra("phase"))
            repeat(20) { still(controller.get()) }
            controller.get().onStartCommand(Intent().putExtra("command", "QUERY"), 0, 3)
            assertTrue(state().getStringExtra("phase")!!.startsWith("WORKING"))
        } finally { controller.destroy() }
    }

    @Test fun candidateWithoutAReviewedDirectionStaysUnconfirmedThroughPauseAndServiceRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command("START", "external_rotation"), 0, 1)
        assertEquals("PREPARING", state().getStringExtra("phase"))
        first.get().onStartCommand(command("PAUSE", "external_rotation", 2L), 0, 2)
        assertEquals("PAUSED", state().getStringExtra("phase"))
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            restored.get().onStartCommand(command("RESUME", "external_rotation", 3L), 0, 3)
            assertEquals("PREPARING", state().getStringExtra("phase"))
            assertEquals(0, state().getIntExtra("reps", -1))
            assertNotNull(app.getSharedPreferences("workout-runtime", 0).getString("state", null))
        } finally { restored.destroy() }
    }

    @Test fun manualExercisesNeverAcquireAnAutomaticPostureVerdict() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command("START", "leg_press"), 0, 1)
            assertEquals("MANUAL_REQUIRED", state().getStringExtra("phase"))
            assertEquals(0, state().getIntExtra("reps", -1))
        } finally { controller.destroy() }
    }
}
