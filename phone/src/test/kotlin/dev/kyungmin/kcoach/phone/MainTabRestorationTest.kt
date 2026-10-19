package dev.kyungmin.kcoach.phone

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainTabRestorationTest {
    @Test fun otherRootTabsSurviveActivityRecreation() {
        for ((method, expected) in listOf("showHome" to "HOME", "showRecords" to "RECORDS",
            "showStats" to "STATS", "showMenu" to "MENU")) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                MainActivity::class.java.getDeclaredMethod(method)
                    .apply { isAccessible = true }.invoke(controller.get())
                controller.recreate()
                val tab = MainActivity::class.java.getDeclaredField("currentTab").apply { isAccessible = true }
                assertEquals("Activity recreation must preserve $expected", expected, tab.get(controller.get()).toString())
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun calibrationTabSurvivesActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val exercise = MainActivity::class.java.getDeclaredField("selectedCalibrationExerciseId")
                .apply { isAccessible = true }
            assertNull("Calibration must not preselect external rotation", exercise.get(controller.get()))
            MainActivity::class.java.getDeclaredMethod("showCalibration")
                .apply { isAccessible = true }.invoke(controller.get())
            assertNull("Opening the tab must not pick the first exercise", exercise.get(controller.get()))
            exercise.set(controller.get(), "smith_squat")
            MainActivity::class.java.getDeclaredMethod("showCalibration")
                .apply { isAccessible = true }.invoke(controller.get())
            val tab = MainActivity::class.java.getDeclaredField("currentTab").apply { isAccessible = true }
            assertEquals("CALIBRATION", tab.get(controller.get()).toString())
            controller.recreate()
            assertEquals("Activity recreation must preserve the selected calibration tab",
                "CALIBRATION", tab.get(controller.get()).toString())
            assertEquals("smith_squat", exercise.get(controller.get()))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
