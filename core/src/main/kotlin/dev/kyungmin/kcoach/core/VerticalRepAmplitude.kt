package dev.kyungmin.kcoach.core

/**
 * Runtime-only size floor for vertical press reps; the frozen v1 detector and its replays are unchanged.
 * The detector's own floor (0.04 per lobe) is a few centimetres, so a small arm lift (reaching for the phone) counted.
 * [motionDisplacement] is the ascent plus return lobe area of the velocity proxy (not physical travel; roughly 2 m per unit
 * on one Smith capture, so 0.24 is under half a full press travel). Real reps: Smith capture 0.48-0.68, MM-Fit 1st percentile 0.41, weakest MM-Fit subject median 0.62.
 * On the 558 MM-Fit reps the floor drops 0.2% (1 rep). The value is a tunable starting point, not a validated constant.
 */
object VerticalRepAmplitude {
    const val MIN_TOTAL_DISPLACEMENT = 0.24

    fun accepts(event: ProfiledMotionRepEvent): Boolean = event.motionDisplacement >= MIN_TOTAL_DISPLACEMENT
}
