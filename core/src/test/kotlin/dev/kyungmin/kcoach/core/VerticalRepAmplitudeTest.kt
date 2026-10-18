package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerticalRepAmplitudeTest {
    private fun event(displacement: Double) = ProfiledMotionRepEvent(0L, 1L, 2L, 2_000L, 0.5, displacement, 0.0, 0.65)

    @Test
    fun aFullPressIsAccepted() = assertTrue(VerticalRepAmplitude.accepts(event(0.48)))

    @Test
    fun aSmallArmLiftIsRefused() {
        assertFalse(VerticalRepAmplitude.accepts(event(0.08)))
        assertFalse(VerticalRepAmplitude.accepts(event(0.29)))
    }
}
