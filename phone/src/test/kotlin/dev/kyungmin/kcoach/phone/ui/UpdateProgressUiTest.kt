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
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class UpdateProgressUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun progressCallbacksDoNotAllowAnotherDownloadAndReadyStateInstallsInsteadOfRechecking() {
        var checks = 0
        var installs = 0
        lateinit var report: (UpdateUi) -> Unit
        compose.setContent { MenuScreen("test", MenuActions({ checks++; report = it }, { installs++; null }, {})) }
        compose.onNodeWithText("업데이트 확인").performScrollTo().performClick()
        compose.runOnIdle { report(UpdateUi("manifest 확인 중", false, busy = true)) }
        compose.onNodeWithTag("updateAction").performTouchInput { click() }
        compose.runOnIdle { report(UpdateUi("APK 다운로드 중", false, busy = true)) }
        compose.onNodeWithTag("updateAction").performTouchInput { click() }
        assertEquals(1, checks)
        compose.runOnIdle { report(UpdateUi("다운로드 완료", true)) }
        compose.onNodeWithText("설치").performClick()
        assertEquals(1, installs)
        assertEquals(1, checks)
    }
    @Test fun failedCheckAllowsAnExplicitRetry() {
        var checks = 0
        lateinit var report: (UpdateUi) -> Unit
        compose.setContent { MenuScreen("test", MenuActions({ checks++; report = it }, { null }, {})) }
        compose.onNodeWithText("업데이트 확인").performScrollTo().performClick()
        compose.runOnIdle { report(UpdateUi("연결 실패", false)) }
        compose.onNodeWithText("업데이트 확인").performClick()
        assertEquals(2, checks)
    }
}
