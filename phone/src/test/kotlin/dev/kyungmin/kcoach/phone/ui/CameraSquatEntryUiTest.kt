package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class CameraSquatEntryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun wristManualPolicyDoesNotDisableTheSeparateFullBodyCameraEntry() {
        var opened = false
        compose.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val exercise = dev.kyungmin.kcoach.phone.ExerciseDefinition("squat", "스쿼트", "하체", "하체", "맨몸")
            CalibrationScreen(
                CalibrationUi(listOf(LibraryEntry(exercise, 0, "스쿼트", 0)), "전신을 촬영해요",
                    mapOf("squat" to "전신 촬영"), setOf("squat")),
                { false }, { CalibrationButtonUi("보정 종료·저장", true) },
                CalibrationActions({}, {}, { opened = true }), { android.view.View(context) },
            )
        }
        compose.onNodeWithText("스쿼트", useUnmergedTree = true).performClick()
        assertTrue(opened)
    }
}
