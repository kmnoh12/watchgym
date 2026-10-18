package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostTargetTempoGuardTest {
    private fun sec(v: Double) = (v * 1e9).toLong()

    private fun tenReps(guard: PostTargetTempoGuard): Long {
        var t = 0L
        repeat(10) { i -> t += sec(2.2); assertTrue(guard.accept(t, i, 10), "rep ${i + 1} is never examined") }
        return t
    }

    @Test
    fun aRackTwistRightAfterTheTargetIsRefused() {
        val guard = PostTargetTempoGuard()
        val last = tenReps(guard)
        assertFalse(guard.accept(last + sec(0.7), 10, 10))
    }

    @Test
    fun aRepAtTheSetsTempoAfterTheTargetIsAccepted() {
        val guard = PostTargetTempoGuard()
        val last = tenReps(guard)
        assertTrue(guard.accept(last + sec(2.4), 10, 10))
    }

    @Test
    fun aRefusedEventDoesNotChangeTheHistory() {
        val guard = PostTargetTempoGuard()
        val last = tenReps(guard)
        assertFalse(guard.accept(last + sec(0.7), 10, 10))
        assertTrue(guard.accept(last + sec(2.3), 10, 10))
    }

    @Test
    fun fastRepsBeforeTheTargetAreNeverRefused() {
        val guard = PostTargetTempoGuard()
        var t = 0L
        repeat(5) { i -> t += sec(2.2); assertTrue(guard.accept(t, i, 10)) }
        t += sec(0.4)
        assertTrue(guard.accept(t, 5, 10))
    }

    @Test
    fun withTooLittleHistoryEverythingIsAccepted() {
        val guard = PostTargetTempoGuard()
        assertTrue(guard.accept(sec(2.0), 0, 2))
        assertTrue(guard.accept(sec(4.0), 1, 2))
        assertTrue(guard.accept(sec(4.2), 2, 2))
        guard.reset()
        assertEquals(true, guard.accept(sec(0.1), 5, 2))
    }
}
