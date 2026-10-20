package dev.kyungmin.kcoach.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneCheckStateTest {
    private fun field(service: WearWorkoutService, name: String) =
        WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun resolve(service: WearWorkoutService, start: Boolean) =
        WearWorkoutService::class.java.getDeclaredMethod("resolvePhoneCheck", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, start)

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
