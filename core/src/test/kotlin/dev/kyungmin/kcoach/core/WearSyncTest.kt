package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WearSyncTest {
    @Test
    fun `heartbeat contains only connection fields`() {
        val fields = WearSync.parse(WearSync.heartbeat("phone"))
        assertEquals(3, fields.size)
        assertEquals("1", fields[0])
        assertEquals("phone", fields[1])
        check(fields[2].toLong() > 0L)
    }

    @Test
    fun `live state exposes actual personal profile use without shifting legacy fields`() {
        val fields = WearSync.parse(
            WearSync.liveState(
                sessionId = "session-1",
                exerciseId = "external_rotation",
                exerciseName = "케이블 익스터널 로테이션",
                setIndex = 1,
                reps = 4,
                targetReps = 12,
                weightKg = 5.0,
                heartRate = 103,
                phase = "WORKING",
                recording = true,
                restEndAtMs = 0L,
                sampleCount = 120,
                trackingProfileId = "external-rotation+personal-1",
                personalized = true,
            ),
        )

        assertEquals("WORKING", fields[9])
        assertEquals("0", fields[12])
        assertEquals("120", fields[13])
        assertEquals("external-rotation+personal-1", fields[14])
        assertEquals("true", fields[15])
    }
}
