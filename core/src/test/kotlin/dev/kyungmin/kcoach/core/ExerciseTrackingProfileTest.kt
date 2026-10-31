package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExerciseTrackingProfileTest {
    @Test
    fun everyCatalogExerciseResolvesToAnExplicitMotionContract() {
        val ids = ExerciseCapabilityMatrix.standardExerciseIds()
        assertEquals(252, ids.size)

        val profiles = ids.map { exerciseId ->
            ExerciseTrackingProfiles.resolve(exerciseId)
        }
        assertEquals(ids.toSet(), profiles.map { it.exerciseId }.toSet())
        assertEquals(206, profiles.count { it.mode != TrackingMode.MANUAL_REQUIRED })
        assertEquals(46, profiles.count { it.mode == TrackingMode.MANUAL_REQUIRED })
        profiles.filter { it.mode != TrackingMode.MANUAL_REQUIRED }.forEach { profile ->
            assertNotEquals(MotionFamily.UNOBSERVABLE_MANUAL, profile.family)
            assertEquals(profile.family.sharedAlgorithmId, profile.id,
                "Stored algorithm version must describe the detector actually used for ${profile.exerciseId}")
        }
    }

    @Test
    fun sameMotionFamilyUsesOneSharedDetectorConfiguration() {
        ExerciseMotionCatalog.explicitBindings
            .filter { it.family != MotionFamily.UNOBSERVABLE_MANUAL }
            .groupBy { it.family }
            .forEach { (family, bindings) ->
                val fingerprints = bindings.map { binding ->
                    ExerciseTrackingProfiles.resolve(binding.exerciseId).detectorFingerprint()
                }.toSet()
                assertEquals(1, fingerprints.size, family.name)
                assertEquals(family.sharedAlgorithmId, fingerprints.single().algorithmId, family.name)
            }
    }

    @Test
    fun knownBenchEquipmentSharesOneDetectorConfiguration() {
        val flat = ExerciseTrackingProfiles.resolve("flat_bench", "바벨 플랫 벤치 프레스")
        val incline = ExerciseTrackingProfiles.resolve("incline_bench", "바벨 인클라인 벤치 프레스")
        val future = ExerciseTrackingProfiles.resolve("dumbbell_bench", "덤벨 벤치 프레스")

        assertEquals(MotionFamily.TRANSLATION_PRESS_RETURN, future.family)
        assertEquals(TrackingMode.WRIST_IMU_CAUTION, future.mode)
        assertEquals(CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS, future.capabilityTier)
        assertEquals(flat.detectorFingerprint(), incline.detectorFingerprint())
        assertEquals(flat.detectorFingerprint(), future.detectorFingerprint())
    }

    @Test
    fun unregisteredExerciseNamesNeverGrantAutomaticMeasurement() {
        listOf("unregistered_spider_curl" to "덤벨 스파이더 컬", "one_arm_row" to "원암 로우",
            "cable_lateral_raise" to "케이블 레터럴 레이즈", "custom_exercise" to "새 사용자 운동")
            .forEach { (id, name) ->
                assertEquals(TrackingMode.MANUAL_REQUIRED, ExerciseTrackingProfiles.resolve(id, name).mode)
            }
    }

    @Test
    fun machineLowerBodyTermsWinBeforePressAndCurlInference() {
        listOf(
            "hack_leg_press" to "핵 레그 프레스",
            "lying_leg_curl" to "라잉 레그 컬",
        ).forEach { (id, name) ->
            val profile = ExerciseTrackingProfiles.resolve(id, name)
            assertEquals(TrackingMode.MANUAL_REQUIRED, profile.mode, id)
            assertEquals(MotionFamily.UNOBSERVABLE_MANUAL, profile.family, id)
        }
    }

    @Test
    fun lowerBodyWristOnlyExercisesAreNeverConfidentlyAutoCounted() {
        val profile = ExerciseTrackingProfiles.resolve("leg_press", "머신 레그 프레스")
        assertEquals(TrackingMode.MANUAL_REQUIRED, profile.mode)

        val records = listOf(
            SensorCaptureRecord(SensorSample(1L, 0f, 0f, 9.8f, 1f, 0f, 0f)),
            SensorCaptureRecord(SensorSample(2L, 0f, 0f, 9.8f, 2f, 0f, 0f)),
            SensorCaptureRecord(SensorSample(3L, 0f, 0f, 9.8f, 1f, 0f, 0f)),
        )
        val output = PhoneRepAnalyzer.analyze(
            SensorCaptureDocument(SensorCaptureFormat.FORMAT_VERSION, 20.0, records),
            "leg_press",
            "머신 레그 프레스",
            watchDetectedReps = 12,
        )
        assertEquals(RepSource.MANUAL, output.result.repSource)
        assertNull(output.result.watchDetectedReps)
        assertNull(output.result.phoneReanalyzedReps)
        assertNull(output.result.confirmedReps)
        assertEquals(CapabilityTier.D_MANUAL_REQUIRED, output.result.capabilityTier)
        assertEquals(null, output.result.repConfidence)
        assertEquals(null, output.result.wristMotionSpeed)
        assertEquals(null, output.result.wristMotionRange)
        assertEquals(null, output.result.tempoConsistency)
    }

    @Test
    fun pulldownProfileCountsOnlyCompletedPullAndReturnCycles() {
        val records = buildList {
            var timestamp = 0L
            repeat(3) { rep ->
                repeat(20) { index ->
                    add(SensorCaptureRecord(SensorSample(timestamp, 0f, 0f, 9.8f, (0.7f + index / 100f), 0f, 0f), 120 + rep))
                    timestamp += 50_000_000L
                }
                repeat(20) {
                    add(SensorCaptureRecord(SensorSample(timestamp, 0f, 0f, 9.8f, 0f, 0f, 0f), 118))
                    timestamp += 50_000_000L
                }
                repeat(20) { index ->
                    add(SensorCaptureRecord(SensorSample(timestamp, 0f, 0f, 9.8f, -(0.7f + index / 100f), 0f, 0f), 120 + rep))
                    timestamp += 50_000_000L
                }
                repeat(20) {
                    add(SensorCaptureRecord(SensorSample(timestamp, 0f, 0f, 9.8f, 0f, 0f, 0f), 118))
                    timestamp += 50_000_000L
                }
            }
        }
        val output = PhoneRepAnalyzer.analyze(
            SensorCaptureDocument(SensorCaptureFormat.FORMAT_VERSION, 20.0, records),
            "lat_pull_down",
            "랫 풀다운",
            watchDetectedReps = 3,
        )
        assertEquals(3, output.result.phoneReanalyzedReps)
        assertEquals(3, output.events.size)
        assertEquals("pull-return-family-v4", output.result.algorithmVersion)
        assertEquals(CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS, output.result.capabilityTier)
        assertTrue(output.result.signalQuality != null)
    }

    private data class DetectorFingerprint(
        val algorithmId: String,
        val signal: TrackingSignal,
        val minimumRepMs: Long,
        val maximumRepMs: Long,
        val minimumSeparationMs: Long,
        val minimumRelativeRom: Double,
        val minimumFeatureRate: Double,
        val minimumLobeDisplacement: Double,
        val minimumLobeMs: Long,
        val maximumLobeMs: Long,
        val minimumTrajectorySpread: Double,
    )

    private fun ExerciseTrackingProfile.detectorFingerprint() = DetectorFingerprint(
        id, signal, minimumRepMs, maximumRepMs, minimumSeparationMs,
        minimumRelativeRom, minimumFeatureRate, minimumLobeDisplacement,
        minimumLobeMs, maximumLobeMs, minimumTrajectorySpread,
    )
}
