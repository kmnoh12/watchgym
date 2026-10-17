package dev.kyungmin.kcoach.wear

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** The workout face in real accessible Android views; coordinates follow the 432-unit round design grid. */
internal class WatchWorkoutFace(context: Context) : FrameLayout(context) {
    private val positions = mutableMapOf<View, android.graphics.Rect>()
    /** Lines placed by their baseline, the way the design sheet's line-height:1 boxes put them (design units). */
    private val baselines = HashMap<TextView, Float>()
    /** Lines that shrink to fit their box instead of being cut (three-digit counts, long notices). */
    private val fitting = HashSet<TextView>()
    /** Text sizes live on the 432-unit design grid (in the old sp numbers) and scale with the screen, not the user's density. */
    private val designSp = HashMap<TextView, Float>()
    fun size(view: TextView, sp: Float) { designSp[view] = sp; applyTextSize(view) }
    private fun applyTextSize(view: TextView) {
        val sp = designSp[view] ?: return
        val scale = minOf(width, height) / 432f
        if (scale <= 0f) { view.textSize = sp; return }
        var px = sp * 2f * scale
        val box = positions[view]?.width()?.times(scale)
        if (view in fitting && box != null && box > 0f) {
            view.paint.textSize = px
            val wanted = android.text.Layout.getDesiredWidth(view.text, view.paint)
            if (wanted > box) px *= box / wanted * .98f
        }
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HAIRLINE; strokeWidth = 1.5f }
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val clock = label(13f, WHITE, 116, 42, 316, 72)
    val title = label(17f, WHITE, 63, 74, 369, 110, true).apply { maxLines = 1 }
    val subtitle = label(11f, MUTED, 52, 72, 380, 142)
    val jog = JogBar(context).also { positions[it] = android.graphics.Rect(48, 182, 384, 262); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    /** Design 4a: the left button is "정지" (and "계속" while the rest is paused), the right one skips the rest. */
    val restPause = RestPill(context, false).also { positions[it] = android.graphics.Rect(67, 280, 205, 350); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    val restSkip = RestPill(context, true).also { positions[it] = android.graphics.Rect(227, 280, 365, 350); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    val chevronUp = ChevronView(context, true).also { positions[it] = android.graphics.Rect(199, 14, 233, 31); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    val chevronDown = ChevronView(context, false).also { positions[it] = android.graphics.Rect(199, 398, 233, 415); addView(it, LayoutParams(1, 1)); it.visibility = GONE }
    val valueGhost = label(88f, SAGE, 50, 140, 270, 320, false).apply { alpha = 0f; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val value = label(88f, SAGE, 50, 140, 270, 320, false)
    val target = label(15f, SOFT, 270, 232, 382, 292)
    val heart = HeartView(context).also { positions[it] = android.graphics.Rect(232, 340, 266, 374); addView(it, LayoutParams(1, 1)) }
    /** Design 1-B: the only thing below the divider is the heart, its rate at 37.4 units and a small muted "bpm". */
    val heartRate = label(18.7f, WHITE, 180, 336, 260, 378, false)
    val bpm = label(10f, MUTED, 250, 336, 300, 378, false).apply { text = "bpm" }
    val hint = label(11f, MUTED, 76, 370, 356, 400).apply { isClickable = true }
    val down = label(12f, MUTED, 180, 391, 252, 419)
    val action = label(16f, Color.rgb(23, 22, 18), 76, 280, 356, 376, true).apply {
        tag = "phone-result"; isClickable = true; isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(BONE); cornerRadius = 6f * resources.displayMetrics.density
        }
    }
    init {
        listOf(heartRate, clock, value, valueGhost).forEach { it.typeface = medium }
        // Design baselines: top + 0.841 x size for the sheet's line-height:1 text.
        baselines[clock] = 63.9f; baselines[title] = 100.6f; baselines[subtitle] = 134.5f
        baselines[hint] = 392f
        fitting += listOf(value, valueGhost, hint, subtitle)
    }
    var showDivider = true
    init { setBackgroundColor(Color.BLACK); setWillNotDraw(false) }
    private fun label(sp: Float, color: Int, left: Int, top: Int, right: Int, bottom: Int, bold: Boolean = false) =
        TextView(context).apply {
            textSize = sp; setTextColor(color); gravity = Gravity.CENTER; includeFontPadding = false
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            addView(this, LayoutParams(1, 1)); positions[this] = android.graphics.Rect(left, top, right, bottom)
            designSp[this] = sp
        }
    private fun place(view: View, l: Int, t: Int, r: Int, b: Int) {
        val rect = android.graphics.Rect(l, t, r, b)
        if (positions[view] != rect) { positions[view] = rect; requestLayout() }
    }
    private fun baseline(view: TextView, line: Float?) {
        if (baselines[view] == line) return
        if (line == null) baselines.remove(view) else baselines[view] = line
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scale = minOf(measuredWidth, measuredHeight) / 432f
        designSp.keys.forEach { applyTextSize(it) }
        // Long exercise names shrink to fit one line, between 11 and 17 design sp.
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, (22f * scale).toInt().coerceAtLeast(8), (34f * scale).toInt().coerceAtLeast(10), 1, android.util.TypedValue.COMPLEX_UNIT_PX)
        positions.forEach { (view, rect) ->
            var height = (rect.height() * scale).toInt()
            if (view === action) height = maxOf(height, (48 * resources.displayMetrics.density).toInt())
            // A line of text needs about 1.4 times its size; a tighter box would cut the bottom of Korean glyphs.
            if (view is TextView && view !== title) height = maxOf(height, (view.textSize * 1.4f).toInt())
            if (view === title) height = maxOf(height, (34f * scale * 1.4f).toInt())
            if (view is TextView && view in baselines) height = if (view === title) (34f * scale * 1.6f).toInt() else (view.textSize * 1.6f).toInt()
            view.measure(MeasureSpec.makeMeasureSpec((rect.width() * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val side = minOf(width, height); val scale = side / 432f
        val offsetX = (width - side) / 2; val offsetY = (height - side) / 2
        positions.forEach { (view, rect) ->
            val l = offsetX + (rect.left * scale).toInt(); val r = offsetX + (rect.right * scale).toInt()
            val centreY = offsetY + (rect.centerY() * scale)
            val line = (view as? TextView)?.let { baselines[it] }
            if (view === action) {
                val bottomPx = offsetY + (rect.bottom * scale).toInt()
                view.layout(l, bottomPx - view.measuredHeight, r, bottomPx)
            } else if (view is TextView && line != null) {
                val t = Math.round(offsetY + line * scale - view.baseline)
                view.layout(l, t, r, t + view.measuredHeight)
            } else if (view is TextView) {
                // Keep the text where the design puts it even when the box grew to avoid clipping.
                val half = view.measuredHeight / 2f
                view.layout(l, (centreY - half).toInt(), r, (centreY + half).toInt())
            } else view.layout(l, offsetY + (rect.top * scale).toInt(), r, offsetY + (rect.bottom * scale).toInt())
        }
        applyRestMorph()
    }
    /**
     * 0 while the rest runs, 1 once it is paused (design 4-1). The time shrinks to 85 % and turns white, the
     * "정지" button becomes the yellow "계속", the label fades in, the ten-second bar dims and the heart arrow goes.
     */
    var restMorph = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            applyRestMorph()
        }

    /** Puts the rest screen into the state [restMorph] describes; the pivots follow the line box of the time (centre 216, 126). */
    fun applyRestMorph() {
        if (mode != 2) return
        val p = restMorph
        val side = minOf(width, height); val scale = side / 432f
        val offsetX = (width - side) / 2f; val offsetY = (height - side) / 2f
        val shrink = 1f - .148f * p
        listOf(value, valueGhost).forEach {
            it.setTextColor(blend(YELLOW, WHITE, p))
            it.scaleX = shrink; it.scaleY = shrink; it.translationY = 12f * scale * p
            if (it.width > 0) { it.pivotX = offsetX + 216f * scale - it.left; it.pivotY = offsetY + 126f * scale - it.top }
        }
        subtitle.alpha = p; subtitle.translationY = 6f * scale * (1f - p)
        jog.alpha = 1f - .65f * p; jog.isEnabled = p < .5f
        chevronDown.alpha = 1f - p
        restPause.resume = p
    }

    /** Back to the plain state when the screen is not the rest screen. */
    private fun resetRestMorph() {
        restMorph = 0f
        listOf(value, valueGhost).forEach { it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f }
        subtitle.alpha = 1f; subtitle.translationY = 0f
        jog.alpha = 1f; jog.isEnabled = true
        chevronDown.alpha = 1f
        restPause.resume = 0f
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt())

    private var mode = -1
    /** The counter sits left of its "/ 12회" while measuring and is centred (and wider) for the rest timer and home. */
    fun primary(mode: Int) {
        this.mode = mode
        when (mode) {
            // Design 4a: a 108-unit bold time whose line box starts at 72, so the baseline is 72 + 0.841 x 108.
            2 -> listOf(value, valueGhost).forEach { place(it, 44, 64, 388, 200); baseline(it, 162.8f); it.letterSpacing = -3f / 108f; it.typeface = Typeface.DEFAULT_BOLD }
            1 -> listOf(value, valueGhost).forEach { place(it, 44, 120, 388, 300); baseline(it, null); it.letterSpacing = 0f; it.typeface = medium }
            else -> listOf(value, valueGhost).forEach { it.letterSpacing = -4f / 176f; it.typeface = medium }
        }
        // The rest label ("휴식 · 일시정지") has its line box at 70 with a 22-unit size.
        baseline(subtitle, if (mode == 2) 88.5f else 134.5f)
        if (mode != 2) resetRestMorph()
    }

    /**
     * The design's measuring row: a 204-wide number window, a 14 gap and "/ 12회", centred together. A word in
     * place of the count ("준비") sits higher, in the middle of the window.
     */
    fun layoutCount() {
        if (mode != 0) return
        val ofWidth = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }.measureText(target.text.toString())
        val left = (216f - (204f + 14f + ofWidth) / 2f).toInt()
        val numeric = value.text.firstOrNull()?.isDigit() == true || value.text.toString() == "—"
        listOf(value, valueGhost).forEach { place(it, left, 140, left + 204, 320); baseline(it, if (numeric) 290f else 262f) }
        place(target, left + 218 - 6, 250, left + 218 + ofWidth.toInt() + 6, 290); baseline(target, 278.8f)
    }

    /**
     * The heart row of design 1-B: a 35.6-unit heart, 6 units, the rate (37.4 units, medium), 6 units, "bpm" (20 units),
     * centred as one group; the rate and "bpm" share the baseline at 373 and the heart is centred on the line box (359.9).
     */
    fun layoutMetrics() {
        val rate = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 37.4f }
        val unit = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
        val rateWidth = rate.measureText(heartRate.text.toString())
        val unitWidth = unit.measureText(bpm.text.toString())
        val showHeart = heart.visibility == VISIBLE
        val heartBox = 35.6f
        val total = (if (showHeart) heartBox + 6f else 0f) + rateWidth + 6f + unitWidth
        var x = 216f - total / 2f
        if (showHeart) { place(heart, x.toInt(), 342, (x + heartBox).toInt(), 378); x += heartBox + 6f }
        place(heartRate, (x - 4).toInt(), 336, (x + rateWidth + 4).toInt(), 378); baseline(heartRate, 373f)
        x += rateWidth + 6f
        place(bpm, (x - 4).toInt(), 336, (x + unitWidth + 4).toInt(), 378); baseline(bpm, 373f)
    }

    /**
     * The status line of the measuring screen: "1세트 · 20kg · 자동 측정" where the set and the weight are bright and the
     * rest is muted (design 1); [weight] is left out for body-weight exercises. A dot in front marks a live measurement.
     */
    fun setStatusLine(set: String, weight: String?, mode: String, dot: Int?) {
        subtitle.setTextColor(MUTED); size(subtitle, 11f)
        subtitle.typeface = Typeface.DEFAULT
        subtitle.setCompoundDrawablesRelative(null, null, null, null)
        val text = android.text.SpannableStringBuilder()
        if (dot != null) text.append("\u200b")
        fun bright(part: String) {
            val start = text.length; text.append(part)
            text.setSpan(android.text.style.ForegroundColorSpan(WHITE), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.TypefaceSpan("sans-serif-medium"), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        bright(set); text.append(" · ")
        if (weight != null) { bright(weight); text.append(" · ") }
        text.append(mode)
        if (dot != null) {
            val scale = minOf(width, height).coerceAtLeast(1) / 432f
            text.setSpan(DotSpan(dot, (9f * scale).toInt().coerceAtLeast(6), (10f * scale).toInt().coerceAtLeast(6)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        subtitle.text = text
    }

    /** The status line under the exercise name; a small coloured dot in front of it marks a live measurement. */
    fun setSubtitle(text: String, color: Int, dot: Int? = null, sizeSp: Float = 11f, bold: Boolean = false, medium: Boolean = false) {
        subtitle.setTextColor(color); size(subtitle, sizeSp)
        subtitle.typeface = when { bold -> Typeface.DEFAULT_BOLD; medium -> this.medium; else -> Typeface.DEFAULT }
        subtitle.setCompoundDrawablesRelative(null, null, null, null)
        if (dot == null) subtitle.text = text
        else {
            val scale = minOf(width, height).coerceAtLeast(1) / 432f
            // A zero-width placeholder carries the dot so it stays next to the text however long the text is.
            subtitle.text = android.text.SpannableString("​$text").apply {
                setSpan(DotSpan(dot, (9f * scale).toInt().coerceAtLeast(6), (10f * scale).toInt().coerceAtLeast(6)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** A tappable notice (retained counts, an update waiting for approval) shown in the hint line. */
    var noticeAction: (() -> Unit)? = null
    fun homeLayout(home: Boolean) {
        // At home the notice is two lines in the middle; otherwise one line that ends above the bottom arrow.
        val rect = if (home) android.graphics.Rect(60, 290, 372, 377) else android.graphics.Rect(76, 370, 356, 400)
        if (positions[hint] != rect) { positions[hint] = rect; hint.maxLines = if (home) 2 else 1; requestLayout() }
        baseline(hint, if (home) null else 392f)
        if (home) fitting -= hint else fitting += hint
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height); val scale = side / 432f
        val x = (width - side) / 2f; val y = (height - side) / 2f
        if (showDivider) {
            divider.strokeWidth = 1.5f * scale
            canvas.drawLine(x + 96 * scale, y + 322.75f * scale, x + 336 * scale, y + 322.75f * scale, divider)
        }
    }
    companion object {
        val BONE = Color.rgb(237, 232, 220)
        val SAGE = Color.rgb(118, 219, 185)
        /** Rest: a soft yellow. */
        val YELLOW = Color.rgb(255, 222, 112)
        /** Over the target: a light amber, kept distinct from the rest yellow. */
        val ORANGE = Color.rgb(255, 180, 94)
        /** The second lap of the over-target ring. */
        val DEEP_AMBER = Color.rgb(217, 121, 31)
        /** Heart-rate zone 4 stays the strong orange. */
        private val ZONE_ORANGE = Color.rgb(255, 160, 51)
        val RED = Color.rgb(229, 72, 59)
        val WHITE = Color.rgb(237, 232, 220)
        val MUTED = Color.rgb(143, 139, 130)
        val SOFT = Color.rgb(163, 159, 152)
        val CARD = Color.rgb(28, 27, 25)
        val HAIRLINE = Color.rgb(30, 29, 26)
        val CONTROL = Color.rgb(52, 50, 45)
        val LARGE_STEP = Color.rgb(75, 72, 65)
        val PREVIOUS_SELECTED = Color.rgb(64, 62, 58)
        val SAGE_WASH = Color.rgb(15, 42, 34)
        val TRACK = Color.rgb(27, 27, 26)
        /** Heart-rate zone colours, zone 1..5. The bands are a placeholder until a max heart rate is known. */
        private val ZONES = intArrayOf(Color.rgb(124, 142, 156), Color.rgb(77, 163, 255), Color.rgb(79, 209, 139), ZONE_ORANGE, RED)
        private const val ASSUMED_MAX_HEART_RATE = 185
        fun zone(heartRate: Int): Int = when {
            heartRate <= 0 -> 0
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 50 -> 1
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 60 -> 1
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 70 -> 2
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 80 -> 3
            heartRate * 100 < ASSUMED_MAX_HEART_RATE * 90 -> 4
            else -> 5
        }
        fun zoneColor(heartRate: Int): Int = ZONES[(zone(heartRate).coerceAtLeast(1)) - 1]
    }
}

/** A filled heart that beats once per heartbeat; only the heart itself is redrawn, never the whole face. */
internal class HeartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private var animator: ObjectAnimator? = null
    private var periodMs = 0L

    init { setLayerType(LAYER_TYPE_HARDWARE, null); visibility = INVISIBLE }

    /** Shows [bpm] in its zone colour and beats at that rate; 0 hides the heart and stops the beat. */
    fun show(bpm: Int) {
        if (bpm <= 0) { stop(); visibility = INVISIBLE; return }
        paint.color = WatchWorkoutFace.zoneColor(bpm)
        invalidate()
        visibility = VISIBLE
        val period = (60_000L / bpm).coerceIn(300L, 1_500L)
        if (animator?.isRunning == true && kotlin.math.abs(period - periodMs) < 40) return
        periodMs = period
        animator?.cancel()
        pivotX = width / 2f; pivotY = height * 0.55f
        val beat = arrayOf(Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(.14f, 1.22f), Keyframe.ofFloat(.28f, 1f),
            Keyframe.ofFloat(.42f, 1.12f), Keyframe.ofFloat(.56f, 1f), Keyframe.ofFloat(1f, 1f))
        animator = ObjectAnimator.ofPropertyValuesHolder(this,
            PropertyValuesHolder.ofKeyframe(SCALE_X, *beat), PropertyValuesHolder.ofKeyframe(SCALE_Y, *beat.map { Keyframe.ofFloat(it.fraction, it.value as Float) }.toTypedArray())).apply {
            duration = period; repeatCount = ObjectAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator(); start()
        }
    }

    private fun stop() { animator?.cancel(); animator = null; periodMs = 0L; scaleX = 1f; scaleY = 1f }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // No beating while the screen is off or the app is in the background.
        if (!isVisible) animator?.pause() else animator?.takeIf { it.isPaused }?.resume()
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.strokeWidth = w * 0.05f
        path.reset()
        // Material "favorite" outline on a 24 unit grid.
        val s = minOf(w, h) / 24f
        val ox = (w - 24f * s) / 2f; val oy = (h - 24f * s) / 2f
        fun px(v: Float) = ox + v * s
        fun py(v: Float) = oy + v * s
        path.moveTo(px(12f), py(21.35f))
        path.lineTo(px(10.55f), py(20.03f))
        path.cubicTo(px(5.4f), py(15.36f), px(2f), py(12.28f), px(2f), py(8.5f))
        path.cubicTo(px(2f), py(5.42f), px(4.42f), py(3f), px(7.5f), py(3f))
        path.cubicTo(px(9.24f), py(3f), px(10.91f), py(3.81f), px(12f), py(5.09f))
        path.cubicTo(px(13.09f), py(3.81f), px(14.76f), py(3f), px(16.5f), py(3f))
        path.cubicTo(px(19.58f), py(3f), px(22f), py(5.42f), px(22f), py(8.5f))
        path.cubicTo(px(22f), py(12.28f), px(18.6f), py(15.36f), px(13.45f), py(20.04f))
        path.close()
        canvas.drawPath(path, paint)
    }
}

/** A small filled circle drawn in front of a line of text, vertically centred on the text. */
private class DotSpan(private val color: Int, private val diameter: Int, private val gap: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = diameter + gap
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val dot = Paint(paint).apply { this.color = this@DotSpan.color; style = Paint.Style.FILL }
        canvas.drawCircle(x + diameter / 2f, y + (paint.ascent() + paint.descent()) / 2f, diameter / 2f, dot)
    }
}
