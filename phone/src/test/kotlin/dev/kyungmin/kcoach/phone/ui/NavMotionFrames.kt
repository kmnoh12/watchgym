package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.WorkoutData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Not a pass/fail check: writes one PNG per frame of a press-and-slide on the tab bar, to be stitched into a GIF. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class NavMotionFrames {
    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableIntStateOf(0)
    private var frame = 0

    private fun snap() {
        compose.mainClock.advanceTimeBy(16)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/nav_frames/%03d.png".format(frame++))
    }

    @Test
    fun slideFromHomeToStats() {
        compose.mainClock.autoAdvance = false
        val routines = WorkoutData.defaultRoutines()
        val rows = routines.mapIndexed { i, r -> RoutineRowUi2(r, if (i == 0) null else i * 3) }
        val ui = HomeUi(
            week = listOf(
                WeekDayUi("월", 0.8, false, false), WeekDayUi("화", null, false, false),
                WeekDayUi("수", 0.5, false, false), WeekDayUi("목", null, true, false),
                WeekDayUi("금", null, false, true), WeekDayUi("토", null, false, true), WeekDayUi("일", null, false, true),
            ),
            weekCount = 2, rows = rows, longestRest = rows.lastOrNull(),
        )
        compose.setContent {
            Box(Modifier.fillMaxSize().background(KColors.Bg)) {
                HomeScreen(ui, null, HomeActions({}, {}, {}, {}, {}, {}, {}, {}))
                Box(Modifier.align(Alignment.BottomCenter)) { FloatingTabBar(selected.intValue, true) { selected.intValue = it } }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        repeat(4) { snap() }

        val y = 2400f - 120f
        fun x(tab: Float) = 1080f * (tab + 0.5f) / 5f
        compose.onRoot().performTouchInput { down(Offset(x(0f), y)) }
        repeat(5) { snap() }
        // Slide to tab 2 (통계) over about half a second.
        for (step in 1..16) {
            val tab = 2f * step / 16f
            compose.onRoot().performTouchInput { moveTo(Offset(x(tab), y)) }
            snap()
        }
        repeat(3) { snap() }
        compose.onRoot().performTouchInput { up() }
        repeat(18) { snap() }
    }
}
