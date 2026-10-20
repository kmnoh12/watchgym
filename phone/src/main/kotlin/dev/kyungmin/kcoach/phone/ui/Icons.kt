package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Our own line icon set: 24 grid, 1.75 stroke, round ends, no filled shapes except dots. */
enum class KIcon {
    Home, Records, Stats, Calibrate, Menu, Back, Forward, Down, Plus, Minus, Check, Close,
    Play, Pause, Stop, Info, Dots, DotsVertical, Clock, Search, Sliders, Heart, Flag, Swap,
    Reload, Trash, Link, Skip, Watch, Settings, Edit,
}

@Composable
fun KIcon(icon: KIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, filled: Boolean = false) {
    Canvas(modifier.size(size)) { drawKIcon(icon, tint, filled) }
}

private fun DrawScope.drawKIcon(icon: KIcon, tint: Color, filled: Boolean) {
    val s = size.width / 24f
    val stroke = Stroke(width = 1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
    fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false) {
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
        if (close) path.close()
        drawPath(path, tint, style = stroke)
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(tint, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = stroke)
    fun circle(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * s, p(cx, cy), style = stroke)
    fun dot(cx: Float, cy: Float, r: Float = 1.5f) = drawCircle(tint, r * s, p(cx, cy))

    when (icon) {
        KIcon.Settings -> {
            val gear = Path()
            for (i in 0 until 32) {
                val angle = i * kotlin.math.PI / 16.0
                val radius = if (i % 4 == 1 || i % 4 == 2) 9f else 7f
                val x = (12 + cos(angle) * radius).toFloat() * s
                val y = (12 + sin(angle) * radius).toFloat() * s
                if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
            }
            gear.close()
            drawPath(gear, tint, style = stroke)
            circle(12f, 12f, 3f)
        }
        KIcon.Home -> {
            poly(4f to 11f, 12f to 4f, 20f to 11f)
            poly(6.5f to 9.5f, 6.5f to 20f, 17.5f to 20f, 17.5f to 9.5f)
        }
        KIcon.Records -> {
            rect(4f, 5.5f, 16f, 14.5f, 2f)
            line(4f, 10f, 20f, 10f)
            line(8f, 3.5f, 8f, 7f)
            line(16f, 3.5f, 16f, 7f)
        }
        KIcon.Stats -> {
            line(6f, 20f, 6f, 13f)
            line(12f, 20f, 12f, 6f)
            line(18f, 20f, 18f, 10f)
        }
        KIcon.Calibrate -> {
            circle(12f, 12f, 8f)
            circle(12f, 12f, 3.5f)
        }
        KIcon.Menu -> {
            line(5f, 7f, 19f, 7f)
            line(5f, 12f, 19f, 12f)
            line(5f, 17f, 19f, 17f)
        }
        KIcon.Back -> poly(15f to 5f, 8f to 12f, 15f to 19f)
        KIcon.Forward -> poly(9f to 5f, 16f to 12f, 9f to 19f)
        KIcon.Down -> poly(6f to 9f, 12f to 15f, 18f to 9f)
        KIcon.Plus -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        KIcon.Minus -> line(5f, 12f, 19f, 12f)
        KIcon.Check -> poly(5f to 12.5f, 10f to 17.5f, 19f to 7.5f)
        KIcon.Close -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        KIcon.Play -> poly(8f to 5.5f, 18f to 12f, 8f to 18.5f, close = true)
        KIcon.Pause -> {
            line(9f, 6f, 9f, 18f)
            line(15f, 6f, 15f, 18f)
        }
        KIcon.Stop -> rect(6.5f, 6.5f, 11f, 11f, 1.5f)
        KIcon.Info -> {
            circle(12f, 12f, 8.5f)
            line(12f, 11f, 12f, 16.5f)
            dot(12f, 7.9f, 1f)
        }
        KIcon.Dots -> {
            dot(6f, 12f)
            dot(12f, 12f)
            dot(18f, 12f)
        }
        KIcon.DotsVertical -> {
            dot(12f, 6f)
            dot(12f, 12f)
            dot(12f, 18f)
        }
        KIcon.Clock -> {
            circle(12f, 12f, 8.5f)
            poly(12f to 7.5f, 12f to 12f, 15f to 14f)
        }
        KIcon.Search -> {
            circle(10.5f, 10.5f, 6f)
            line(15f, 15f, 19.5f, 19.5f)
        }
        KIcon.Sliders -> {
            line(4f, 8f, 6.5f, 8f)
            line(11.5f, 8f, 20f, 8f)
            circle(9f, 8f, 2.5f)
            line(4f, 16f, 12.5f, 16f)
            line(17.5f, 16f, 20f, 16f)
            circle(15f, 16f, 2.5f)
        }
        KIcon.Heart -> {
            val path = Path().apply {
                moveTo(12f * s, 20f * s)
                cubicTo(5f * s, 15f * s, 3.5f * s, 11.5f * s, 3.5f * s, 8.8f * s)
                cubicTo(3.5f * s, 6.2f * s, 5.5f * s, 4.5f * s, 7.8f * s, 4.5f * s)
                cubicTo(9.6f * s, 4.5f * s, 11.2f * s, 5.5f * s, 12f * s, 7f * s)
                cubicTo(12.8f * s, 5.5f * s, 14.4f * s, 4.5f * s, 16.2f * s, 4.5f * s)
                cubicTo(18.5f * s, 4.5f * s, 20.5f * s, 6.2f * s, 20.5f * s, 8.8f * s)
                cubicTo(20.5f * s, 11.5f * s, 19f * s, 15f * s, 12f * s, 20f * s)
                close()
            }
            if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
        }
        KIcon.Flag -> {
            line(6f, 20f, 6f, 4f)
            poly(6f to 5f, 18f to 5f, 15f to 9f, 18f to 13f, 6f to 13f)
        }
        KIcon.Swap -> {
            line(5f, 8f, 19f, 8f)
            poly(15f to 4.5f, 19f to 8f, 15f to 11.5f)
            line(19f, 16f, 5f, 16f)
            poly(9f to 12.5f, 5f to 16f, 9f to 19.5f)
        }
        KIcon.Reload -> {
            drawArc(
                color = tint, startAngle = -50f, sweepAngle = 290f, useCenter = false,
                topLeft = p(5f, 5f), size = Size(14f * s, 14f * s), style = stroke,
            )
            val end = p(12f + 7f * cos(Math.toRadians(-50.0)).toFloat(), 12f + 7f * sin(Math.toRadians(-50.0)).toFloat())
            drawLine(tint, end, Offset(end.x + 1f * s, end.y - 4.2f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(tint, end, Offset(end.x - 3.8f * s, end.y - 0.8f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        KIcon.Edit -> {
            poly(4.5f to 19.5f, 5.5f to 15f, 16f to 4.5f, 19.5f to 8f, 9f to 18.5f, close = true)
            line(13.5f, 7f, 17f, 10.5f)
        }
        KIcon.Trash -> {
            line(5f, 7f, 19f, 7f)
            line(10f, 4f, 14f, 4f)
            poly(6.5f to 7f, 7.5f to 20f, 16.5f to 20f, 17.5f to 7f)
            line(10f, 10.5f, 10f, 16.5f)
            line(14f, 10.5f, 14f, 16.5f)
        }
        KIcon.Skip -> {
            poly(6f to 5.5f, 15f to 12f, 6f to 18.5f, close = true)
            line(18.5f, 6f, 18.5f, 18f)
        }
        KIcon.Watch -> {
            rect(7f, 6.5f, 10f, 11f, 3f)
            line(9.5f, 3.5f, 14.5f, 3.5f)
            line(9.5f, 20.5f, 14.5f, 20.5f)
            poly(12f to 9.5f, 12f to 12f, 13.8f to 13.2f)
        }
        KIcon.Link -> {
            rect(3.5f, 9.5f, 10f, 5f, 2.5f)
            rect(10.5f, 9.5f, 10f, 5f, 2.5f)
        }
    }
}
