package dev.kyungmin.kcoach.wear

import android.os.SystemClock
import dev.kyungmin.kcoach.core.SensorSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneCheckStateTest {
    private fun field(service: WearWorkoutService, name: String) =
        WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun resolve(service: WearWorkoutService, start: Boolean) =
        WearWorkoutService::class.java.getDeclaredMethod("resolvePhoneCheck", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, start)

    private fun refresh(service: WearWorkoutService, sample: SensorSample) =
        WearWorkoutService::class.java.getDeclaredMethod("refreshPhoneCheck", SensorSample::class.java)
            .apply { isAccessible = true }.invoke(service, sample)

    private fun moved(now: Long) = SensorSample(now, 0f, 0f, 9.80665f, 3f, 0f, 0f)

    @Test fun wristMovementDoesNotDismissTheOpenQuestion() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get()
            val now = SystemClock.elapsedRealtimeNanos()
            PhoneViewingStore(app).update(true, now)
            field(service, "phoneCheckPending").setBoolean(service, true)
            refresh(service, moved(now))
            assertTrue("Raising the wrist to read the question must not dismiss it",
                field(service, "phoneCheckPending").getBoolean(service))
            assertFalse(field(service, "phoneCheckResting").getBoolean(service))
        } finally { controller.destroy() }
    }

    @Test fun phonePutDownEndsTheOpenQuestion() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get()
            val now = SystemClock.elapsedRealtimeNanos()
            PhoneViewingStore(app).update(true, now)
            field(service, "phoneCheckPending").setBoolean(service, true)
            PhoneViewingStore(app).update(false, now)
            refresh(service, SensorSample(now, 0f, 0f, 9.80665f, 0f, 0f, 0f))
            assertFalse(field(service, "phoneCheckPending").getBoolean(service))
        } finally { controller.destroy() }
    }

    @Test fun restingAnswerYieldsToMovement() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get()
            val now = SystemClock.elapsedRealtimeNanos()
            PhoneViewingStore(app).update(true, now)
            field(service, "phoneCheckResting").setBoolean(service, true)
            refresh(service, moved(now))
            assertFalse("After 'resting' the next lift starts the set", field(service, "phoneCheckResting").getBoolean(service))
        } finally { controller.destroy() }
    }

    @Test fun startReturnsToPreparationWhileRestKeepsWaitingForMovementOrPhoneClear() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            val service = controller.get()
            field(service, "phoneCheckPending").setBoolean(service, true)
            resolve(service, true)
            assertFalse(field(service, "phoneCheckPending").getBoolean(service))
            assertFalse(field(service, "phoneCheckResting").getBoolean(service))
            assertTrue(field(service, "phoneCheckSuppressedUntilNs").getLong(service) > 0L)

            field(service, "phoneCheckPending").setBoolean(service, true)
            resolve(service, false)
            assertFalse(field(service, "phoneCheckPending").getBoolean(service))
            assertTrue(field(service, "phoneCheckResting").getBoolean(service))
        } finally { controller.destroy() }
    }
}
