package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kyungmin.kcoach.phone.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class RecordsAndResultMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRecordRowOpensItsSession() {
        var opened: String? = null
        val record = WorkoutRecord("2026-10-20", "가슴·삼두", 81, 26, 10450, "session-1")
        compose.setContent {
            RecordsScreen(listOf(record), YearMonth.of(2026, 10), null,
                RecordsActions(openResult = {}, openRecord = { opened = it.sessionId }, monthChanged = {}))
        }
        compose.onNodeWithText("가슴·삼두").performClick()
        assertEquals("session-1", opened)
    }

    @Test
    fun theResultDotsOpenShareAndSave() {
        compose.setContent {
            WorkoutResultScreen(
                ResultUi("가슴·삼두", "81분 · 26세트 · 세션 00000000", emptyList(), "센서 추정"),
                back = {}, toRecords = {},
            )
        }
        compose.onNodeWithTag("result-menu").performClick()
        compose.onNodeWithText("이미지로 공유").assertExists()
        compose.onNodeWithText("이미지로 저장").assertExists()
    }
}
