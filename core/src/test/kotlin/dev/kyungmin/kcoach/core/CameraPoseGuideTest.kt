package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*

class CameraPoseGuideTest {
    @Test fun defaultGuideKeepsUpWithBodyTranslationAtLowFrameRates() {
        val smoother = CameraPoseGuideSmoother()
        val a = GuideAnchor(GuidePoint(100.0, 100.0), GuidePoint(80.0, 100.0), 600.0, 1)
        smoother.update(a, 0)
        val b = a.copy(shoulder = GuidePoint(300.0, 100.0), other = GuidePoint(280.0, 100.0))
        assertTrue(smoother.update(b, 125)!!.shoulder.x >= 260.0)
        assertNull(smoother.update(null, 350), "A lost body must not leave a ghost guide behind")
    }
    private val w = 480.0; private val h = 640.0

    /** Pixel-space fixture converted to normalized landmarks; unlisted joints are invisible. */
    private fun pose(vararg joints: Pair<Int, Pair<Double, Double>>): List<CameraLandmark> {
        val list = MutableList(33) { CameraLandmark(0.5, 0.5, 0.0, 0.0, 0.0) }
        joints.forEach { (i, p) -> list[i] = CameraLandmark(p.first / w, p.second / h, 0.0, 0.99, 0.99) }
        return list
    }
    private fun dist(a: GuidePoint, b: GuidePoint) = hypot(a.x - b.x, a.y - b.y)

    @Test fun sideGuideAnchorsOnTheWatchShoulderAndScalesByUpperArmEvenWithTheHipCropped() {
        // Left side view, facing +x (nose ahead of the shoulder), hip and legs out of frame.
        val points = pose(0 to (260.0 to 200.0), 11 to (240.0 to 300.0), 13 to (250.0 to 420.0), 15 to (330.0 to 380.0))
        val anchor = assertNotNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.STAND_ARMS_DOWN_SIDE, left = true))
        val upperArm = hypot(10.0, 120.0)
        assertEquals(upperArm / 0.186, anchor.bodyHeight, 1e-6)
        assertEquals(1, anchor.facing)
        val figure = CameraPoseGuide.build(CameraPoseFamily.STAND_ARMS_DOWN_SIDE, anchor)
        val arm = figure.limbs.first { it.primary }
        assertEquals(240.0, arm.points[0].x, 1e-6); assertEquals(300.0, arm.points[0].y, 1e-6)
        // Target arm hangs: elbow straight below the shoulder at the user's own upper-arm length.
        assertEquals(upperArm, dist(arm.points[0], arm.points[1]), 1e-6)
        assertTrue(abs(arm.points[1].x - 240.0) < 1.0 && arm.points[1].y > 300.0)
        assertTrue(figure.head.y < 300.0)
        assertFalse(figure.lowerBody)
    }

    @Test fun frontGuideUsesShoulderWidthAndBothShoulders() {
        val points = pose(11 to (300.0 to 250.0), 12 to (180.0 to 250.0), 13 to (310.0 to 330.0), 14 to (170.0 to 330.0),
            23 to (280.0 to 420.0), 24 to (200.0 to 420.0))
        val anchor = assertNotNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.STAND_ARMS_DOWN_FRONT, left = true))
        assertEquals(120.0 / 0.259, anchor.bodyHeight, 1e-6)
        val figure = CameraPoseGuide.build(CameraPoseFamily.STAND_ARMS_DOWN_FRONT, anchor)
        assertEquals(2, figure.limbs.count { it.primary })
        assertEquals(setOf(300.0, 180.0), figure.limbs.map { it.points[0].x }.toSet())
    }

    @Test fun facingFlipsWithTheNoseAndLyingTorsoPointsTowardTheHead() {
        val points = pose(0 to (150.0 to 300.0), 11 to (200.0 to 310.0), 13 to (205.0 to 230.0), 23 to (320.0 to 320.0))
        val anchor = assertNotNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.LYING_ARMS_UP_SIDE, left = true))
        assertEquals(-1, anchor.facing)
        val figure = CameraPoseGuide.build(CameraPoseFamily.LYING_ARMS_UP_SIDE, anchor)
        assertTrue(figure.head.x < 200.0 && abs(figure.head.y - 310.0) < 5.0)
        val arm = figure.limbs.first { it.primary }
        assertTrue(arm.points.last().y < 310.0 - 100.0) // arms point to the ceiling
    }

    @Test fun missingAnchorJointsGiveNoGuideInsteadOfAGuess() {
        val points = pose(11 to (240.0 to 300.0))
        assertNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.STAND_ARMS_DOWN_SIDE, left = true))
        assertNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.STAND_ARMS_DOWN_SIDE, left = false))
    }

    @Test fun everyReviewedExerciseResolvesToAFamilyOrExplicitlyNone() {
        val withoutGuide = setOf(MeasurementRecipe.MANUAL, MeasurementRecipe.PULL_UP, MeasurementRecipe.LOADED_HINGE, MeasurementRecipe.LOADED_LUNGE, MeasurementRecipe.HIP_EXTENSION, MeasurementRecipe.CORE_FLEXION, MeasurementRecipe.CORE_ROTATION, MeasurementRecipe.CORE_EXTENSION, MeasurementRecipe.DIP, MeasurementRecipe.SQUAT_PRESS)
        ExerciseMeasurementContracts.standard.keys.forEach { id ->
            val family = CameraPoseGuide.familyFor(id)
            if (ExerciseMeasurementContracts.resolve(id).recipe in withoutGuide) assertNull(family, id) else assertNotNull(family, id)
        }
        assertEquals(CameraPoseFamily.STAND_ARMS_DOWN_SIDE, CameraPoseGuide.familyFor("dumbbell_curl"))
        assertEquals(CameraPoseFamily.SQUAT_SIDE, CameraPoseGuide.familyFor("squat"))
        assertTrue(CameraPoseFamily.SQUAT_SIDE.lowerBody)
    }

    @Test fun smootherFollowsGraduallyAndDropsAfterTheAnchorIsLost() {
        val smoother = CameraPoseGuideSmoother(alpha = 0.5, holdMs = 300)
        val a = GuideAnchor(GuidePoint(100.0, 100.0), GuidePoint(100.0, 100.0), 600.0, 1)
        val b = a.copy(shoulder = GuidePoint(200.0, 100.0), other = GuidePoint(200.0, 100.0), bodyHeight = 800.0)
        assertEquals(100.0, smoother.update(a, 0)!!.shoulder.x, 1e-9)
        val mid = smoother.update(b, 33)!!
        assertEquals(150.0, mid.shoulder.x, 1e-9); assertEquals(700.0, mid.bodyHeight, 1e-9)
        assertNotNull(smoother.update(null, 200))
        assertNull(smoother.update(null, 400))
    }

    @Test fun obliqueGuideScalesBodySizeByTheForeshortenedShoulderWidth() {
        val points = pose(11 to (300.0 to 250.0), 12 to (200.0 to 250.0), 13 to (300.0 to 330.0), 14 to (200.0 to 330.0))
        val front = assertNotNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.STAND_ARMS_DOWN_FRONT, left = true))
        val oblique = assertNotNull(CameraPoseGuide.measure(points, w, h, CameraPoseFamily.EXTERNAL_ROTATION_OBLIQUE, left = true))
        assertEquals(front.bodyHeight / kotlin.math.cos(Math.toRadians(CameraOrientationPolicy.OBLIQUE)), oblique.bodyHeight, 1e-6)
    }
}
