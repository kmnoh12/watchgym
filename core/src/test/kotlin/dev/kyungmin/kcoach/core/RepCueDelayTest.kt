package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RepCueDelayTest {
    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun delaysByTheFractionOfTimeSinceTheTop() = assertEquals(320L, RepCueDelay.delayMs(0L, ms(800)))

    @Test
    fun clampsFastAndSlowDescents() {
        assertEquals(RepCueDelay.MIN_MS, RepCueDelay.delayMs(0L, ms(200)))
        assertEquals(RepCueDelay.MAX_MS, RepCueDelay.delayMs(0L, ms(3_000)))
    }

    @Test
    fun invalidTimingFallsBackToImmediate() {
        assertEquals(0L, RepCueDelay.delayMs(ms(900), ms(800)))
        assertEquals(0L, RepCueDelay.delayMs(0L, ms(9_000)))
    }
}
