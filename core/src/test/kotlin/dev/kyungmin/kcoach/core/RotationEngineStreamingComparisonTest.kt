package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Diagnostic comparison, not human-motion accuracy evidence. No end-of-stream flush is allowed. */
class RotationEngineStreamingComparisonTest {
    private data class Case(val name: String, val halfSeconds: Double, val reps: Int = 5,
        val topHoldSeconds: Double = 0.0, val bottomHoldSeconds: Double = 0.0, val returnArm: Boolean = true)
    private data class Delivery(val now: Double, val eventEnd: Double)

    @Test fun compareActualDeliveryTimesOnTheSameAngularStream() {
        val cases = listOf(
            Case("normal-2.4s", 1.2), Case("fast-1.2s", 0.6), Case("slow-6s", 3.0),
            Case("top-hold-0.6s", 1.2, topHoldSeconds = 0.6),
            Case("bottom-hold-0.4s", 1.2, bottomHoldSeconds = 0.4),
            Case("outward-only", 1.2, reps = 1, returnArm = false),
        )
        for (case in cases) {
            val samples = mutableListOf<SensorSample>()
            val bottoms = mutableListOf<Double>()
            var angle = 0.0
            fun segment(seconds: Double, rateAt: (Double) -> Double) {
                repeat((seconds * 50).toInt()) { i ->
                    val rate = rateAt((i + 0.5) / 50.0)
                    angle += rate / 50.0
                    // Orientation-only fixture: gravity rotates in the watch frame. Arm translation,
                    // impacts, sensor noise and OS/haptic scheduling are deliberately not simulated.
                    samples += SensorSample(1_000_000_000L + samples.size * 20_000_000L,
                        0f, (9.80665 * sin(angle)).toFloat(), (9.80665 * cos(angle)).toFloat(),
                        rate.toFloat(), 0f, 0f, 3)
                }
            }
            segment(1.0) { 0.0 }
            repeat(case.reps) {
                // Each half sweeps 90 degrees with a smooth half-sine angular velocity.
                val peak = (PI / 2) * PI / (2 * case.halfSeconds)
                segment(case.halfSeconds) { peak * sin(PI * it / case.halfSeconds) }
                segment(case.topHoldSeconds) { 0.0 }
                if (case.returnArm) {
                    segment(case.halfSeconds) { -peak * sin(PI * it / case.halfSeconds) }
                    bottoms += samples.size / 50.0
                    segment(case.bottomHoldSeconds) { 0.0 }
                }
            }
            segment(2.0) { 0.0 } // Real observed still samples, not a finish() operation.
            val rotation = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
            val cycle = ProfiledMotionRepDetector(ExerciseTrackingProfiles.resolve("dumbbell_lateral_raise"))
            rotation.beginCounting(); cycle.beginCounting()
            val rotationDeliveries = mutableListOf<Delivery>()
            val cycleDeliveries = mutableListOf<Delivery>()
            samples.forEach { sample ->
                val now = (sample.timestampNs - 1_000_000_000L) / 1e9
                rotation.add(sample)?.let {
                    assertTrue(it.endTimestampNs <= sample.timestampNs)
                    rotationDeliveries += Delivery(now, (it.endTimestampNs - 1_000_000_000L) / 1e9)
                }
                cycle.add(sample)?.let {
                    assertTrue(it.endTimestampNs <= sample.timestampNs)
                    cycleDeliveries += Delivery(now, (it.endTimestampNs - 1_000_000_000L) / 1e9)
                }
            }
            fun report(engine: String, deliveries: List<Delivery>) {
                val offsets = if (bottoms.size == deliveries.size) deliveries.zip(bottoms).joinToString(",") {
                    "%.2f".format(java.util.Locale.US, it.first.now - it.second)
                } else "unmatched"
                println("STREAM ${case.name} $engine expected=${bottoms.size} emitted=${deliveries.size}" +
                    " actualDeliverySeconds=${deliveries.joinToString(",") { "%.2f".format(java.util.Locale.US, it.now) }}" +
                    " deliveryMinusReturnSeconds=$offsets")
            }
            report("rotation-v4", rotationDeliveries)
            report("cycle-current-working-tree", cycleDeliveries)
        }
    }
}
