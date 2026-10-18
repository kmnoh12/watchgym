package dev.kyungmin.kcoach.core

import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertTrue

/** Local private evidence is optional and never embedded into the repository or auto-accepted. */
class SelectedEvidenceReplayTest {
    @Test fun `replay selected original evidence with frozen current and pose candidate detectors`() {
        val source = System.getenv("KCOACH_EVIDENCE_ZIP")?.let(::File)
        assumeTrue(source?.isFile == true, "Configure a selected original evidence ZIP")
        ZipFile(source!!).use { zip ->
            val wire = zip.getInputStream(zip.getEntry("watch.manifest")).bufferedReader().use { WireCodec.decode(it.readText()) }
            val original = zip.getInputStream(zip.getEntry("watch.kcs")).use(SensorCaptureFormat::read)
            val frozen = TrackingProfileSnapshot.decode(wire.payload.getValue("trackingProfileSnapshot"))
            val current = ExerciseTrackingProfiles.resolve(frozen.exerciseId)
            val reviewed = zip.getInputStream(zip.getEntry("review.json")).bufferedReader().use { it.readText() }
                .let { Regex("\"reps\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
            assertTrue(original.validateTimestamps().isEmpty())
            val oldDetector = ProfiledMotionRepDetector(frozen)
            val newDetector = ProfiledMotionRepDetector(current)
            val poseCandidate = ReturnToPoseRepDetector(current.minimumRepMs)
            val verticalCandidate = VerticalPressRepDetector()
            val events = linkedMapOf<String, MutableList<ProfiledMotionRepEvent>>(
                "frozen" to mutableListOf(), "current" to mutableListOf(), "pose_candidate_unadopted" to mutableListOf(), "vertical_candidate_unadopted" to mutableListOf())
            original.records.forEach { record ->
                val sample = SensorRecordQuality.forCounting(record)
                oldDetector.add(sample)?.let { events.getValue("frozen") += it }
                newDetector.add(sample)?.let { events.getValue("current") += it }
                poseCandidate.add(sample)?.let { events.getValue("pose_candidate_unadopted") += it }
                verticalCandidate.add(sample)?.let { events.getValue("vertical_candidate_unadopted") += it }
            }
            val t0 = original.records.first().sample.timestampNs
            File("build/selected-evidence-replay.csv").writeText(buildString {
                appendLine("detector,start_s,turn_s,end_s,duration_ms")
                events.forEach { (name, list) -> list.forEach { e ->
                    appendLine("$name,${(e.startTimestampNs-t0)/1e9},${(e.turnaroundTimestampNs-t0)/1e9},${(e.endTimestampNs-t0)/1e9},${e.durationMs}")
                } }
            })
            println("Exercise=${frozen.exerciseId} samples=${original.sampleCount} reviewed=$reviewed recordedWatch=${wire.payload["watchReps"]}")
            events.forEach { (name, list) -> println("$name=${list.size} end_s=${list.map { (it.endTimestampNs-t0)/1e9 }}") }
            println("No candidate is enabled by this replay; preparation/runtime state are outside this detector-only comparison.")
        }
    }
}
