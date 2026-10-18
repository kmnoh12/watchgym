package dev.kyungmin.kcoach.core

import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Boundary diagnosis only. Context and observed tail are never pooled into the strict score. */
class PressBoundaryDiagnosticTest {
    @Test fun diagnoseObservedStartAndEndContextWithoutInventingSamples() {
        val dir = System.getenv("KCOACH_MMFIT_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        val rows = mutableListOf<List<String>>()
        val lobes = mutableListOf<String>()
        val tailLobes = mutableListOf<String>()
        dir!!.listFiles().orEmpty().filter { it.name.matches(Regex("w\\d+\\.csv")) }.sorted().forEach { file ->
            val samples = CurrentDetectorReplay.readSamples(file)
            File(dir, file.nameWithoutExtension + "_labels.csv").readLines().drop(1).map { it.split(',') }
                .filter { it[3] == "dumbbell_shoulder_press" }.forEachIndexed { index, label ->
                    val from = label[0].toLong(); val end = label[1].toLong(); val expected = label[2].toInt()
                    for (recovery in listOf(.5, .15)) for (preparation in listOf(0L, 2_000_000_000L)) {
                        val detector = VerticalPressTimingCandidate(recovery)
                        if (preparation > 0) samples.filter { it.timestampNs >= from-preparation && it.timestampNs < from }
                            .forEach { detector.add(it) }
                        detector.beginCounting()
                        val events = samples.filter { it.timestampNs in from..end }.mapNotNull { detector.add(it) }
                        if (preparation == 0L) detector.closedLobes.forEach { lobes += "${file.nameWithoutExtension},${index+1},$recovery,$from,$it" }
                        val closedBeforeTail = detector.closedLobes.size
                        val pendingAtEnd = detector.candidateStartNs
                        val tail = samples.filter { it.timestampNs > end && it.timestampNs <= end+2_000_000_000L }
                            .mapNotNull { detector.add(it) }
                        if (preparation == 0L) detector.closedLobes.drop(closedBeforeTail).forEach { tailLobes += "${file.nameWithoutExtension},${index+1},$recovery,$from,$it" }
                        val closingInsideCandidate = tail.count { it.startTimestampNs <= end }
                        rows += listOf(file.nameWithoutExtension, (index+1).toString(), expected.toString(),
                            recovery.toString(), preparation.toString(), events.size.toString(),
                            closingInsideCandidate.toString(), (pendingAtEnd != null).toString(),
                            (events.firstOrNull()?.let { (it.startTimestampNs-from)/1e9 }).toString(),
                            (events.lastOrNull()?.let { (end-it.endTimestampNs)/1e9 }).toString())
                    }
                }
        }
        File("build/press-lobe-diagnostic.csv").writeText("session,set,recovery,label_start_ns,direction,start_ns,end_ns,valid_lobe,had_ascent,emitted,duration_ms,area,peak,cycle_ms\n" + lobes.joinToString("\n") + "\n")
        File("build/press-tail-lobe-diagnostic.csv").writeText("session,set,recovery,label_start_ns,direction,start_ns,end_ns,valid_lobe,had_ascent,emitted,duration_ms,area,peak,cycle_ms\n" + tailLobes.joinToString("\n") + "\n")
        assertEquals(240, rows.size)
        File("build/press-boundary-diagnostic.csv").writeText("session,set,expected,recovery,preparation_ns,strict_count,observed_tail_closures,pending_at_label_end,first_start_s,last_end_gap_s\n"+
            rows.joinToString("\n") { it.joinToString(",") }+"\n")
        for (recovery in listOf("0.5","0.15")) for (prep in listOf("0","2000000000")) {
            val group=rows.filter { it[3]==recovery && it[4]==prep }
            println("recovery=$recovery pre=$prep strictExact=${group.count { it[2]==it[5] }}/60 " +
                "strictMAE=${group.map { abs(it[2].toInt()-it[5].toInt()) }.average()} " +
                "incompleteCyclesClosedInObservedTail=${group.count { it[6].toInt()>0 }} " +
                "diagnosticContextExact=${group.count { it[2].toInt()==it[5].toInt()+it[6].toInt() }}/60")
        }
    }
}
