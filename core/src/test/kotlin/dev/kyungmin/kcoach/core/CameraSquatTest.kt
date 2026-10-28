package dev.kyungmin.kcoach.core

import kotlin.test.*

class CameraSquatTest {
    @Test fun squatVariantsShareTheCameraRecipeAndTheCycleAxisWristEngine() {
        assertEquals(229, ExerciseCapabilityMatrix.standardExerciseIds().size)
        for (id in listOf("squat", "barbell_squat", "front_squat", "goblet_squat", "smith_squat")) {
            assertEquals(MeasurementRecipe.SQUAT, ExerciseMeasurementContracts.resolve(id).recipe)
            assertEquals(CameraMotion.SQUAT_KNEE_FLEXION, CameraCaptureGuide.forExercise(id, true).metric)
            assertEquals(MotionFamily.CYCLE_AXIS_RETURN, ExerciseTrackingProfiles.resolve(id).family)
        }
    }
    private fun frame(ms: Long, bend: Double): CameraPoseFrame {
        val image = MutableList(33) { CameraLandmark(.5, .5, 0.0, .95, .95) }
        val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, .95, .95) }
        image[0] = image[0].copy(y = .08 + bend * .08)
        for (side in 0..1) {
            val x = if (side == 0) .16 else -.16
            image[11 + side] = image[0].copy(x = .5 + x, y = .20 + bend * .08)
            image[23 + side] = image[0].copy(x = .5 + x + bend * .05, y = .42 + bend * .16)
            image[25 + side] = image[0].copy(x = .5 + x + bend * .12, y = .65)
            image[27 + side] = image[0].copy(x = .5 + x, y = .88)
            world[11 + side] = world[0].copy(x = x * 1.3, y = -.45)
            world[23 + side] = world[0].copy(x = x, y = 0.0)
            world[25 + side] = world[0].copy(x = x + bend * .20, y = .40 - bend * .15)
            world[27 + side] = world[0].copy(x = x, y = .80 - bend * .30)
        }
        val ns = 1_000_000_000L + ms * 1_000_000L
        return CameraPoseFrame(ms / 50, ns, ns + 1_000_000, ns / 1_000_000, ns + 2_000_000, ms * 1000,
            640, 480, 0, true, CameraTimestampSource.PHONE_ELAPSED_REALTIME, "phone", listOf(image), listOf(world.map { it.copy(x = (it.x + it.z) / kotlin.math.sqrt(2.0), z = (it.z - it.x) / kotlin.math.sqrt(2.0)) }), true)
    }
    private fun sequence() = (0L..1100L step 50).map { ms ->
        frame(ms, when { ms < 400 -> 0.0; ms <= 650 -> (ms - 400) / 250.0; ms <= 900 -> (900 - ms) / 250.0; else -> 0.0 })
    }
    @Test fun standingIsReadyOnlyAndOnlyTheCompleteDownAndUpMotionProducesAProvisionalCycle() {
        val frames = sequence()
        val guide = CameraCaptureGuide.forExercise("squat", true)
        val counter = UpperBodyPoseCounter(requireNotNull(guide.metric))
        frames.take(9).forEach { counter.observe(it.modelTimestampMs, guide.observedMetric(it.imageLandmarks.single(), it.worldLandmarks.single(), true)) }
        assertTrue(counter.ready); assertEquals(0, counter.reps)
        frames.drop(9).forEach { counter.observe(it.modelTimestampMs, guide.observedMetric(it.imageLandmarks.single(), it.worldLandmarks.single(), true)) }
        assertEquals(1, counter.reps)
        val cycles = CameraRepProposals.propose(frames, "squat", true)
        assertEquals(1, cycles.size)
        assertEquals(MusclePhase.ECCENTRIC, cycles.single().departurePhase)
        assertTrue(cycles.single().startNs >= 1_400_000_000L)
        assertTrue(CameraRepProposals.propose(frames.take(14), "squat", true).isEmpty())
        assertEquals(MotionFamily.CYCLE_AXIS_RETURN, ExerciseTrackingProfiles.resolve("squat").family)
    }
    @Test fun hiddenLegsOrALyingImageCannotConfirmTheStandingCandidate() {
        val standing = frame(0, 0.0); val guide = CameraCaptureGuide.forExercise("squat", true)
        val hidden = standing.imageLandmarks.single().toMutableList().apply { this[28] = this[28].copy(presence = .1) }
        assertNull(guide.observedMetric(hidden, standing.worldLandmarks.single(), true))
        assertNull(guide.observedMetric(standing.imageLandmarks.single().map { it.copy(y = .5) }, standing.worldLandmarks.single(), true))
        val frames = sequence().mapIndexed { i, f -> if (i == 13) f.copy(imageLandmarks = listOf(hidden)) else f }
        assertTrue(CameraRepProposals.propose(frames, "squat", true).isEmpty())
    }
    @Test fun pelvisTravelUsesPixelsAndTheStartingTorsoLengthEvenWhenWorldHipHeightIsAlwaysZero() {
        val frames = sequence()
        val cycle = RepCycle(frames[8].cameraTimestampNs, frames[13].cameraTimestampNs, frames[18].cameraTimestampNs, MusclePhase.ECCENTRIC)
        assertTrue(frames.all { it.worldLandmarks.single()[23].y == 0.0 })
        val metrics = assertNotNull(CameraSquatAnalyzer.analyze(frames, listOf(cycle), true))
        assertEquals(.16 / .22, metrics.cycles.single().pelvisDropTorsoLengths, 1e-8)
        assertTrue(metrics.cycles.single().pelvisSidewaysTorsoLengths > 0)
        assertEquals(0.0, metrics.cycles.single().returnOffsetTorsoLengths, 1e-8)
        assertNull(metrics.cycles.single().markedPauseMs)
        val translated = frames.map { it.copy(worldLandmarks = listOf(it.worldLandmarks.single().map { p -> p.copy(y = p.y + 10) })) }
        val moved = assertNotNull(CameraSquatAnalyzer.analyze(translated, listOf(cycle), true))
        assertEquals(metrics.cycles.single().pelvisDropTorsoLengths, moved.cycles.single().pelvisDropTorsoLengths)
        assertEquals(metrics.cycles.single().minimumKneeDegrees, moved.cycles.single().minimumKneeDegrees, 1e-8)
        assertNull(CameraSquatAnalyzer.analyze(frames.filterIndexed { i, _ -> i != 13 }, listOf(cycle), true))
        val partial = cycle.copy(endNs = frames[15].cameraTimestampNs)
        assertNull(CameraSquatAnalyzer.analyze(frames, listOf(partial), true))
    }
}
