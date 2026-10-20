package dev.kyungmin.kcoach.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.FrameLayout
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainResultNavigationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val session = "finished-session"
    private val capture = "late-capture"

    private fun saveFinishedResult(id: String = session) {
        val snapshot = WorkoutSnapshot(sessionId = id, routineId = "routine", exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(PlannedSetSnapshot(0, "dumbbell_curl", 0, 8, 10.0, true,
                exerciseName = "덤벨 컬", performedReps = 8, repSource = SetRepSource.MANUAL)),
            performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
            paused = false, startedAtMs = 1000, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap())
        FinishedWorkoutStore(app).finish(snapshot, "팔 루틴", 30)
        fun encode(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
        app.getSharedPreferences("workout_results", Context.MODE_PRIVATE).edit()
            .putString("last", listOf(encode(id), encode("팔 루틴"), "30", "1", "").joinToString("#")).commit()
    }

    private fun lateAnalysis(activity: MainActivity) {
        val manifest = WireMessages.captureManifest(sessionId = session, exerciseSessionId = "dumbbell_curl",
            setId = "set0", captureId = capture, formatVersion = SensorCaptureFormat.FORMAT_VERSION, compression = "NONE",
            sampleCount = 2, sampleRateRequestedHz = 50.0, sampleRateObservedHz = 50.0, startMonotonicNs = 1000,
            endMonotonicNs = 2000, sha256 = "a".repeat(64), assetSizeBytes = 100, watchDetectedReps = 9,
            exerciseId = "dumbbell_curl", exerciseName = "덤벨 컬", setIndex = 0)
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit()
            .putString(CaptureInbox.manifestKey(capture), WireCodec.encode(manifest)).commit()
        val encoded = MutableList(24) { "" }.apply {
            this[0] = "9"; this[1] = "9"; this[2] = "9"; this[11] = "0.9"
            this[13] = "WRIST_IMU_CAUTION"; this[14] = "generic-wrist"
            this[15] = "REVIEW_REQUIRED"; this[16] = "C_PERSONALIZED_AUTO_COUNT_BETA"
        }.joinToString("|")
        SetAnalysisStore(app).persistAnalysis(manifest, encoded)
        val receiver = field(activity, "wearReceiver") as BroadcastReceiver
        receiver.onReceive(activity, Intent(PhoneWearListenerService.ACTION_CAPTURE)
            .putExtra("captureId", capture).putExtra("sessionId", session))
        val result = requireNotNull(field(activity, "lastWorkoutResult"))
        @Suppress("UNCHECKED_CAST")
        val rows = result.javaClass.getDeclaredField("analyses").apply { isAccessible = true }.get(result) as Map<String, SetAnalysisUi>
        assertTrue("The callback must still retain the late analysis", rows.values.any { it.captureId == capture })
        assertTrue(app.getSharedPreferences("workout_results", Context.MODE_PRIVATE).getString("last", "")!!
            .contains(Base64.encodeToString(capture.toByteArray(), Base64.NO_WRAP)))
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun openResult(activity: MainActivity, result: Any) {
        MainActivity::class.java.getDeclaredMethod("showWorkoutResult", result.javaClass)
            .apply { isAccessible = true }.invoke(activity, result)
    }

    @Test fun lateAnalysisKeepsHomeStatsMenuAndRecordListInPlace() {
        saveFinishedResult()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            for (screen in listOf("showHome", "showStats", "showMenu", "showRecords")) {
                call(activity, screen)
                val tab = field(activity, "currentTab")
                val root = field(activity, "root") as FrameLayout
                val displayed = root.getChildAt(0)
                lateAnalysis(activity)
                assertEquals("Late sensor analysis must not select a result from $screen", tab, field(activity, "currentTab"))
                assertSame("Late sensor analysis must not rebuild $screen", displayed, root.getChildAt(0))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lateAnalysisRefreshesTheMatchingVisibleResult() {
        saveFinishedResult()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            openResult(activity, requireNotNull(field(activity, "lastWorkoutResult")))
            val root = field(activity, "root") as FrameLayout
            val displayed = root.getChildAt(0)
            lateAnalysis(activity)
            assertNotSame(displayed, root.getChildAt(0))
            assertEquals(session, field(activity, "visibleWorkoutResultSessionId"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lateAnalysisDoesNotReplaceADifferentVisibleResult() {
        saveFinishedResult()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            saveFinishedResult("other-finished-session")
            openResult(activity, requireNotNull(call(activity, "restoreLastWorkoutResult")))
            val root = field(activity, "root") as FrameLayout
            val displayed = root.getChildAt(0)
            lateAnalysis(activity)
            assertSame(displayed, root.getChildAt(0))
            assertEquals("other-finished-session", field(activity, "visibleWorkoutResultSessionId"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aRecordRowLoadsTheFinishedResultItBelongsTo() {
        saveFinishedResult()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            val load = MainActivity::class.java.getDeclaredMethod("loadRecordResult", WorkoutRecord::class.java)
                .apply { isAccessible = true }
            fun sessionOf(result: Any?): String? = result?.javaClass
                ?.getDeclaredField("sessionId")?.apply { isAccessible = true }?.get(result) as String?
            val record = WorkoutRecord("1970-01-01", "팔 루틴", 30, 1, 80, session)
            assertEquals(session, sessionOf(load.invoke(activity, record)))
            // Logs written before sessions were stored still resolve through the day and title.
            assertEquals(session, sessionOf(load.invoke(activity, record.copy(sessionId = null))))
        } finally { controller.pause().stop().destroy() }
    }
}
