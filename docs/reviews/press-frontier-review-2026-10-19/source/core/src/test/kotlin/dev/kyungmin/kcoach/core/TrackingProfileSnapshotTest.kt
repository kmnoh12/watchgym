package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrackingProfileSnapshotTest {
    @Test fun `rope uses pose counting while frozen press snapshots retain their algorithm`() {
        val current = ExerciseTrackingProfiles.resolve("rope_extension")
        assertEquals(MotionFamily.FLEXION_EXTENSION, current.family)
        assertEquals(TrackingSignal.ROTATION_ENERGY, current.signal)
        for (version in listOf("v3", "v4")) {
            val old = current.copy(family = MotionFamily.PRESS_RETURN, signal = TrackingSignal.COMBINED_MOTION,
                id = "press-return-family-$version")
            assertEquals(old, TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(old)))
        }
        for (id in listOf("cable_pushdown", "flat_bench", "ez_lying_extension")) {
            assertEquals(MotionFamily.PRESS_RETURN, ExerciseTrackingProfiles.resolve(id).family)
        }
        val other = ExerciseTrackingProfiles.resolve("flat_bench")
        assertFailsWith<IllegalArgumentException> {
            TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(other.copy(
                family = MotionFamily.FLEXION_EXTENSION, signal = TrackingSignal.ROTATION_ENERGY,
                id = MotionFamily.FLEXION_EXTENSION.sharedAlgorithmId)))
        }
    }
    @Test fun `old shoulder press snapshots replay old family while new captures use vertical engine`() {
        for (exerciseId in listOf("dumbbell_shoulder_press", "smith_shoulder_press")) {
            val current = ExerciseTrackingProfiles.resolve(exerciseId)
            assertEquals(MotionFamily.VERTICAL_PRESS_RETURN, current.family, exerciseId)
            for (version in listOf("v3", "v4")) {
                val old = current.copy(family = MotionFamily.PRESS_RETURN, signal = TrackingSignal.COMBINED_MOTION,
                    id = "press-return-family-$version")
                assertEquals(old, TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(old)))
            }
        }
    }
    @Test fun `all configured detector settings survive serialization`() {
        ExerciseCapabilityMatrix.standardExerciseIds().forEach { id ->
            val profile = ExerciseTrackingProfiles.resolve(id)
            assertEquals(profile, TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(profile)))
        }
        val personal = ExerciseTrackingProfiles.resolve("barbell_biceps_curl").copy(
            id = "flexion-extension-pose-v4+personal-123", minimumRepMs = 290, maximumRepMs = 8000,
            preferredAxis = 2, preferredDirection = -1, gyroNoiseFloor = 0.12, calibrationProfileVersion = 7,
        )
        assertEquals(personal, TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(personal)))
    }

    @Test fun `unsupported algorithm and invalid timing fail instead of silently using new settings`() {
        val profile = ExerciseTrackingProfiles.resolve("barbell_biceps_curl")
        assertFailsWith<IllegalArgumentException> {
            TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(profile.copy(id = "unknown-version")))
        }
        assertFailsWith<IllegalArgumentException> {
            TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(profile.copy(minimumRepMs = -1)))
        }
    }
    @Test fun `frozen settings cannot turn a wrist-unobservable exercise into automatic counting`() {
        val manual = ExerciseTrackingProfiles.resolve("leg_press")
        val forged = manual.copy(id = MotionFamily.FLEXION_EXTENSION.sharedAlgorithmId,
            family = MotionFamily.FLEXION_EXTENSION, signal = TrackingSignal.ROTATION_ENERGY,
            mode = TrackingMode.WRIST_IMU_CAUTION)
        assertFailsWith<IllegalArgumentException> {
            TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(forged))
        }
    }

    @Test fun `historical lobe snapshots remain explicit and do not masquerade as the new detector`() {
        ExerciseCapabilityMatrix.standardExerciseIds().map { ExerciseTrackingProfiles.resolve(it) }
            .filter { it.family.legacyAlgorithmId != null }.distinctBy { it.family }.forEach { current ->
            val legacy = current.copy(id = requireNotNull(current.family.legacyAlgorithmId) + "+personal-123")
            assertEquals(legacy, TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(legacy)))
            assertEquals(current, TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(current)))
        }
    }

}
