package dev.kyungmin.kcoach.wear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator

/**
 * One of the rest screen's two buttons (design 4a): a 138 x 70 pill with a 24-unit icon and a 24-unit label.
 * The left one is "정지" and turns into the yellow "계속" as [resume] goes from 0 to 1; the right one is "건너뛰기".
 * A press shrinks it to 93 % and darkens it for 140 ms.
 */
internal class RestPill(context: Context, private val skip: Boolean) : View(context) {
    var onClick: () -> Unit = {}

    /** 0 is the dark "정지" pill, 1 the yellow "계속" pill. Only the left pill reacts. */
    var resume = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            contentDescription = describe()
            invalidate()
        }

    /** Replaces the rest labels: the left pill becomes a yellow action, the right one a plain "skip"-style pill. */
    var caption: String? = null
        set(value) { if (field == value) return; field = value; contentDescription = describe(); invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val ease = PathInterpolator(0f, 0f, .58f, 1f)
    private var pressed = 0f
    private var pressAnimator: ValueAnimator? = null
    private var down = false

    init {
        isClickable = true; isFocusable = true
        contentDescription = describe()
    }

    private fun describe() = caption ?: if (skip) "건너뛰기" else if (resume >= .5f) "계속" else "정지"

    private fun pressTo(target: Float) {
        pressAnimator?.cancel()
        if (Moment.reducedMotion(context) || !isAttachedToWindow) { pressed = target; invalidate(); return }
        pressAnimator = ValueAnimator.ofFloat(pressed, target).apply {
            duration = PRESS_MS; interpolator = ease
            addUpdateListener { pressed = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; pressTo(1f); return true }
            MotionEvent.ACTION_MOVE -> {
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (down && !inside) { down = false; pressTo(0f) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val fire = down && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                down = false; pressTo(0f)
                if (fire) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { down = false; pressTo(0f); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); onClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    override fun onDetachedFromWindow() { pressAnimator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val k = width / WIDTH_UNITS
        val cx = width / 2f; val cy = height / 2f
        val scale = 1f - .07f * pressed
        // A press multiplies every colour by 0.8 at full strength, like CSS brightness(.8).
        val shade = (255 * (1f - .2f * pressed)).toInt()
        val filter = if (pressed > 0f) LightingColorFilter(Color.rgb(shade, shade, shade), 0) else null
        fill.colorFilter = filter; label.colorFilter = filter; icon.colorFilter = filter
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val action = caption != null && !skip
        fill.color = if (action) WatchWorkoutFace.YELLOW else if (skip) WatchWorkoutFace.CARD else blend(WatchWorkoutFace.CARD, WatchWorkoutFace.YELLOW, resume)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 20 * k, 20 * k, fill)
        if (caption != null) {
            if (action) layer(canvas, k, cy, caption!!, Icon.PLAY, Color.rgb(36, 26, 0), Typeface.DEFAULT_BOLD, 1f, 1f)
            else layer(canvas, k, cy, caption!!, Icon.SKIP, WatchWorkoutFace.WHITE, medium, 1f, 1f)
        } else if (skip) {
            layer(canvas, k, cy, "건너뛰기", Icon.SKIP, WatchWorkoutFace.WHITE, medium, 1f, 1f)
        } else {
            // The two faces of the button cross-fade and scale between 80 and 100 %.
            val fade = (resume / FADE_SPAN).coerceAtMost(1f)
            if (fade < 1f) layer(canvas, k, cy, "정지", Icon.PAUSE, WatchWorkoutFace.WHITE, medium, 1f - fade, 1f - .2f * fade)
            if (fade > 0f) layer(canvas, k, cy, "계속", Icon.PLAY, Color.rgb(36, 26, 0), Typeface.DEFAULT_BOLD, fade, .8f + .2f * fade)
        }
        canvas.restore()
    }

    /** The icon and the label as one centred group: 24 + 8 + text width, on a line box 24 units high. */
    private fun layer(canvas: Canvas, k: Float, cy: Float, text: String, glyph: Icon, color: Int, face: Typeface, alpha: Float, scale: Float) {
        label.typeface = face; label.textSize = 24 * k; label.color = color
        val textWidth = label.measureText(text)
        val group = 24 * k + 8 * k + textWidth
        val left = (width - group) / 2f
        val saved = canvas.save()
        canvas.scale(scale, scale, width / 2f, cy)
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        label.alpha = a
        // line-height:1 puts the baseline 0.841 of the size below the top of the box.
        canvas.drawText(text, left + 24 * k + 8 * k, cy - 12 * k + 0.841f * 24 * k, label)
        icon.color = color; icon.alpha = a; icon.strokeWidth = 1.75f // in design units: the canvas is scaled to them below
        canvas.translate(left, cy - 12 * k); canvas.scale(k, k)
        glyph.draw(canvas, icon)
        canvas.restoreToCount(saved)
    }

    private enum class Icon {
        PAUSE, PLAY, SKIP;

        /** The 24-unit glyphs of the design sheet, filled and outlined with a round 1.75 stroke. */
        fun draw(canvas: Canvas, paint: Paint) {
            when (this) {
                PAUSE -> {
                    canvas.drawRoundRect(RectF(6f, 5f, 10f, 19f), 1f, 1f, paint)
                    canvas.drawRoundRect(RectF(14f, 5f, 18f, 19f), 1f, 1f, paint)
                }
                PLAY -> canvas.drawPath(Path().apply { moveTo(7f, 5f); lineTo(19f, 12f); lineTo(7f, 19f); close() }, paint)
                SKIP -> {
                    canvas.drawPath(Path().apply { moveTo(6f, 5f); lineTo(15f, 12f); lineTo(6f, 19f); close() }, paint)
                    canvas.drawLine(18.5f, 5f, 18.5f, 19f, paint)
                }
            }
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt())

    private companion object {
        const val WIDTH_UNITS = 138f
        const val PRESS_MS = 140L
        /** The label cross-fade (220 ms) finishes within this share of the 340 ms morph. */
        const val FADE_SPAN = .65f
    }
}
