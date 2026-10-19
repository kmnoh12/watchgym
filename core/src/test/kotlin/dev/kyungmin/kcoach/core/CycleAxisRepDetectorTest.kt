package dev.kyungmin.kcoach.core

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class CycleAxisRepDetectorTest {
    private fun obliqueSet(reps: Int, periodS: Double, amplitude: Double, hz: Int = 50): List<SensorSample> {
        val axis = doubleArrayOf(0.40, 0.70, -0.59)
        val total = reps * periodS + 3.0
        return (0 until (total * hz).toInt()).map { i ->
            val t = i.toDouble() / hz
            val wave = if (t < reps * periodS) amplitude * sin(2 * PI * t / periodS) else 0.0
            SensorSample(1_000_000_000L + (t * 1e9).toLong(), 0f, 0f, 9.8f,
                (wave * axis[0]).toFloat(), (wave * axis[1]).toFloat(), (wave * axis[2]).toFloat(), 3)
        }
    }

    private fun obliqueAcceleration(reps: Int, periodS: Double, amplitude: Double, hz: Int = 50): List<SensorSample> {
        val axis = doubleArrayOf(0.40, 0.70, -0.59)
        val total = reps * periodS + 3.0
        return (0 until (total * hz).toInt()).map { i ->
            val t = i.toDouble() / hz
            val wave = if (t < reps * periodS) amplitude * sin(2 * PI * t / periodS) else 0.0
            SensorSample(1_000_000_000L + (t * 1e9).toLong(), (wave * axis[0]).toFloat(), (wave * axis[1]).toFloat(),
                (9.8 + wave * axis[2]).toFloat(), 0f, 0f, 0f, 3)
        }
    }

    /** The app requires a still wrist before counting starts, so a realistic set opens with a quiet stretch. */
    private fun withLeadingStill(samples: List<SensorSample>, seconds: Double, hz: Int = 50): List<SensorSample> {
        val still = (0 until (seconds * hz).toInt()).map { SensorSample(1_000_000_000L + it * (1e9 / hz).toLong(), 0f, 0f, 9.8f, 0f, 0f, 0f, 3) }
        val shift = seconds * 1e9.toLong()
        return still + samples.map { it.copy(timestampNs = it.timestampNs + shift.toLong()) }
    }

    private fun count(profileId: String, samples: List<SensorSample>): Int {
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve(profileId))
        detector.beginCounting()
        return samples.count { detector.add(it) != null } + detector.finishAll().size
    }

    @Test
    fun obliqueRowingAxisIsCountedWithoutAFixedSensorAxis() {
        assertEquals(10, count("cable_seated_row", obliqueSet(10, 2.3, 0.8)))
    }

    @Test
    fun aMotionlessWristCountsNothing() {
        assertEquals(0, count("cable_seated_row", obliqueSet(0, 2.3, 0.8)))
    }

    @Test
    fun tinyRegularMotionIsNotARow() {
        assertEquals(0, count("cable_seated_row", obliqueSet(10, 2.3, 0.03)))
        assertEquals(0, count("cable_seated_row", obliqueSet(10, 2.3, 0.2)))
        assertEquals(0, count("cable_seated_row", obliqueAcceleration(10, 2.3, 0.03)))
        assertEquals(0, count("cable_seated_row", obliqueAcceleration(10, 2.3, 0.22)))
    }

    @Test
    fun sensorNoiseAloneCountsNothing() {
        val random = kotlin.random.Random(7)
        val noise = (0 until 1250).map { i ->
            fun n(scale: Double) = ((random.nextDouble() - 0.5) * 2 * scale).toFloat()
            SensorSample(1_000_000_000L + i * 20_000_000L, n(0.02), n(0.02), 9.8f + n(0.02), n(0.002), n(0.002), n(0.002), 3)
        }
        assertEquals(0, count("cable_seated_row", noise))
    }

    @Test
    fun aRealSizedRowStrokeIsStillCountedFromGyroOrAcceleration() {
        assertEquals(10, count("cable_seated_row", obliqueSet(10, 2.3, 0.83)))
        assertEquals(10, count("cable_seated_row", obliqueAcceleration(10, 2.3, 3.4)))
    }

    @Test
    fun everyRepIsReportedShortlyAfterItsPeakStartingWithTheFirst() {
        val samples = withLeadingStill(obliqueSet(10, 2.3, 0.8), 2.0)
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("cable_seated_row"))
        detector.beginCounting()
        val delays = mutableListOf<Double>()
        samples.forEach { sample ->
            detector.add(sample)?.let { delays += (sample.timestampNs - it.endTimestampNs) / 1e9 }
        }
        assertEquals(10, delays.size + detector.finishAll().size)
        assertTrue(delays.size >= 9, "reps must be reported while the set runs, not at its end: ${delays.size}")
        assertTrue(delays.all { it in 0.0..0.7 }, "each rep is reported within 0.7 s of its peak: $delays")
    }

    @Test
    fun theFirstRepIsNotDelayedUntilThreePeaksExist() {
        val samples = withLeadingStill(obliqueSet(10, 2.3, 0.8), 2.0)
        val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("cable_seated_row"))
        detector.beginCounting()
        val firstReportedAt = samples.firstOrNull { detector.add(it) != null }
        val startNs = samples.first().timestampNs
        assertTrue(firstReportedAt != null && firstReportedAt.timestampNs - startNs < 4_500_000_000L + 0L,
            "first rep must be reported before the second rep finishes")
    }

    @Test
    fun theTwoLobesOfOneFirstRepAreNotCountedTwice() {
        val axis = doubleArrayOf(0.40, 0.70, -0.59)
        val hz = 50
        // A first rep whose pull and return lobes are one second apart, then regular 2.3 s reps.
        val peaksAt = listOf(1.0, 2.0) + (0 until 9).map { 4.3 + it * 2.3 }
        val samples = (0 until 28 * hz).map { i ->
            val t = i.toDouble() / hz
            val wave = peaksAt.sumOf { 0.8 * kotlin.math.exp(-((t - it) * (t - it)) / (2 * 0.12 * 0.12)) }
            SensorSample(1_000_000_000L + (t * 1e9).toLong(), 0f, 0f, 9.8f,
                (wave * axis[0]).toFloat(), (wave * axis[1]).toFloat(), (wave * axis[2]).toFloat(), 3)
        }
        assertEquals(10, count("cable_seated_row", samples))
    }

    @Test
    fun rowsLateralRaisesAndTricepsUseTheCycleAxisEngine() {
        for (id in listOf("seated_row", "cable_seated_row", "barbell_row", "one_arm_dumbbell_row", "lying_tbar_row",
            "dumbbell_lateral_raise", "one_arm_cable_lateral", "dumbbell_triceps_extension")) {
            val profile = ExerciseTrackingProfiles.resolve(id)
            assertEquals(MotionFamily.CYCLE_AXIS_RETURN, profile.family, id)
            assertEquals("cycle-axis-return-family-v1", profile.id, id)
        }
        assertEquals(MotionFamily.PULL_RETURN, ExerciseTrackingProfiles.resolve("lat_pull_down").family)
    }

    @Test
    fun aFrozenPullReturnRecordingOfTheSameExerciseStillDecodes() {
        val recorded = "1|cable_seated_row|pull-return-family-v4|WRIST_IMU_CAUTION|COMBINED_MOTION|500|6000|650|0.0|0.15|" +
            "케이블 시티드 로우 · 당김-복귀 공통 측정|B_AUTO_COUNT_LIMITED_METRICS|AUTO_LIMITED_METRICS|PULL_RETURN|0.14|0.07|120|2200|0.0|||0.0|"
        val decoded = TrackingProfileSnapshot.decode(recorded)
        assertEquals(MotionFamily.PULL_RETURN, decoded.family)
        assertEquals("pull-return-family-v4", decoded.id)
    }

    @Test
    fun `replay on labelled MM-Fit rows lateral raises and triceps`() {
        val configured = System.getenv("KCOACH_MMFIT_DIR")
        assumeTrue(!configured.isNullOrBlank(), "MM-Fit dataset not configured; accuracy replay not run")
        val dir = File(requireNotNull(configured))
        val ids = mapOf("dumbbell_rows" to "one_arm_dumbbell_row", "lateral_shoulder_raises" to "dumbbell_lateral_raise",
            "tricep_extensions" to "dumbbell_triceps_extension")
        val errors = ids.keys.associateWith { mutableListOf<Int>() }
        dir.listFiles { f -> f.name.matches(Regex("w\\d+\\.csv")) }!!.sorted().forEach { csv ->
            val samples = CurrentDetectorReplay.readSamples(csv)
            File(dir, csv.nameWithoutExtension + "_labels.csv").useLines { lines ->
                lines.drop(1).forEach { line ->
                    val p = line.split(',')
                    val start = p[0].toLong(); val end = p[1].toLong(); val expected = p[2].toInt()
                    val id = ids[p[3]] ?: return@forEach
                    val window = samples.filter { it.timestampNs in (start - 1_000_000_000L)..(end + 2_000_000_000L) }
                    if (window.size < 40) return@forEach
                    val detector = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve(id))
                    detector.beginCounting()
                    val events = buildList { window.forEach { detector.add(it)?.let(::add) }; addAll(detector.finishAll()) }
                    errors.getValue(p[3]) += events.count { it.endTimestampNs in start..(end + 2_000_000_000L) } - expected
                }
            }
        }
        errors.forEach { (exercise, list) ->
            val within1 = list.count { abs(it) <= 1 } * 100.0 / list.size
            println("cycle-axis replay $exercise: ${list.size} sets, exact ${list.count { it == 0 } * 100 / list.size}%, within1 ${"%.0f".format(within1)}%")
            assertTrue(within1 >= 80.0, "$exercise within-one rate $within1")
        }
    }
}
