package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Only the three remaining reference faces, with identical values and a settled animation frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchDesignFinishTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun render(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 432, 432)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { translate(-view.scrollX.toFloat(), -view.scrollY.toFloat()) }
        view.draw(canvas)
        File("build/outputs/watch-preview/finish-$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun renderOnlyOverTargetRestEditorAndAuxiliaryWithTheReferenceValues() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = activity.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "design-finish").putExtra("setId", "set-1").putExtra("attemptId", "attempt")
            .putExtra("exerciseId", "curl").putExtra("exerciseName", "덤벨 컬").putExtra("setIndex", 0)
            .putExtra("paired", true).putExtra("running", true).putExtra("targetReps", 12)
            .putExtra("reps", 13).putExtra("weightKg", 20.0).putExtra("heartRate", 118).putExtra("phase", "WORKING"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        val face = descendants(root).filterIsInstance<WatchWorkoutFace>().single()
        face.clock.text = "02:03"
        render(root, "over-13-of-12")
        assertEquals("13", face.value.text.toString()); assertEquals("목표 초과", face.subtitle.text.toString())
        assertEquals(WatchWorkoutFace.ORANGE, face.value.currentTextColor)
        assertEquals(2, WatchWorkoutFace.zone(118))
        val editor = EditPageView(app).apply {
            applyMode(EditPageView.Mode.REST)
            bindRest("휴식 0:45", "02:05", "방금 1세트", "다음 2세트", 1, "12", "20")
        }
        render(editor, "rest-edit")
        assertEquals(WatchWorkoutFace.SAGE, editor.segNext.currentTextColor)
        assertEquals(WatchWorkoutFace.MUTED, editor.segPrevious.currentTextColor)
        val aux = AuxPageView(app).apply { bind(112, "32:10", 9, "02:05") }
        render(aux, "aux")
        assertEquals("ZONE 2", aux.zoneLabel.text.toString())
        assertEquals("32:10", aux.elapsed.text.toString()); assertEquals("9", aux.sets.text.toString())
        activity.pause().stop().destroy()
    }
}
