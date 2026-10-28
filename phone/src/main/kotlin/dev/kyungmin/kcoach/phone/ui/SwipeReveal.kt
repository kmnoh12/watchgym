package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Swipe left to reveal a red "삭제" button; the row never deletes on the first swipe.
 * Tap the button, or swipe left once more, to delete. Swipe back or tap the row to close.
 */
@Composable
fun SwipeRevealDelete(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { 79.dp.toPx() }
    val deletePullPx = with(density) { 64.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val open = offset.value < -revealPx / 2

    fun settle(target: Float) = scope.launch { offset.animateTo(target, Motion.ui()) }

    Box(modifier.clipToBounds()) {
        // Red button sits behind the row and widens with the swipe.
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { (-offset.value).coerceAtLeast(0f).toDp() })
                    .padding(vertical = 3.dp)
                    .clip(KShape.Control)
                    .background(KColors.Red)
                    .clickable(enabled = open, onClick = onDelete),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "삭제", color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(79.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var startedOpen = false
                    val firstStop = -(revealPx + 20.dp.toPx())
                    val secondFloor = -(revealPx + deletePullPx + 24.dp.toPx())
                    detectHorizontalDragGestures(
                        onDragStart = { startedOpen = offset.value <= -revealPx / 2 },
                        onDragEnd = {
                            val value = offset.value
                            when {
                                startedOpen && value <= -(revealPx + deletePullPx) -> onDelete()
                                value < -revealPx / 2 -> settle(-revealPx)
                                else -> settle(0f)
                            }
                        },
                        onDragCancel = { settle(if (offset.value < -revealPx / 2) -revealPx else 0f) },
                    ) { change, dragAmount ->
                        // Swiping right on a closed row belongs to the page pager.
                        if (dragAmount > 0f && offset.value >= 0f) return@detectHorizontalDragGestures
                        change.consume()
                        // A swipe that starts closed never goes past the button; only a second swipe can delete.
                        val floor = if (startedOpen) secondFloor else firstStop
                        val next = (offset.value + dragAmount).coerceIn(floor, 0f)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
                    }
                },
        ) {
            content()
            // While open, the row only closes; its own buttons stay inert.
            if (open) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(0f) },
                )
            }
        }
    }
}
