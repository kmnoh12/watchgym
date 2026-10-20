package dev.kyungmin.kcoach.wear

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInstrumentation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WatchActivityBoundaryTest.RejectingServices::class])
class WatchActivityBoundaryTest {
    @Implements(Instrumentation::class)
    class RejectingServices : ShadowInstrumentation() {
        companion object { var reject = false }
        @Implementation
        override fun startService(intent: Intent): ComponentName {
            if (reject) throw IllegalStateException("injected service start rejection")
            return super.startService(intent)
        }
    }
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun notice(activity: MainActivity) = field(activity, "failureNotice").get(activity) as TextView
    private fun state(activity: MainActivity, resting: Boolean = false) {
        val app = activity.application
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("running", !resting).putExtra("resting", resting)
            .putExtra("session", "test").putExtra("exerciseId", "curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("restEndAtMs", System.currentTimeMillis() + 60_000))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun rejectedCreationAndNewIntentStayVisibleUntilFreshStateArrives() {
        RejectingServices.reject = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            assertEquals(View.VISIBLE, notice(activity).visibility)
            controller.newIntent(Intent().putExtra("command", "QUERY"))
            assertEquals(View.VISIBLE, notice(activity).visibility)
            RejectingServices.reject = false
            notice(activity).performClick()
            assertEquals("A sent query alone does not confirm runtime state", View.VISIBLE, notice(activity).visibility)
            state(activity)
            assertEquals(View.GONE, notice(activity).visibility)
        } finally { RejectingServices.reject = false; controller.destroy() }
    }
    @Test fun rejectedPageAndRestCommandsDoNotPretendCountingWasPaused() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            state(activity)
            RejectingServices.reject = true
            val pager = field(activity, "pager").get(activity) as WatchPager
            pager.show(-1)
            assertEquals(View.VISIBLE, notice(activity).visibility)
            assertFalse(field(activity, "countingPausedForEdit").getBoolean(activity))
            state(activity, resting = true)
            pager.show(0)
            val event = android.view.MotionEvent.obtain(0, 1, android.view.MotionEvent.ACTION_SCROLL,
                1, arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(android.view.MotionEvent.PointerCoords().apply {
                    setAxisValue(android.view.MotionEvent.AXIS_SCROLL, -1f)
                }), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_ROTARY_ENCODER, 0)
            try { assertTrue(activity.onGenericMotionEvent(event)) } finally { event.recycle() }
            assertEquals(View.VISIBLE, notice(activity).visibility)
        } finally { RejectingServices.reject = false; controller.destroy() }
    }
    @Test fun rejectedManualSubmitDoesNotLeaveTheSetStuckPending() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            state(activity)
            RejectingServices.reject = true
            MainActivity::class.java.getDeclaredMethod("finishCurrentSet").apply { isAccessible = true }.invoke(activity)
            assertEquals(View.VISIBLE, notice(activity).visibility)
            assertNotEquals("PENDING", field(activity, "manualStatus").get(activity))
        } finally { RejectingServices.reject = false; controller.destroy() }
    }
    @Test fun receiverAndRestTickSurviveCorruptPreferencesAndRecoverWithFreshState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val app = RuntimeEnvironment.getApplication()
            val prefs = app.getSharedPreferences("watch-home", 0)
            prefs.edit().putString("showCompletion", "wrong type").commit()
            app.sendBroadcast(Intent(WatchHomeStore.ACTION_OVERVIEW).setPackage(app.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, notice(activity).visibility)
            prefs.edit().remove("showCompletion").commit()
            notice(activity).visibility = View.GONE
            field(activity, "resting").setBoolean(activity, true)
            val drafts = app.getSharedPreferences("manual-count-drafts", 0)
            drafts.edit().putInt("::", 2).commit()
            (field(activity, "restUiTicker").get(activity) as Runnable).run()
            assertEquals(View.VISIBLE, notice(activity).visibility)
            drafts.edit().clear().commit()
            state(activity)
            assertEquals(View.GONE, notice(activity).visibility)
        } finally { controller.destroy() }
    }
}
