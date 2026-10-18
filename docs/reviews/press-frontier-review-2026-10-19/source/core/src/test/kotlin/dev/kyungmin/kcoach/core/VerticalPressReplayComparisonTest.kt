package dev.kyungmin.kcoach.core

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Descriptive comparison on previously explored public data; not personal or Watch8 acceptance. */
class VerticalPressReplayComparisonTest {
    @Test fun `compare frozen v4 vertical v1 and fixed timing candidate on identical sixty sets`() {
        val directory = System.getenv("KCOACH_MMFIT_DIR")?.let(::File)
        assumeTrue(directory?.isDirectory == true, "Configure converted MM-Fit data")
        val current = ExerciseTrackingProfiles.resolve("dumbbell_shoulder_press")
        val template = MotionFamilyTemplates.get(MotionFamily.PRESS_RETURN)
        val old = current.copy(id = MotionFamily.PRESS_RETURN.sharedAlgorithmId, family = MotionFamily.PRESS_RETURN,
            signal = template.signal, minimumRepMs = template.minimumRepMs, maximumRepMs = template.maximumRepMs,
            minimumSeparationMs = template.minimumSeparationMs, minimumRelativeRom = template.minimumRelativeRom,
            minimumFeatureRate = template.minimumFeatureRate, minimumLobeDisplacement = template.minimumLobeDisplacement,
            minimumLobeMs = template.minimumLobeMs, maximumLobeMs = template.maximumLobeMs)
        data class Row(val session: String, val expected: Int, val old: Int, val vertical: Int, val timing: Int)
        val rows = mutableListOf<Row>()
        directory!!.listFiles().orEmpty().filter { it.name.matches(Regex("w\\d+\\.csv")) }.sorted().forEach { file ->
            val labels = File(directory, file.nameWithoutExtension + "_labels.csv").readLines().drop(1)
                .map { it.split(',') }.filter { it[3] == "dumbbell_shoulder_press" }
            if (labels.isEmpty()) return@forEach
            val samples = CurrentDetectorReplay.readSamples(file)
            labels.forEach { label ->
                val original = samples.filter { it.timestampNs in label[0].toLong()..label[1].toLong() }
                val before = ProfiledMotionRepDetector(old); val after = ProfiledMotionRepDetector(current)
                // Fixed before this benchmark; do not search for a better fraction on these labels.
                val timing = VerticalPressTimingCandidate(.15)
                rows += Row(file.nameWithoutExtension, label[2].toInt(), original.count { before.add(it) != null },
                    original.count { after.add(it) != null }, original.count { timing.add(it) != null })
            }
        }
        assertTrue(rows.size == 60, "Expected the same 60 shoulder-press sets, found ${rows.size}")
        val output = File("build/vertical-press-comparison.csv")
        output.writeText("session,expected,frozen_v4,vertical_v1,timing_candidate_015\n" + rows.joinToString("\n") { "${it.session},${it.expected},${it.old},${it.vertical},${it.timing}" } + "\n")
        fun summary(select: (Row) -> Int): String = "MAE=%.3f exact=%d/%d within1=%d/%d".format(Locale.US,
            rows.map { abs(select(it) - it.expected) }.average(), rows.count { select(it) == it.expected }, rows.size,
            rows.count { abs(select(it) - it.expected) <= 1 }, rows.size)
        println("Frozen v4 ${summary { it.old }}")
        println("Vertical v1 ${summary { it.vertical }}")
        println("Timing candidate recovery=0.15 ${summary { it.timing }}")
        println("Timing versus v1: better=${rows.count { abs(it.timing-it.expected)<abs(it.vertical-it.expected) }} " +
            "same=${rows.count { abs(it.timing-it.expected)==abs(it.vertical-it.expected) }} worse=${rows.count { abs(it.timing-it.expected)>abs(it.vertical-it.expected) }}")
        println("Per-set: better=${rows.count { abs(it.vertical-it.expected)<abs(it.old-it.expected) }} " +
            "same=${rows.count { abs(it.vertical-it.expected)==abs(it.old-it.expected) }} worse=${rows.count { abs(it.vertical-it.expected)>abs(it.old-it.expected) }}")
        assertTrue(rows.sumOf { abs(it.vertical - it.expected) } < rows.sumOf { abs(it.old - it.expected) })
    }
}
