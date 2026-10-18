package dev.kyungmin.kcoach.core

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Offline adapter only: no thresholds, model selection, or ground-truth-driven early stopping. */
internal object CurrentDetectorReplay {
    data class Result(val watch: Int?, val phone: Int?, val selected: Int?, val score: Double, val quality: Double?)

    // Map dataset exercise identities once, before looking at results. Lower-body and unknown
    // exercises use the app's manual policy; an unsupported count is never scored as zero.
    fun exerciseId(datasetExercise: String): String = when (datasetExercise) {
        "bicep_curls" -> "dumbbell_biceps_curl"
        "dumbbell_rows" -> "dumbbell_row"
        "dumbbell_shoulder_press" -> "dumbbell_shoulder_press"
        "lateral_shoulder_raises" -> "dumbbell_lateral_raise"
        "tricep_extensions" -> "dumbbell_triceps_extension"
        else -> datasetExercise
    }

    /** The watch reads the latest acceleration when a gyroscope event arrives. */
    fun readSamples(csv: File): List<SensorSample> = buildList {
        var acceleration: FloatArray? = null
        var accelerationAt = 0L
        var lastRowAt = 0L
        var lastGyroAt = 0L
        csv.useLines { lines ->
            require(lines.first() == "sensor,t_ns,x,y,z") { "Unexpected MM-Fit CSV header" }
        }
        csv.useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.split(',')
                require(fields.size == 5)
                val sensor = fields[0].toInt()
                val timestamp = fields[1].toLong()
                val values = fields.drop(2).map(String::toFloat).toFloatArray()
                require(timestamp > 0 && timestamp >= lastRowAt && values.all(Float::isFinite))
                require(sensor == 0 || sensor == 1)
                lastRowAt = timestamp
                if (sensor == 0) {
                    acceleration = values
                    accelerationAt = timestamp
                } else {
                    val accel = acceleration
                    if (accel != null && timestamp - accelerationAt <= 100_000_000L && timestamp > lastGyroAt) {
                        add(SensorSample(timestamp, accel[0], accel[1], accel[2], values[0], values[1], values[2]))
                        lastGyroAt = timestamp
                    }
                }
            }
        }
    }

    /** Preserve gaps; do not interpolate missing samples into apparently perfect sensor quality. */
    fun capture(samples: List<SensorSample>): SensorCaptureDocument {
        return SensorCaptureDocument(SensorCaptureFormat.FORMAT_VERSION, 50.0, samples.map(::SensorCaptureRecord))
    }

    fun evaluate(samples: List<SensorSample>, exerciseId: String): Result {
        val profile = ExerciseTrackingProfiles.resolve(exerciseId)
        val document = capture(samples)
        if (profile.mode == TrackingMode.MANUAL_REQUIRED) {
            val result = PhoneRepAnalyzer.analyze(document, profile).result
            return Result(null, result.phoneReanalyzedReps, result.confirmedReps, result.analysisConfidence, result.signalQuality)
        }
        // These are labelled, already-started sets. The service start gate, target-rep auto-stop,
        // Bluetooth and OS lifecycle are outside this detector-only benchmark.
        val watch = if (profile.signal == TrackingSignal.FOREARM_ROTATION) {
            val detector = ForearmRotationRepDetector(profile)
            detector.beginCounting()
            samples.count { detector.add(it) != null } + if (detector.finish() != null) 1 else 0
        } else {
            val detector = ProfiledMotionRepDetector(profile)
            detector.beginCounting()
            samples.count { detector.add(it) != null } + if (detector.finish() != null) 1 else 0
        }
        val result = PhoneRepAnalyzer.analyze(document, profile, watch).result
        return Result(watch, result.phoneReanalyzedReps, result.confirmedReps, result.analysisConfidence, result.signalQuality)
    }
}

class CurrentDetectorEvaluationTest {
    private data class SetResult(val session: String, val exercise: String, val id: String, val expected: Int, val result: CurrentDetectorReplay.Result)

    @Test fun `replay current watch detector and phone analyzer on labelled MM-Fit sets`() {
        val configured = System.getenv("KCOACH_MMFIT_DIR")
        assumeTrue(!configured.isNullOrBlank(), "MM-Fit dataset not configured; accuracy evaluation not run")
        val dir = File(requireNotNull(configured))
        val files = dir.listFiles { f -> f.name.matches(Regex("w\\d+\\.csv")) }?.sorted().orEmpty()
        require(files.isNotEmpty()) { "No converted MM-Fit sessions at $dir" }
        val results = mutableListOf<SetResult>()
        files.forEach { csv ->
            val samples = CurrentDetectorReplay.readSamples(csv)
            File(dir, csv.nameWithoutExtension + "_labels.csv").useLines { lines ->
                lines.drop(1).forEach { line ->
                    val p = line.split(',')
                    require(p.size == 4)
                    val start = p[0].toLong(); val end = p[1].toLong(); val expected = p[2].toInt()
                    require(end > start && expected >= 0)
                    val setSamples = samples.filter { it.timestampNs in start..end }
                    require(setSamples.isNotEmpty()) { "Empty labelled set ${csv.name}: $line" }
                    val id = CurrentDetectorReplay.exerciseId(p[3])
                    results += SetResult(csv.nameWithoutExtension, p[3], id, expected, CurrentDetectorReplay.evaluate(setSamples, id))
                }
            }
        }
        require(results.isNotEmpty())
        val report = buildString {
            appendLine("# Current KCoach detector replay — MM-Fit left wrist")
            appendLine()
            appendLine("${results.size} labelled sets, ${files.size} sessions. Frozen app profiles; no tuning in this evaluation.")
            appendLine("Watch lane: current detector at source gyro times. Phone lane: current PhoneRepAnalyzer on the SAME samples the watch counts; original timestamps and gaps retained, no callback-time throttling or interpolation. Sensor request remains 50Hz; source MM-Fit data is 100Hz.")
            appendLine("Known set boundaries are supplied. Preparation gate, target-rep auto-stop, transport, personal calibration, and Watch8 hardware are NOT evaluated.")
            appendLine("Previously explored public dataset; this is NOT independent held-out validation. Alternating-arm biceps curls have BOTH-arm labels: their single-wrist accuracy is N/A, not an error against the total label. Per-set estimates remain in CSV for inspection (MM-Fit paper section 5.3).")
            appendLine("Manual-only exercises stay N/A. Auto-selected means the internal score passed the app threshold, NOT user confirmation or proven accuracy.")
            appendLine()
            appendLine("| exercise | sets | watch MAE | watch ±1 | phone MAE | phone ±1 | auto-selected sets | selected MAE | selected ±1 |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
            results.groupBy { it.exercise }.toSortedMap().forEach { (exercise, sets) ->
                fun errors(select: (CurrentDetectorReplay.Result) -> Int?) = sets.mapNotNull { row ->
                    if (row.exercise == "bicep_curls") null else select(row.result)?.let { abs(it - row.expected) }
                }
                val watch = errors { it.watch }; val phone = errors { it.phone }; val selected = errors { it.selected }
                fun mae(errors: List<Int>) = if (errors.isEmpty()) "N/A" else "%.2f".format(Locale.US, errors.average())
                fun withinOne(errors: List<Int>) = if (errors.isEmpty()) "N/A" else "%.1f%%".format(Locale.US, 100.0 * errors.count { it <= 1 } / errors.size)
                appendLine("| $exercise | ${sets.size} | ${mae(watch)} | ${withinOne(watch)} | ${mae(phone)} | ${withinOne(phone)} | ${sets.count { it.result.selected != null }}/${sets.size} | ${mae(selected)} | ${withinOne(selected)} |")
            }
            appendLine()
            appendLine("MAE = mean absolute repetition-count error. Selected-only accuracy must always be read alongside selection coverage.")
        }
        File("build/current-mmfit-report.md").writeText(report)
        File("build/current-mmfit-sets.csv").writeText(buildString {
            appendLine("session,dataset_exercise,app_exercise,dataset_label_reps,watch,phone,auto_selected,heuristic_score,signal_quality,label_scope")
            results.forEach { row ->
                val r = row.result
                appendLine("${row.session},${row.exercise},${row.id},${row.expected},${r.watch ?: ""},${r.phone ?: ""},${r.selected ?: ""},${r.score},${r.quality ?: ""},${if (row.exercise == "bicep_curls") "both_arms_alternating" else "set_reps"}")
            }
        })
        println(report)
    }

    @Test fun `manual exercises are unavailable rather than zero-error automatic counts`() {
        val result = CurrentDetectorReplay.evaluate(listOf(sample(1_000_000_000L)), "squats")
        assertNull(result.watch); assertNull(result.phone); assertNull(result.selected)
        assertEquals("dumbbell_triceps_extension", CurrentDetectorReplay.exerciseId("tricep_extensions"))
    }

    @Test fun `capture preserves every sensor sample and long missing intervals`() {
        val samples = listOf(0L, 10L, 20L, 30L, 1000L).map { sample(1_000_000_000L + it * 1_000_000L) }
        val document = CurrentDetectorReplay.capture(samples)
        assertEquals(samples.map { it.timestampNs }, document.records.map { it.sample.timestampNs })
        assertTrue(document.validateTimestamps().isEmpty())
        assertEquals(50.0, document.sampleRateRequestedHz)
    }

    @Test fun `pairing uses preceding acceleration and never future or stale values`() {
        val csv = File.createTempFile("mmfit-pairing", ".csv")
        try {
            csv.writeText("sensor,t_ns,x,y,z\n1,1000000000,1,0,0\n0,1001000000,0,0,9.8\n1,1002000000,2,0,0\n1,1202000000,3,0,0\n")
            val samples = CurrentDetectorReplay.readSamples(csv)
            assertEquals(1, samples.size)
            assertEquals(1_002_000_000L, samples.single().timestampNs)
            assertEquals(9.8f, samples.single().accelerationZ)
            assertEquals(2f, samples.single().gyroscopeX)
        } finally { csv.delete() }
    }

    @Test fun `submillisecond timestamp rounding does not discard regular 50Hz samples`() {
        val samples = (0..20).map { i -> sample(1_000_000_000L + i * 20_000_000L + if (i % 2 == 0) 128L else -128L) }
        assertEquals(samples, CurrentDetectorReplay.capture(samples).records.map { it.sample })
    }

    @Test fun `a complete clean three-repetition fixture reaches both actual app analyzers`() {
        var t = 1_000_000_000L
        val samples = buildList {
            repeat(3) {
                for (rate in listOf(1.2f, 0f, -1.2f, 0f)) repeat(50) {
                    add(SensorSample(t, 0f, 0f, 9.8f, rate, 0f, 0f))
                    t += 20_000_000L
                }
            }
        }
        val result = CurrentDetectorReplay.evaluate(samples, "lat_pull_down")
        assertEquals(3, result.watch)
        assertEquals(3, result.phone)
        assertEquals(3, result.selected)
        assertEquals(1.0, result.quality)
    }

    private fun sample(t: Long) = SensorSample(t, 0f, 0f, 9.8f, 0f, 0f, 0f)
}
