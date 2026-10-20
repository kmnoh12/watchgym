package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.text.style.TextOverflow
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.abs

/** One exercise row. [key] stays stable while the row is dragged around. */
class RoutineRowUi(
    val key: Int,
    val name: String,
    val subtitle: String,
    val thumbnail: () -> View,
    /** Completed / planned sets, only while this routine is being performed. */
    val doneSets: Int? = null,
    val plannedSets: Int = 0,
)

class RoutineDetailActions(
    val back: () -> Unit,
    val open: (index: Int) -> Unit,
    val info: (index: Int) -> Unit,
    val menu: (index: Int) -> Unit,
    /** Called for every single-step swap while dragging. */
    val move: (from: Int, to: Int) -> Unit,
    val moveFinished: () -> Unit,
    val primary: () -> Unit,
    val addExercise: () -> Unit,
)

private val RowHeight = 66.dp
private val RowStride = 74.dp

@Composable
fun RoutineDetailScreen(
    title: String,
    rows: List<RoutineRowUi>,
    primaryLabel: String?,
    primaryIcon: KIcon,
    showHandle: Boolean,
    actions: RoutineDetailActions,
    preparationContent: (@Composable () -> Unit)? = null,
    addFilled: Boolean = false,
    /** Pencil next to the title; null hides it. */
    onRename: (() -> Unit)? = null,
    /** Running workout clock shown at the right of the title row; null when no workout runs. */
    elapsedSeconds: (() -> Long)? = null,
    paused: Boolean = false,
) {
    val list = remember { mutableStateListOf<RoutineRowUi>().apply { addAll(rows) } }
    var draggedKey by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var moved by remember { mutableStateOf(false) }
    val stridePx = with(LocalDensity.current) { RowStride.toPx() }
    val haptic = LocalHapticFeedback.current

    Box(Modifier.fillMaxSize().background(KColors.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 110.dp),
        ) {
            item(key = "top") {
                Column {
                    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp, 58.dp).pressable(actions.back), contentAlignment = Alignment.Center) {
                            KIcon(KIcon.Back, KColors.White, size = 28.dp)
                        }
                        Text(title, color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (onRename != null) {
                            Box(Modifier.size(44.dp, 58.dp).pressable(onRename), contentAlignment = Alignment.Center) {
                                KIcon(KIcon.Edit, KColors.Muted, size = 20.dp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (elapsedSeconds != null) {
                            val elapsed by produceState(elapsedSeconds(), elapsedSeconds, paused) {
                                while (true) {
                                    value = elapsedSeconds()
                                    if (paused) break
                                    delay(1_000)
                                }
                            }
                            Text(
                                "%02d:%02d".format(elapsed / 60, elapsed % 60),
                                color = if (paused) KColors.Muted else KColors.Accent,
                                fontSize = 20.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    preparationContent?.invoke()
                    Spacer(Modifier.height(10.dp))
                }
            }
            itemsIndexed(list, key = { _, row -> row.key }) { index, row ->
                val dragging = draggedKey == row.key
                val lift by animateFloatAsState(if (dragging) 1.03f else 1f, Motion.ui(), label = "lift")
                Box(
                    Modifier
                        .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem(placementSpec = Motion.panel()))
                        .fillMaxWidth()
                        .height(RowStride)
                        .graphicsLayer { if (dragging) translationY = dragOffset }
                        .scale(lift)
                        .alpha(if (dragging) 0.85f else 1f)
                        .drawBehind { if (!dragging) drawLine(KColors.Hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f) },
                ) {
                    ExerciseRow(
                        row = row,
                        showHandle = showHandle,
                        onOpen = { actions.open(list.indexOfFirst { it.key == row.key }) },
                        onInfo = { actions.info(list.indexOfFirst { it.key == row.key }) },
                        onMenu = { actions.menu(list.indexOfFirst { it.key == row.key }) },
                        modifier = Modifier.pointerInput(row.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggedKey = row.key
                                    dragOffset = 0f
                                    moved = false
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val from = list.indexOfFirst { it.key == row.key }
                                    val step = when {
                                        dragOffset > stridePx / 2 -> 1
                                        dragOffset < -stridePx / 2 -> -1
                                        else -> 0
                                    }
                                    val to = from + step
                                    if (step != 0 && to in list.indices) {
                                        list.add(to, list.removeAt(from))
                                        actions.move(from, to)
                                        dragOffset -= step * stridePx
                                        moved = true
                                    } else if (step != 0 && abs(dragOffset) > stridePx) {
                                        dragOffset = if (step > 0) stridePx else -stridePx
                                    }
                                },
                                onDragEnd = {
                                    draggedKey = null
                                    dragOffset = 0f
                                    if (moved) actions.moveFinished()
                                    moved = false
                                },
                                onDragCancel = {
                                    draggedKey = null
                                    dragOffset = 0f
                                    if (moved) actions.moveFinished()
                                    moved = false
                                },
                            )
                        },
                    )
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(KColors.Bg)
                .drawBehind { drawLine(KColors.Hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp),
        ) {
            if (primaryLabel != null) {
                RoutineButton(primaryLabel, primaryIcon, KColors.Accent, KColors.OnAccent, Modifier.weight(1f), actions.primary)
                Spacer(Modifier.width(12.dp))
            }
            RoutineButton(
                "운동 추가",
                KIcon.Plus,
                if (addFilled) KColors.Accent else Color.Transparent,
                if (addFilled) KColors.OnAccent else KColors.White,
                Modifier.weight(1f),
                actions.addExercise,
            )
        }
    }
}

@Composable
private fun ExerciseRow(
    row: RoutineRowUi,
    showHandle: Boolean,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(RowHeight)
            .pressable(onOpen)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val entered = rememberEntered()
        val fade by animateFloatAsState(if (entered) 1f else 0f, Motion.ui(), label = "thumb")
        AndroidView(factory = { row.thumbnail() }, modifier = Modifier.size(58.dp).alpha(fade))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(row.subtitle, color = KColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.doneSets != null) {
            val finished = row.plannedSets > 0 && row.doneSets >= row.plannedSets
            Text(
                "${row.doneSets}/${row.plannedSets}",
                color = if (finished) KColors.Accent else if (row.doneSets > 0) KColors.White else KColors.Faint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Box(Modifier.size(38.dp, 48.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
            KIcon(KIcon.Info, KColors.Muted, size = 20.dp)
        }
        Box(Modifier.size(40.dp, 48.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            KIcon(KIcon.Dots, if (showHandle) KColors.Accent else KColors.Muted, size = 22.dp)
        }
    }
}

@Composable
private fun RoutineButton(
    text: String,
    icon: KIcon,
    background: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(66.dp)
            .clip(KShape.Control)
            .background(background)
            .then(if (background == Color.Transparent) Modifier.border(0.5.dp, Color(0xFF3F3D39), KShape.Control) else Modifier)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KIcon(icon, content, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(text, color = content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}
