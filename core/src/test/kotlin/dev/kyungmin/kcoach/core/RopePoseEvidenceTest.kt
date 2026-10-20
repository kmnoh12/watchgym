package dev.kyungmin.kcoach.core

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Optional private captures stay outside git; reviewed totals never enter the detector. */
class RopePoseEvidenceTest {
    @Test fun `three reviewed rope sets count ten while frozen profiles keep historical results`() {
        val root = System.getenv("KCOACH_ROPE_EVIDENCE_DIR")?.let(::File)
        assumeTrue(root?.isDirectory == true, "Configure private extracted rope captures")
        for ((id, oldCount) in listOf("9cf833a3" to 11, "cfc9dc8b" to 6, "9fb34269" to 7)) {
            val folder = File(root!!, id)
            val wire = WireCodec.decode(File(folder, "watch.manifest").readText())
            val records = File(folder, "watch.kcs").inputStream().use(SensorCaptureFormat::read)
            assertTrue(records.validateTimestamps().isEmpty())
            val frozen = TrackingProfileSnapshot.decode(wire.payload.getValue("trackingProfileSnapshot"))
            assertEquals("rope_extension", frozen.exerciseId)
            val old = ProfiledMotionRepDetector(frozen)
            val current = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve(frozen.exerciseId))
            val before = mutableListOf<ProfiledMotionRepEvent>()
            val after = mutableListOf<ProfiledMotionRepEvent>()
            records.records.forEach { record ->
                val sample = SensorRecordQuality.forCounting(record)
                old.add(sample)?.let(before::add)
                current.add(sample)?.let(after::add)
            }
            assertEquals(oldCount, before.size, "$id historical replay changed")
            assertEquals(10, after.size, "$id user-reviewed total mismatch")
            assertNull(current.finish(), "Set end must not invent another repetition")
            assertEquals(10, after.map { it.endTimestampNs }.distinct().size)
            val t0 = records.records.first().sample.timestampNs
            println("$id frozen=${before.size} current=${after.size} end_s=${after.map { (it.endTimestampNs-t0)/1e9 }}")
        }
    }

    @Test fun `translation pushdown detector also counts the real pushdown captures within one`() {
        val root = System.getenv("KCOACH_ROPE_EVIDENCE_DIR")?.let(::File)
        assumeTrue(root?.isDirectory == true, "Configure private extracted rope captures")
        for (id in listOf("9cf833a3", "cfc9dc8b", "9fb34269")) {
            val records = File(root!!, "$id/watch.kcs").inputStream().use(SensorCaptureFormat::read)
            val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("cable_pushdown"))
            detector.beginCounting()
            val events = records.records.mapNotNull { detector.add(SensorRecordQuality.forCounting(it)) }
            assertTrue(events.size in 9..10, "$id translation pushdown=${events.size}")
            println("$id translation_pushdown=${events.size}")
        }
    }
}
