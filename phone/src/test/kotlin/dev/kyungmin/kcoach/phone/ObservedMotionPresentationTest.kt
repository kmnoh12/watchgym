package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservedMotionPresentationTest {
    private fun metrics() = ObservedMotionMetrics("GENERIC_WRIST_CANDIDATE", listOf(
        ObservedRepMotion(0, 300_000_000, 1_000_000_000, 300.0, 700.0, null, null, null, 2.0, 2.0)))
    @Test fun mixedSignalExerciseDisplaysOnlyMeasuredGyroUnitsAndUnknownPauseStaysHidden() {
        val analysis = SetAnalysisUi("lat_pull_down", "랫풀다운", 0, 1, 1, null, .8, motionMetrics = metrics())
        val rows = analysis.resultMetricRows()
        assertTrue(rows.contains("시작→전환 평균" to "0.30초"))
        assertTrue(rows.contains("전환→복귀 평균" to "0.70초"))
        assertTrue(rows.contains("손목 관측 속도" to "2.00 rad/s"))
        assertFalse(rows.any { it.first.contains("정지") || it.first.contains("ROM") || it.first.contains("자세 정확도") })
        assertFalse(analysis.copy(trackingMode = "MANUAL_REQUIRED").resultMetricRows().any { it.first.contains("평균") || it.first == "손목 관측 속도" })
        assertFalse(analysis.copy(motionMetrics = null).resultMetricRows().any { it.first == "손목 관측 속도" })
    }
    @Test fun reviewedWristComparisonHasUnitsSampleCountAndNeverClaimsJointRange() {
        val comparison = WristMotionComparison("a".repeat(64), 6, 1.5, .2, .75, listOf(.75))
        val analysis = SetAnalysisUi("dumbbell_curl", "컬", 0, 1, 1, 1, .9,
            motionMetrics = metrics().copy(source = "PERSONAL_WRIST_CANDIDATE", wristMotionComparison = comparison))
        val rows = analysis.resultMetricRows()
        assertTrue(rows.contains("개인 기준 대비 손목 회전 움직임량" to "75.0% · 현재 반복 중간값"))
        assertTrue(rows.any { it.first == "개인 손목 기준" && it.second.contains("확인한 6회") && it.second.contains("1.50 rad") })
        assertFalse(rows.any { it.first.contains("관절") || it.first.contains("ROM") || it.first.contains("자세 정확도") })
        assertFalse(analysis.copy(motionMetrics = metrics()).resultMetricRows().any { it.first == "개인 기준 대비 손목 회전 움직임량" })
    }
    @Test fun cameraPresentationRequiresObservedValuesAndMakesTheirPlaneAndUnitsExplicit() {
        val metric = CameraObservedMetrics(true, false, listOf(CameraObservedCycle(0, 1_000_000_000, 20,
            45.0, 100.0, 3.0, .1, null)))
        val text = cameraObservedMetricsText(metric)
        assertTrue(text.contains("왼팔"))
        assertTrue(text.contains("45.0~100.0°"))
        assertTrue(text.contains("영상평면"))
        assertTrue(text.contains("어깨 폭 기준"))
        assertFalse(text.contains("양팔 추정각 차이"))
        assertTrue(cameraObservedMetricsText(null).contains("산출 불가"))
        assertFalse(cameraObservedMetricsText(null).contains("0°"))
    }
    @Test fun localResultMetricsSurviveStoreRestartAndOldTwentyFourFieldsStillRead() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val manifest = WireMessages.captureManifest(sessionId = "metrics-session", exerciseSessionId = "lat_pull_down",
            setId = "metrics-set", captureId = "metrics-capture", formatVersion = 2, compression = "none", sampleCount = 10,
            sampleRateRequestedHz = 50.0, sampleRateObservedHz = 50.0, startMonotonicNs = 0, endMonotonicNs = 1_000_000_000,
            sha256 = "0".repeat(64), assetSizeBytes = 100, exerciseId = "lat_pull_down", setIndex = 0)
        prefs.edit().putString(CaptureInbox.manifestKey("metrics-capture"), WireCodec.encode(manifest)).commit()
        val fields = MutableList(24) { "" }.apply {
            this[0] = "1"; this[1] = "1"; this[11] = ".8"; this[13] = "WRIST_IMU_CAUTION"
            this[14] = "generic"; this[16] = "C_PERSONALIZED_AUTO_COUNT_BETA"
        }
        val encoded = (fields + ObservedMotionMetricsCodec.encode(metrics())).joinToString("|")
        SetAnalysisStore(app).persistAnalysis(manifest, encoded)
        assertEquals(metrics(), SetAnalysisStore(app).loadForSession("metrics-session").values.single().motionMetrics)
        SetAnalysisStore(app).persistAnalysis(manifest, fields.joinToString("|"))
        assertNull(SetAnalysisStore(app).loadForSession("metrics-session").values.single().motionMetrics)
    }
}
