package dev.kyungmin.kcoach.core

import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class VerticalPressRepDetectorTest {
    @Test fun `replay reviewed ten press capture without camera or target count input`() {
        val source = System.getenv("KCOACH_PRESS_EVIDENCE_ZIP")?.let(::File)
        assumeTrue(source?.isFile == true, "Configure the local reviewed capture ZIP")
        val (document, frozen) = ZipFile(source!!).use { zip ->
            val document = zip.getInputStream(zip.getEntry("watch.kcs")).use(SensorCaptureFormat::read)
            val manifest = WireCodec.decode(zip.getInputStream(zip.getEntry("watch.manifest")).bufferedReader().use { it.readText() })
            document to TrackingProfileSnapshot.decode(manifest.payload.getValue("trackingProfileSnapshot"))
        }
        val old = ProfiledMotionRepDetector(frozen)
        assertEquals(8, document.records.count { old.add(SensorRecordQuality.forCounting(it)) != null }, "Original v4 reproduction")
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("dumbbell_shoulder_press"))
        val count = document.records.count { detector.add(SensorRecordQuality.forCounting(it)) != null }
        println("Full original capture: ${document.sampleCount} samples, frozen v4=8, vertical v1=$count, reviewed count 10")
        assertEquals(10, count)
        assertNull(detector.finish())
        assertEquals(count, PhoneRepAnalyzer.analyze(document, "dumbbell_shoulder_press").events.size)
        val signalDetector = VerticalPressRepDetector()
        val trace = StringBuilder("time_s,vertical_acceleration,velocity_proxy\n")
        val events = StringBuilder("start_s,turn_s,end_s\n")
        val t0 = document.records.first().sample.timestampNs
        document.records.forEach { record ->
            signalDetector.add(SensorRecordQuality.forCounting(record))?.let { event ->
                events.appendLine("${(event.startTimestampNs-t0)/1e9},${(event.turnaroundTimestampNs-t0)/1e9},${(event.endTimestampNs-t0)/1e9}")
            }
            signalDetector.latestSignal?.let { signal -> trace.appendLine("${(signal.timestampNs-t0)/1e9},${signal.acceleration},${signal.velocityProxy}") }
        }
        File("build/vertical-press-evidence-signal.csv").writeText(trace.toString())
        File("build/vertical-press-evidence-events.csv").writeText(events.toString())
    }

    @Test fun `vertical translation without any wrist rotation is counted at multiple tempos and orientations`() {
        for (period in listOf(1.5, 2.5, 4.0)) for (axis in 0..2) {
            val detector = VerticalPressRepDetector()
            val events = translation(6, period, axis).mapNotNull(detector::add)
            assertEquals(6, events.size, "period=$period axis=$axis")
            assertTrue(events.all { it.startTimestampNs < it.turnaroundTimestampNs && it.turnaroundTimestampNs < it.endTimestampNs })
        }
    }

    @Test fun `stationary noise and gyroscope energy alone cannot become vertical repetitions`() {
        val detector = VerticalPressRepDetector()
        val events = (0..3000).mapNotNull { i -> detector.add(SensorSample(1_000_000_000L + i * 20_000_000L,
            0f, 0f, (9.80665 + .03 * sin(i.toDouble())).toFloat(), sin(i * .1).toFloat(), 0f, 0f)) }
        assertTrue(events.isEmpty())
    }

    @Test fun `rotating gravity without translation cannot become vertical repetitions`() {
        for (angle in listOf(.3, .6, 1.0)) {
            val detector = VerticalPressRepDetector()
            val events = (0..1600).mapNotNull { i ->
                val time = i * .02
                val theta = angle * sin(2 * PI * time / 3)
                detector.add(SensorSample(1_000_000_000L + i * 20_000_000L,
                    (9.80665 * sin(theta)).toFloat(), 0f, (9.80665 * cos(theta)).toFloat(),
                    0f, (-angle * 2 * PI / 3 * cos(2 * PI * time / 3)).toFloat(), 0f))
            }
            assertTrue(events.isEmpty(), "angle=$angle")
        }
    }

    @Test fun `missing unreliable and invalid samples break an unfinished cycle`() {
        val samples = translation(1, 4.0, 2)
        for (fault in listOf("gap", "unreliable", "nan")) {
            val detector = VerticalPressRepDetector()
            val broken = samples.mapIndexed { i, sample -> when {
                fault == "gap" && i >= 195 -> sample.copy(timestampNs = sample.timestampNs + 2_000_000_000L)
                i in 155..195 && fault == "unreliable" -> sample.copy(sensorAccuracy = 0)
                i in 155..195 && fault == "nan" -> sample.copy(accelerationZ = Float.NaN)
                else -> sample
            } }
            assertTrue(broken.mapNotNull(detector::add).isEmpty(), fault)
        }
    }

    @Test fun `duplicate samples cannot change events and preparation does not leak a candidate`() {
        val samples = translation(4, 2.5, 1)
        val normal = VerticalPressRepDetector(); val duplicate = VerticalPressRepDetector()
        val expected = samples.mapNotNull(normal::add)
        val actual = samples.flatMap { listOfNotNull(duplicate.add(it), duplicate.add(it)) }
        assertEquals(expected, actual)
        duplicate.beginCounting()
        assertNull(duplicate.candidateStartNs)
    }

    private fun translation(reps: Int, period: Double, axis: Int): List<SensorSample> =
        (0..((4 + reps * period) * 50).toInt()).map { i ->
            val t = i / 50.0 - 2
            val omega = 2 * PI / period
            val acceleration = if (t >= 0 && t < reps * period) .35 * omega * omega / 2 * cos(omega * t) else 0.0
            val a = FloatArray(3); a[axis] = (9.80665 + acceleration).toFloat()
            SensorSample(1_000_000_000L + i * 20_000_000L, a[0], a[1], a[2], 0f, 0f, 0f)
        }

    @Test fun `a partial ascent never gets completed by ending the set`() {
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("dumbbell_shoulder_press"))
        val samples = translation(1, 4.0, 2).take(150)
        assertTrue(samples.mapNotNull(detector::add).isEmpty())
        assertNull(detector.finish())
    }
}
