package dev.kyungmin.kcoach.core

import kotlin.test.*
import kotlin.math.cos
import kotlin.math.sin

class CameraCaptureGuideTest {
    @Test fun longReadyHoldDoesNotBecomeTheFirstRepetitionOrDiscardTheActualMovement() {
        val ready = (1000L..16000L step 200).map { frame(it, 160.0) }
        val motion = listOf(frame(16200, 120.0), frame(16400, 60.0), frame(16600, 100.0), frame(16800, 160.0))
        val proposed = CameraRepProposals.propose(ready + motion, "dumbbell_curl", true)
        assertEquals(1, proposed.size)
        assertEquals(16000_000_000L, proposed.single().startNs)
        assertEquals(16400_000_000L, proposed.single().turnaroundNs)
        assertEquals(16800_000_000L, proposed.single().endNs)
    }
    private fun frame(timeMs: Long, angle: Double, visible: Boolean = true): CameraPoseFrame {
        val image = List(33) { CameraLandmark(0.5, 0.5, 0.0, if (visible) 1.0 else 0.0, 1.0) }
        val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }
        world[11] = CameraLandmark(0.0, 1.0, 0.0, 1.0, 1.0)
        world[12] = CameraLandmark(-.4, 1.0, 0.0, 1.0, 1.0)
        world[23] = CameraLandmark(0.0, 1.5, 0.0, 1.0, 1.0)
        world[24] = CameraLandmark(-.4, 1.5, 0.0, 1.0, 1.0)
        world[13] = CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0)
        val radians = Math.toRadians(angle)
        world[15] = CameraLandmark(sin(radians), cos(radians), 0.0, 1.0, 1.0)
        val ns = timeMs * 1_000_000L
        return CameraPoseFrame(timeMs, ns, ns, timeMs, ns, timeMs * 1000, 640, 480, 0, true,
            CameraTimestampSource.PHONE_ELAPSED_REALTIME, "fixture-clock", listOf(image), listOf(world.map { it.copy(x = (it.x + it.z) / kotlin.math.sqrt(2.0), z = (it.z - it.x) / kotlin.math.sqrt(2.0)) }), true)
    }
    @Test fun allReviewedExercisesHaveAnExplicitGuideAndRequiredAnatomicalJoints() {
        assertEquals(244, ExerciseMeasurementContracts.standard.size)
        ExerciseMeasurementContracts.standard.keys.forEach { id ->
            for (left in listOf(true, false)) {
                val guide = CameraCaptureGuide.forExercise(id, left)
                assertTrue(guide.id.contains(id)); assertTrue(guide.direction.isNotBlank())
                assertTrue(guide.required.isNotEmpty()); assertTrue(guide.startDescription.isNotBlank())
                assertTrue(guide.required.all { it in 0..32 })
            }
        }
    }
    @Test fun everyExerciseGetsAOneOrTwoLineBriefInsteadOfAParagraph() {
        (ExerciseMeasurementContracts.standard.keys + "squat").forEach { id ->
            for (left in listOf(true, false)) {
                val guide = CameraCaptureGuide.forExercise(id, left)
                assertTrue(guide.headline.isNotBlank() && guide.headline.length <= 24, "$id ${guide.headline}")
                val hint = guide.startHint
                if (ExerciseMeasurementContracts.resolve(id).recipe in setOf(MeasurementRecipe.MANUAL, MeasurementRecipe.PULL_UP, MeasurementRecipe.LOADED_HINGE, MeasurementRecipe.LOADED_LUNGE, MeasurementRecipe.HIP_EXTENSION, MeasurementRecipe.CORE_FLEXION, MeasurementRecipe.CORE_ROTATION, MeasurementRecipe.CORE_EXTENSION, MeasurementRecipe.DIP, MeasurementRecipe.SQUAT_PRESS)) assertNull(hint, id)
                else assertTrue(hint != null && hint.length <= 26, "$id $hint")
            }
        }
        val curl = CameraCaptureGuide.forExercise("dumbbell_curl", true)
        assertEquals("45° 사선 · 골반과 왼팔이 보이게", curl.headline)
        assertEquals("팔을 쭉 내린 상태에서 시작", curl.startHint)
        assertEquals("45° 사선 · 골반과 오른팔이 보이게", CameraCaptureGuide.forExercise("dumbbell_curl", false).headline)
    }
    @Test fun mirroredPreviewDoesNotSwapTheObservedAnatomicalArmAndOcclusionBlocksReady() {
        val guide = CameraCaptureGuide.forExercise("dumbbell_curl", true)
        val points = frame(1000, 160.0).imageLandmarks.single().toMutableList()
        assertTrue(guide.visible(points)); points[16] = points[16].copy(visibility = 0.0)
        assertTrue(guide.visible(points)); points[15] = points[15].copy(presence = 0.0)
        assertFalse(guide.visible(points)); points[15] = points[15].copy(presence = 1.0, x = 1.2)
        assertFalse(guide.visible(points))
    }
    @Test fun yawRequiresBothShouldersAndHipsEvenForElbowAngleExercises() {
        val points = frame(1000, 160.0).imageLandmarks.single().toMutableList()
        listOf(23, 24, 25, 26, 27, 28).forEach { points[it] = points[it].copy(y = 1.3, visibility = 0.0) }
        for (id in listOf("dumbbell_curl", "cable_seated_row", "lat_pull_down", "dumbbell_shoulder_press", "cable_pushdown"))
            assertFalse(CameraCaptureGuide.forExercise(id, true).visible(points), id)
        // The shoulder-raise angle is measured against the torso, so the hip stays required.
        assertFalse(CameraCaptureGuide.forExercise("dumbbell_lateral_raise", true).visible(points))
        assertFalse(CameraCaptureGuide.forExercise("squat", true).visible(points))
    }
    private fun world(vararg joints: Pair<Int, Triple<Double, Double, Double>>): List<CameraLandmark> {
        val list = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }
        joints.forEach { (i, p) -> list[i] = CameraLandmark(p.first, p.second, p.third, 1.0, 1.0) }
        return list
    }
    private val visibleImage = List(33) { CameraLandmark(0.5, 0.5, 0.0, 1.0, 1.0) }

    @Test fun shoulderDrivenLiftsHaveStartChecksInsteadOfAManualTick() {
        // Left shoulder at the origin, right shoulder to its right, hip below (MediaPipe world y points down).
        val base = arrayOf(11 to Triple(0.0, 0.0, 0.0), 12 to Triple(-0.4, 0.0, 0.0), 23 to Triple(0.0, 0.5, 0.0), 15 to Triple(0.6, 0.0, 0.0))
        val open = world(*base, 13 to Triple(0.3, 0.0, 0.0))
        val forward = world(*base, 13 to Triple(0.0, 0.0, -0.3))
        assertTrue(CameraCaptureGuide.forExercise("machine_fly", true).copy(viewTarget = null).startReady(visibleImage, open, true))
        assertFalse(CameraCaptureGuide.forExercise("machine_fly", true).copy(viewTarget = null).startReady(visibleImage, forward, true))
        assertTrue(CameraCaptureGuide.forExercise("machine_reverse_fly", true).copy(viewTarget = null).startReady(visibleImage, forward, true))
        assertFalse(CameraCaptureGuide.forExercise("machine_reverse_fly", true).copy(viewTarget = null).startReady(visibleImage, open, true))
        val overhead = world(*base, 13 to Triple(0.0, -0.3, 0.0))
        val atChest = world(*base, 13 to Triple(0.0, 0.0, -0.3))
        listOf("dumbbell_pullover", "barbell_pullover").forEach { id ->
            assertTrue(CameraCaptureGuide.forExercise(id, true).copy(viewTarget = null).startReady(visibleImage, overhead, true), id)
            assertFalse(CameraCaptureGuide.forExercise(id, true).copy(viewTarget = null).startReady(visibleImage, atChest, true), id)
        }
    }

    @Test fun externalRotationOnlyChecksTheElbowSetUpAndHoldsLonger() {
        val guide = CameraCaptureGuide.forExercise("external_rotation", true).copy(viewTarget = null)
        assertNull(guide.metric); assertEquals(1000L, guide.readyHoldMs)
        val bent = world(11 to Triple(0.0, 0.0, 0.0), 13 to Triple(0.0, 0.3, 0.0), 15 to Triple(0.0, 0.3, -0.25))
        val straight = world(11 to Triple(0.0, 0.0, 0.0), 13 to Triple(0.0, 0.3, 0.0), 15 to Triple(0.0, 0.55, -0.05))
        assertTrue(guide.startReady(visibleImage, bent, true))
        assertFalse(guide.startReady(visibleImage, straight, true))
        assertEquals(200L, CameraCaptureGuide.forExercise("dumbbell_curl", true).readyHoldMs)
    }
    @Test fun completeCandidateKeepsActualFrameBoundariesAndIncompleteOrHiddenMotionDoesNotCount() {
        val frames = listOf(frame(1000, 160.0), frame(1200, 160.0), frame(1400, 120.0), frame(1600, 60.0),
            frame(1800, 100.0), frame(2000, 160.0))
        val proposed = CameraRepProposals.propose(frames, "dumbbell_curl", true)
        assertEquals(1, proposed.size); assertEquals(1200_000_000L, proposed.single().startNs)
        assertEquals(1600_000_000L, proposed.single().turnaroundNs); assertEquals(2000_000_000L, proposed.single().endNs)
        assertEquals(MusclePhase.CONCENTRIC, proposed.single().departurePhase)
        assertTrue(CameraRepProposals.propose(frames.dropLast(1), "dumbbell_curl", true).isEmpty())
        assertTrue(CameraRepProposals.propose(frames.mapIndexed { i, f -> if (i == 3) frame(1600, 60.0, false) else f }, "dumbbell_curl", true).isEmpty())
    }
    @Test fun gapCannotBeFilledWithInventedRestOrBoundaries() {
        val frames = listOf(frame(1000, 160.0), frame(1200, 160.0), frame(1400, 120.0), frame(2600, 60.0), frame(2800, 160.0))
        assertTrue(CameraRepProposals.propose(frames, "dumbbell_curl", true).isEmpty())
    }

    @Test fun externalRotationAsksForAFortyFiveDegreeViewWithAMatchingPictogram() {
        val guide = CameraCaptureGuide.forExercise("external_rotation", true)
        assertEquals("45° 사선 · 골반과 왼팔이 보이게", guide.headline)
        assertTrue(guide.direction.contains("45°") && !guide.direction.contains("측면/사선"))
        assertEquals(CameraPoseFamily.EXTERNAL_ROTATION_OBLIQUE, CameraPoseGuide.familyFor("external_rotation"))
    }
}
