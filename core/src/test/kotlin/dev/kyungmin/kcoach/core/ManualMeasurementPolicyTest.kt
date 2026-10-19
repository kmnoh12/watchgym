package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualMeasurementPolicyTest {
    @Test
    fun aCustomExerciseNameDoesNotGrantAutomaticMeasurementCapability() {
        for (name in listOf("덤벨 프레스", "바벨 로우", "스파이더 컬")) {
            for (id in listOf("custom_123", "imported-unregistered-lift")) {
                val profile = ExerciseTrackingProfiles.resolve(id, name)
                assertEquals(TrackingMode.MANUAL_REQUIRED, profile.mode, "$id: $name")
            }
        }
    }

    @Test
    fun fixedGripBodyMovementStaysManualEvenWhenWatchReportsRepetitions() {
        val document = SensorCaptureDocument(
            SensorCaptureFormat.FORMAT_VERSION, 50.0,
            (1..100).map { i ->
                SensorCaptureRecord(SensorSample(i * 20_000_000L, 0f, 0f, 9.8f, if (i < 50) 2f else -2f, 0f, 0f))
            },
        )
        for (id in listOf("leg_press")) {
            val profile = ExerciseTrackingProfiles.resolve(id)
            assertEquals(TrackingMode.MANUAL_REQUIRED, profile.mode, id)
            val output = PhoneRepAnalyzer.analyze(document, profile, watchDetectedReps = 12)
            assertTrue(output.events.isEmpty(), id)
            assertNull(output.result.watchDetectedReps, id)
            assertNull(output.result.phoneReanalyzedReps, id)
            assertNull(output.result.confirmedReps, id)
            assertEquals(RepSource.MANUAL, output.result.repSource, id)
        }
    }
}
