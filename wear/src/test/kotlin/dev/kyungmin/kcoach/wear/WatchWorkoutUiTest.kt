package dev.kyungmin.kcoach.wear

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchWorkoutUiTest {
    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    private fun dispatched(): Intent {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (true) {
            val intent = app.nextStartedService ?: error("No workout request dispatched")
            if (intent.getStringExtra("command") == "WORKOUT_CONTROL_SUBMIT") return intent
        }
    }
    @Test fun preparedRoutineNeverAddsACompetingWatchStartButton() {
        val app = RuntimeEnvironment.getApplication()
        val offer = WatchWorkoutOffer("session", "수동 루틴", "레그 프레스", "set", "attempt", "a".repeat(64), Long.MAX_VALUE)
        WatchWorkoutOutbox(app).receiveOffer(offer.encode())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(texts(controller.get().window.decorView).any { it.text.contains("워치에서 루틴 시작") || it.text == "운동 시작" })
        assertTrue(WatchWorkoutOutbox(app).pending().isEmpty())
        assertTrue(texts(controller.get().window.decorView).any { it.text == "이번 주 운동" })
        assertFalse(texts(controller.get().window.decorView).any { it.text.contains("스마트폰 연결 대기") })
        controller.pause().stop().destroy()
    }
    @Test fun phoneSuspicionShowsTheRestOrStartChoice() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "session").putExtra("setId", "set-2").putExtra("attemptId", "attempt-2")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("exerciseName", "덤벨 컬")
            .putExtra("running", true).putExtra("resting", false).putExtra("phase", "PHONE_CHECK")
            .putExtra("reps", 0).putExtra("targetReps", 10))
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertEquals("아직 휴식 중인가요?", shadowOf(dialog!!).title)
        controller.pause().stop().destroy()
    }

    @Test fun previousCountIsNotShownBetweenRestCompletionAndTheNextSet() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "session").putExtra("setId", "set-2").putExtra("attemptId", "attempt-2")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("exerciseName", "덤벨 컬")
            .putExtra("running", false).putExtra("resting", false).putExtra("phase", "READY")
            .putExtra("reps", 12).putExtra("targetReps", 10))
        shadowOf(Looper.getMainLooper()).idle()
        val values = texts(activity.window.decorView).map { it.text.toString() }
        assertFalse("The finished set's 12 must not appear as the next set's count", values.contains("12"))
        assertTrue(values.contains("준비"))
        controller.pause().stop().destroy()
    }

    @Test fun nextButtonCarriesTheDisplayedNextSetIdentityAndDoesNotInventACompletionCount() {
        val app = RuntimeEnvironment.getApplication()
        WatchWorkoutOutbox(app).receivePhoneHello(listOf("1", "phone", "0", "WORKOUT_CONTROL_V1"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "session").putExtra("setId", "next-set").putExtra("attemptId", "next-attempt")
            .putExtra("exerciseId", "leg_press").putExtra("exerciseName", "레그 프레스")
            .putExtra("running", false).putExtra("resting", true).putExtra("phase", "RESTING"))
        shadowOf(Looper.getMainLooper()).idle()
        fun pills(view: android.view.View): List<RestPill> = (if (view is RestPill) listOf(view) else emptyList()) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { pills(view.getChildAt(it)) } else emptyList()
        // The skip button on the rest screen carries the next set's identity.
        pills(activity.window.decorView).single { it.contentDescription == "건너뛰기" }.performClick()
        val command = WatchWorkoutControl.decode(dispatched().getStringExtra("payload")!!)
        assertEquals(WatchWorkoutAction.NEXT_SET, command.action)
        assertEquals("next-set", command.setId)
        assertEquals("next-attempt", command.attemptId)
        assertNull(command.planHash)
        assertFalse(WireCodec.decode(command.encode()).payload.containsKey("performedReps"))
        controller.pause().stop().destroy()
    }
}
