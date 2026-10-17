package dev.kyungmin.kcoach.wear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** A page laid out on the 432-unit round design grid, so every position matches the design sheet. */
internal open class Grid432(context: Context) : FrameLayout(context) {
    protected val positions = LinkedHashMap<View, android.graphics.Rect>()
    private val designSp = HashMap<TextView, Float>()
    /** Text placed by its baseline on the design grid, the way the design sheet's line-height:1 boxes put it. */
    private val baselines = HashMap<TextView, Float>()
    /** Text that shrinks to fit its box width instead of being cut off. */
    private val fitting = HashSet<TextView>()
    protected val density get() = resources.displayMetrics.density

    fun <T : View> place(view: T, l: Int, t: Int, r: Int, b: Int): T {
        val rect = android.graphics.Rect(l, t, r, b)
        if (positions[view] != rect) { positions[view] = rect; requestLayout() }
        if (view.parent == null) addView(view, LayoutParams(1, 1))
        return view
    }

    /**
     * Puts a line of text between [l] and [r] with its baseline at [baseline] design units. The box itself is
     * always tall enough for the whole line, so the bottom of Korean glyphs is never cut.
     */
    fun <T : TextView> placeText(view: T, l: Int, r: Int, baseline: Float, fit: Boolean = false): T {
        if (baselines[view] != baseline) { baselines[view] = baseline; requestLayout() }
        if (fit) fitting += view else fitting -= view
        return place(view, l, baseline.toInt() - 40, r, baseline.toInt() + 20)
    }

    /** Changes a text's size, in design units of the 432 grid (the design sheet's `calc(var(--u)*N)`). */
    fun sizeUnits(view: TextView, units: Float) {
        if (designSp[view] != units / 2f) { designSp[view] = units / 2f; requestLayout() }
    }

    fun text(sp: Float, color: Int, bold: Boolean = false, medium: Boolean = false, gravity: Int = Gravity.CENTER) = TextView(context).apply {
        textSize = sp; designSp[this] = sp; setTextColor(color); this.gravity = gravity; includeFontPadding = false
        typeface = when { medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL); bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scale = minOf(measuredWidth, measuredHeight) / 432f
        designSp.forEach { (view, sp) ->
            var px = sp * 2f * scale
            val box = positions[view]?.width()?.times(scale)
            if (view in fitting && box != null && box > 0f && px > 0f) {
                view.paint.textSize = px
                val wanted = android.text.Layout.getDesiredWidth(view.text, view.paint)
                if (wanted > box) px *= box / wanted * 0.98f
            }
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
        }
        positions.forEach { (view, rect) ->
            var height = (rect.height() * scale).toInt()
            // A line of text needs about 1.4 times its size; a tighter box would cut the bottom of Korean glyphs.
            if (view is TextView) height = maxOf(height, (view.textSize * 1.4f).toInt())
            if (view is TextView && view in baselines) height = (view.textSize * 1.6f).toInt()
            view.measure(MeasureSpec.makeMeasureSpec((rect.width() * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val side = minOf(width, height); val scale = side / 432f
        val offsetX = (width - side) / 2; val offsetY = (height - side) / 2
        positions.forEach { (view, rect) ->
            val l = offsetX + (rect.left * scale).toInt(); val r = offsetX + (rect.right * scale).toInt()
            val baseline = baselines[view]
            if (view is TextView && baseline != null) {
                val t = (offsetY + baseline * scale - view.baseline).roundToInt()
                view.layout(l, t, r, t + view.measuredHeight)
            } else if (view is TextView) {
                val centreY = offsetY + rect.centerY() * scale
                val half = view.measuredHeight / 2f
                view.layout(l, (centreY - half).toInt(), r, (centreY + half).toInt())
            } else view.layout(l, offsetY + (rect.top * scale).toInt(), r, offsetY + (rect.bottom * scale).toInt())
        }
    }
}

/** The thin up or down arrow that tells which neighbouring page exists. */
internal class ChevronView(context: Context, private val up: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WatchWorkoutFace.SOFT; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    var tint: Int
        get() = paint.color
        set(value) { if (paint.color != value) { paint.color = value; invalidate() } }
    override fun onDraw(canvas: Canvas) {
        // The design draws a 24 x 12 glyph scaled to the view; strokes are 2.4 units of that glyph.
        val k = width / 24f
        paint.strokeWidth = 2.4f * k
        val path = Path()
        if (up) { path.moveTo(4 * k, 9.5f * k); path.lineTo(12 * k, 3 * k); path.lineTo(20 * k, 9.5f * k) }
        else { path.moveTo(4 * k, 2.5f * k); path.lineTo(12 * k, 9 * k); path.lineTo(20 * k, 2.5f * k) }
        canvas.drawPath(path, paint)
    }
}

/**
 * The rest-time control: a rounded bar with a "10초" knob. Pushing the knob right adds ten seconds each time,
 * left removes ten and the knob springs back; tapping the left or right end does the same.
 */
internal class JogBar(context: Context) : View(context) {
    var onStep: (Int) -> Unit = {}
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WatchWorkoutFace.CARD }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WatchWorkoutFace.MUTED; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var offset = 0f
    private var startX = 0f
    private var moved = false
    private var armed = true
    private var back: ValueAnimator? = null

    private fun u() = width / 336f // the bar is 336 design units wide (design 4a)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // A paused rest does not take ten-second steps.
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { back?.cancel(); startX = e.x; moved = false; armed = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - startX
                if (abs(dx) > slop) moved = true
                if (!moved) return true
                offset = dx.coerceIn(-70f * u(), 70f * u())
                if (armed && abs(offset) >= 48f * u()) {
                    armed = false
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStep(if (offset > 0) 10 else -10)
                } else if (!armed && abs(offset) <= 22f * u()) armed = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    val third = width / 3f
                    val delta = when { e.x < third -> -10; e.x > width - third -> 10; else -> 0 }
                    if (delta != 0) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onStep(delta); nudge(delta) }
                    performClick()
                }
                springBack()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun nudge(delta: Int) { offset = (if (delta > 0) 26f else -26f) * u(); invalidate() }

    private fun springBack() {
        back?.cancel()
        back = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 160; interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            addUpdateListener { offset = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val k = u()
        val cy = height / 2f
        // Design 4a: an 80-unit bar with 22-unit corners; the sign glyphs are 46 units, set 26 units from the ends.
        canvas.drawRoundRect(RectF(0f, cy - 40 * k, width.toFloat(), cy + 40 * k), 22 * k, 22 * k, track)
        glyph.textSize = 46 * k
        // A line-height:1 box of size S centred on the bar puts the baseline 0.841 S below its top: 15.7 units under the centre.
        val gy = cy + 15.7f * k
        glyph.textAlign = Paint.Align.LEFT; canvas.drawText("−", 26 * k, gy, glyph)
        glyph.textAlign = Paint.Align.RIGHT; canvas.drawText("+", width - 26 * k, gy, glyph)
        val active = abs(offset) > 1f
        knob.color = if (active) WatchWorkoutFace.YELLOW else WatchWorkoutFace.CONTROL
        label.color = if (active) Color.rgb(36, 26, 0) else WatchWorkoutFace.WHITE
        label.textSize = 36 * k
        val cx = width / 2f + offset
        canvas.drawRoundRect(RectF(cx - 70 * k, cy - 34 * k, cx + 70 * k, cy + 34 * k), 17 * k, 17 * k, knob)
        canvas.drawText("10초", cx, cy + 12.3f * k, label)
    }
}

/** Five zone ticks; only the current one is lit. */
internal class ZoneBar(context: Context) : View(context) {
    var zone = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val gap = width * 5f / 264f
        val w = (width - gap * 4) / 5f
        for (i in 0 until 5) {
            val color = WatchWorkoutFace.zoneColor(ZONE_SAMPLE_BPM[i])
            paint.color = color; paint.alpha = if (i + 1 == zone) 255 else 72
            val l = i * (w + gap)
            canvas.drawRoundRect(RectF(l, 0f, l + w, height.toFloat()), height * .19f, height * .19f, paint)
        }
    }
    private companion object { val ZONE_SAMPLE_BPM = intArrayOf(100, 120, 140, 160, 180) }
}

/** The set editor: the current set while measuring, or the set just finished / the next one while resting. */
internal class EditPageView(context: Context) : Grid432(context) {
    enum class Mode { MEASURE, REST }
    enum class Field { REPS, WEIGHT }
    var onDelta: (Field, Int) -> Unit = { _, _ -> }
    var onKeypad: (Field) -> Unit = {}
    var onFinish: () -> Unit = {}
    var onSegment: (Int) -> Unit = {}

    private var mode = Mode.MEASURE
    private val handler = Handler(Looper.getMainLooper())
    val clock = text(13f, WatchWorkoutFace.WHITE, medium = true)
    val name = text(15f, WatchWorkoutFace.WHITE, bold = true)
    val sub = text(11f, WatchWorkoutFace.MUTED)
    val segPrevious = text(10f, WatchWorkoutFace.WHITE, bold = true)
    val segNext = text(10f, WatchWorkoutFace.MUTED, medium = true)
    private val segTrack = View(context).apply { background = round(WatchWorkoutFace.CARD, 12) }
    val finish = text(12f, Color.rgb(23, 22, 18), bold = true).apply { background = round(WatchWorkoutFace.BONE, 14) }
    val chevronDown = ChevronView(context, false)
    private val repsRow = Row(Field.REPS)
    private val weightRow = Row(Field.WEIGHT)
    val repsValue get() = repsRow.value
    val weightValue get() = weightRow.value

    private fun round(color: Int, radiusUnits: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = radiusUnits * density * 0.5f * (resources.displayMetrics.widthPixels.coerceAtLeast(1) / 432f) * 2f / density
    }

    private inner class Row(val field: Field) {
        val value = text(15.5f, WatchWorkoutFace.WHITE, medium = true).apply {
            background = round(WatchWorkoutFace.CARD, 12); isClickable = true; isFocusable = true
            setOnClickListener { onKeypad(field) }
        }
        val buttons = listOf(-5, -1, 1, 5).map { delta ->
            text(11f, WatchWorkoutFace.BONE).apply { typeface = Typeface.create(Typeface.DEFAULT, 600, false) }.also { b ->
                b.text = when (delta) { -5 -> "−5"; -1 -> "−"; 1 -> "+"; else -> "+5" }
                b.background = round(if (abs(delta) == 5) WatchWorkoutFace.LARGE_STEP else WatchWorkoutFace.CONTROL, 12)
                b.isClickable = true; b.isFocusable = true
                b.contentDescription = (if (field == Field.REPS) "횟수 " else "무게 ") + b.text
                b.setOnClickListener { onDelta(field, delta) }
                holdToRepeat(b) { onDelta(field, delta) }
            }
        }
    }

    /** A long press keeps repeating the step, quickly enough to cover a large change. */
    private fun holdToRepeat(view: View, step: () -> Unit) {
        var repeating = false
        val tick = object : Runnable { override fun run() { repeating = true; step(); handler.postDelayed(this, 90) } }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { repeating = false; v.isPressed = true; handler.postDelayed(tick, 450); true }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(tick); v.isPressed = false
                    if (!repeating) v.performClick(); true
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(tick); v.isPressed = false; true }
                else -> true
            }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        segPrevious.setOnClickListener { onSegment(0) }; segNext.setOnClickListener { onSegment(1) }
        finish.setOnClickListener { onFinish() }
        applyMode(Mode.MEASURE)
    }

    fun applyMode(newMode: Mode) {
        mode = newMode
        val measure = newMode == Mode.MEASURE
        clock.visibility = if (measure) GONE else VISIBLE
        name.visibility = if (measure) VISIBLE else GONE
        segTrack.visibility = if (measure) GONE else VISIBLE
        segPrevious.visibility = segTrack.visibility; segNext.visibility = segTrack.visibility
        finish.visibility = if (measure) VISIBLE else GONE
        // Baselines from the design sheet (top + 0.841 x size).
        placeText(clock, 116, 316, 63.9f); placeText(name, 63, 369, 83.2f, fit = true)
        placeText(sub, 52, 380, if (measure) 116.5f else 94.5f)
        sub.typeface = if (measure) Typeface.DEFAULT else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        place(segTrack, 50, 108, 382, 152); place(segPrevious, 54, 112, 214, 148); place(segNext, 218, 112, 378, 148)
        val firstTop = if (measure) 134 else 166; val secondTop = if (measure) 206 else 238
        layoutRow(repsRow, firstTop); layoutRow(weightRow, secondTop)
        place(finish, 76, 298, 356, 362)
        place(chevronDown, 199, 398, 233, 415)
    }

    private fun layoutRow(row: Row, top: Int) {
        val h = 62
        var x = 38
        place(row.buttons[0], x, top, x + 62, top + h); x += 70
        place(row.buttons[1], x, top, x + 62, top + h); x += 70
        place(row.value, x, top, x + 76, top + h); x += 84
        place(row.buttons[2], x, top, x + 62, top + h); x += 70
        place(row.buttons[3], x, top, x + 62, top + h)
    }

    /** Shows a value as a large number with a small unit, e.g. 12 회, 20 kg, 105 kg. */
    private fun show(view: TextView, number: String, unit: String) {
        view.text = SpannableStringBuilder(number).apply {
            // The design keeps 4 units between the number and its unit.
            val gap = length; append(Moment.GAP); setSpan(GapSpan(4f / 31f), gap, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = length
            append(unit)
            setSpan(RelativeSizeSpan(0.52f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(WatchWorkoutFace.MUTED), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun bindMeasure(exercise: String, setNumber: Int, reps: String, weight: String, finishText: String, canFinish: Boolean) {
        name.text = exercise; sub.text = "${setNumber}세트 편집"; sub.setTextColor(WatchWorkoutFace.MUTED)
        show(repsRow.value, reps, "회"); show(weightRow.value, weight, "kg")
        finish.text = finishText; finish.alpha = if (canFinish) 1f else .4f; finish.isEnabled = canFinish
    }

    fun bindRest(left: String, clockText: String, previousLabel: String, nextLabel: String, selected: Int, reps: String, weight: String) {
        clock.text = clockText
        sub.text = left; sub.setTextColor(WatchWorkoutFace.YELLOW)
        segPrevious.text = previousLabel; segNext.text = nextLabel
        val onFirst = selected == 0
        segPrevious.setTextColor(if (onFirst) WatchWorkoutFace.BONE else WatchWorkoutFace.MUTED)
        segNext.setTextColor(if (onFirst) WatchWorkoutFace.MUTED else WatchWorkoutFace.SAGE)
        segPrevious.typeface = Typeface.create(Typeface.DEFAULT, if (onFirst) 700 else 500, false)
        segNext.typeface = Typeface.create(Typeface.DEFAULT, if (onFirst) 500 else 700, false)
        segPrevious.background = if (onFirst) round(WatchWorkoutFace.PREVIOUS_SELECTED, 9) else null
        segNext.background = if (onFirst) null else round(WatchWorkoutFace.SAGE_WASH, 9)
        show(repsRow.value, reps, "회"); show(weightRow.value, weight, "kg")
    }
}

/** The page below the counter while measuring: heart rate with its zone, workout time and finished sets. */
internal class AuxPageView(context: Context) : Grid432(context) {
    val clock = placeText(text(13f, WatchWorkoutFace.WHITE, medium = true), 116, 316, 63.9f)
    /** The rate and its small "bpm", centred together on one baseline as in the design. */
    val heartRate = placeText(text(56f, WatchWorkoutFace.BONE, medium = true).apply { letterSpacing = -3f / 112f }, 40, 392, 166.2f, fit = true)
    val zoneBar = place(ZoneBar(context), 84, 204, 348, 220)
    val zoneLabel = placeText(text(11f, WatchWorkoutFace.SOFT, bold = true).apply { letterSpacing = 1.5f / 22f }, 84, 348, 250.5f)
    /** Workout time and finished sets, two columns 48 units apart. */
    private val pair = StatRow(this, 40f, 18f, 290f, 8f, 23.25f, 0f, 300f)
    val elapsed get() = pair.values[0]
    val sets get() = pair.values[1]
    val chevronUp = place(ChevronView(context, true), 199, 14, 233, 31)
    init { setBackgroundColor(Color.BLACK) }

    fun bind(heart: Int, elapsedText: String, finishedSets: Int, clockText: String) {
        clock.text = clockText
        val zone = WatchWorkoutFace.zone(heart)
        val color = if (zone == 0) WatchWorkoutFace.MUTED else WatchWorkoutFace.zoneColor(heart)
        heartRate.text = if (heart > 0) Moment.valueWithUnit(heart.toString(), "bpm", 22f / 112f, WatchWorkoutFace.MUTED, gapEm = 10f / 112f) else "—"
        heartRate.setTextColor(color)
        zoneBar.zone = zone
        zoneLabel.text = if (zone == 0) "" else "ZONE $zone"; zoneLabel.setTextColor(color)
        pair.bind(listOf(elapsedText to "운동 시간", finishedSets.toString() to "완료 세트"))
    }
}
