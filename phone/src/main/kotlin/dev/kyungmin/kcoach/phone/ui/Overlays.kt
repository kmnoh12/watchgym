package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt

/** One bottom sheet or centred dialog shown above whatever screen is current. */
class Overlay(val isSheet: Boolean, val content: @Composable (dismiss: () -> Unit) -> Unit)

/** A short-lived bar at the bottom that lets the user take back something just done, instead of asking first. */
class UndoNotice(val message: String, val onUndo: () -> Unit)

class OverlayState {
    var current by mutableStateOf<Overlay?>(null)
        private set

    var undo by mutableStateOf<UndoNotice?>(null)
        private set

    fun showUndo(notice: UndoNotice) {
        undo = notice
    }

    fun clearUndo(notice: UndoNotice) {
        if (undo === notice) undo = null
    }

    fun show(overlay: Overlay) {
        current = overlay
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun OverlayHost(state: OverlayState) {
    val current = state.current
    BackHandler(enabled = current != null) { state.dismiss() }
    Box(Modifier.fillMaxSize()) {
        UndoBar(state)
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(Motion.panel()),
            exit = fadeOut(Motion.panel()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.dismiss() },
            )
        }
        AnimatedContent(
            targetState = current,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { overlayTransition(initialState, targetState) },
            label = "overlay",
        ) { overlay ->
            // Keep the empty state full-sized too. A zero-size outgoing state makes
            // AnimatedContent resize/reposition the incoming sheet diagonally.
            Box(Modifier.fillMaxSize(), contentAlignment = if (overlay?.isSheet == true) Alignment.BottomCenter else Alignment.Center) {
                if (overlay != null) {
                    if (overlay.isSheet) SheetSurface(state::dismiss) { overlay.content(state::dismiss) }
                    else DialogSurface { overlay.content(state::dismiss) }
                }
            }
        }
    }
}

private const val UNDO_VISIBLE_MS = 2_000L

@Composable
private fun UndoBar(state: OverlayState) {
    val notice = state.undo
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(UNDO_VISIBLE_MS)
            state.clearUndo(notice)
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = notice != null, enter = fadeIn(Motion.ui()), exit = fadeOut(Motion.ui())) {
            // Keep the last notice on screen while it fades out.
            val shown = remember { mutableStateOf(notice) }
            if (notice != null) shown.value = notice
            val text = shown.value ?: return@AnimatedVisibility
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(KColors.SurfaceRaised)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text.message, color = KColors.White, fontSize = 15.sp)
                Text(
                    "취소하기", color = KColors.Sage, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { state.clearUndo(text); text.onUndo() }.padding(start = 16.dp),
                )
            }
        }
    }
}

private fun overlayTransition(from: Overlay?, to: Overlay?): ContentTransform = when {
    from == null && to != null ->
        (if (to.isSheet) slideInVertically(Motion.panel()) { it } else fadeIn(Motion.ui()) + scaleIn(Motion.ui(), 0.94f)) togetherWith ExitTransition.None
    from != null && to == null ->
        EnterTransition.None togetherWith
            (if (from.isSheet) slideOutVertically(Motion.panel()) { it } else fadeOut(Motion.ui()) + scaleOut(Motion.ui(), 0.94f))
    else -> fadeIn(Motion.ui()) togetherWith fadeOut(Motion.ui())
}

@Composable
private fun SheetSurface(dismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissDistance = with(density) { 100.dp.toPx() }
    val flingVelocity = with(density) { 900.dp.toPx() }

    fun release(velocity: Float) {
        scope.launch {
            if (offset.value > dismissDistance || velocity > flingVelocity) dismiss()
            else offset.animateTo(0f, Motion.panel())
        }
    }

    // Scrollable content (long lists) hands leftover downward drags to the sheet once it is at the top.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && offset.value > 0f) {
                    val used = maxOf(available.y, -offset.value)
                    scope.launch { offset.snapTo(offset.value + used) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    scope.launch { offset.snapTo(offset.value + available.y) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (offset.value > 0f) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // The whole sheet is the drag handle, not just the grabber at the top.
    Column(
        Modifier
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .fillMaxWidth()
            .clip(KShape.Sheet)
            .background(KColors.Surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .nestedScroll(nested)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                onDragStopped = { velocity -> release(velocity) },
            )
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(44.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(KColors.Faint))
        }
        Column(Modifier.heightIn(max = 560.dp)) { content() }
    }
}

@Composable
private fun DialogSurface(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 28.dp)
            .fillMaxWidth()
            .clip(KShape.Panel)
            .background(KColors.SurfaceRaised)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
    ) { content() }
}

// ---- Sheet building blocks -------------------------------------------------

@Composable
fun SheetTitle(text: String) {
    Text(
        text, color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
    )
}

@Composable
fun SheetOptionRow(icon: KIcon, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(46.dp), contentAlignment = Alignment.CenterStart) { KIcon(icon, KColors.Soft, size = 22.dp) }
        Text(title, color = KColors.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
    }
}

/**
 * One action in a menu sheet. [featured] actions become big square buttons on top (at most three),
 * [destructive] ones go last in red; [value] is a short current value shown under a featured title.
 */
class SheetItem(
    val icon: KIcon,
    val title: String,
    val featured: Boolean = false,
    val destructive: Boolean = false,
    val value: String? = null,
    val onClick: () -> Unit,
)

class SegOption(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** [note] explains the chosen option and changes with it. */
class SegGroup(val label: String, val options: List<SegOption>, val note: String? = null)

class SheetAction(val label: String, val destructive: Boolean, val onClick: () -> Unit)

/**
 * Sheet for one set: big title, each setting as one connected bar (tap a segment to choose, no second sheet),
 * then plain text actions underneath.
 */
@Composable
fun SetSheet(title: String, subtitle: String, groups: List<SegGroup>, actions: List<SheetAction>) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp)) {
            Text(title, color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = KColors.Muted, fontSize = 13.sp)
        }
        groups.forEach { group ->
            Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text(group.label, color = KColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                SegmentedBar(group.options, Modifier.testTag("seg-${group.label}"))
                if (group.note != null) {
                    Spacer(Modifier.height(6.dp))
                    Crossfade(group.note, animationSpec = Motion.ui(), label = "segNote") { note ->
                        Text(note, color = KColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            actions.forEach { action ->
                Box(Modifier.weight(1f).height(52.dp).pressable(action.onClick), contentAlignment = Alignment.Center) {
                    Text(
                        action.label,
                        color = if (action.destructive) KColors.Red else KColors.White,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * One thumb under all segments. Press and slide: the thumb follows the finger and settles on the nearest segment
 * when released; a plain tap moves it straight to that segment. The choice fires once, on release.
 */
@Composable
private fun SegmentedBar(options: List<SegOption>, modifier: Modifier = Modifier) {
    val count = options.size
    val selected = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    var preview by remember { mutableStateOf<Int?>(null) }
    val highlighted = preview ?: selected
    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp).clip(KShape.Control).background(KColors.SurfaceRaised)) {
        val width = constraints.maxWidth.toFloat()
        val segment = width / count
        val thumbX by animateFloatAsState(highlighted * segment, Motion.ui(), label = "segmentThumb")
        val lift by animateFloatAsState(if (preview != null) 1.04f else 1f, Motion.ui(), label = "thumbLift")

        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToInt(), 0) }
                .width(with(LocalDensity.current) { segment.toDp() })
                .fillMaxHeight()
                .padding(2.dp)
                .graphicsLayer { scaleX = lift; scaleY = lift }
                .clip(KShape.Control)
                .background(KColors.AccentWash)
                .border(1.dp, KColors.Accent, KShape.Control),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                val text by animateColorAsState(if (index == highlighted) KColors.Accent else KColors.Soft, Motion.ui(), label = "segText")
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(option.label, color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        // Gesture layer on top; the labels above are only drawn.
        Box(
            Modifier
                .fillMaxSize()
                .slidingSelection(
                    selected = selected,
                    indexAt = { x -> (x / segment).toInt().coerceIn(0, count - 1) },
                    preview = { preview = it },
                    onSelect = { options[it].onClick() },
                ),
        )
    }
}

@Composable
fun SheetOptions(title: String, items: List<SheetItem>, subtitle: String? = null) {
    val featured = items.filter { it.featured && !it.destructive }.take(3)
    val plain = items.filter { it !in featured && !it.destructive }
    val danger = items.filter { it.destructive }
    Column(Modifier.padding(bottom = 16.dp)) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp)) {
            Text(title, color = KColors.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = KColors.Muted, fontSize = 13.sp)
        }
        if (featured.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 22.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                featured.forEach { item ->
                    Column(
                        Modifier
                            .weight(1f)
                            .height(92.dp)
                            .clip(KShape.Control)
                            .background(KColors.SurfaceRaised)
                            .pressable(item.onClick)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        KIcon(item.icon, KColors.Accent, size = 24.dp)
                        Column {
                            Text(item.title, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            // Always reserve the value line so titles sit at the same height across tiles.
                            Text(item.value ?: " ", color = KColors.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        (plain + danger).forEach { item ->
            Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
            Box(Modifier.fillMaxWidth().height(52.dp).pressable(item.onClick).padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                Text(item.title, color = if (item.destructive) KColors.Red else KColors.Soft, fontSize = 15.sp)
            }
        }
    }
}

/** Pick one value. Every option is a block; the current one carries the same mint wash and outline as the set sheet's bar. */
@Composable
fun ChoiceSheet(title: String, items: List<Pair<String, String>>, selected: String? = null, onSelect: (String) -> Unit) {
    val valueWidth = if (items.maxOfOrNull { it.first.length } ?: 0 > 3) 92.dp else 52.dp
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(title, color = KColors.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp))
        items.forEach { (value, description) ->
            val chosen = value == selected
            val wash by animateColorAsState(if (chosen) KColors.AccentWash else KColors.SurfaceRaised, Motion.ui(), label = "pickWash")
            val edge by animateColorAsState(if (chosen) KColors.Accent else Color.Transparent, Motion.ui(), label = "pickEdge")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 3.dp)
                    .clip(KShape.Control)
                    .background(wash)
                    .border(1.dp, edge, KShape.Control)
                    .pressable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, color = if (chosen) KColors.Accent else KColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(valueWidth))
                if (description.isNotBlank()) Text(description, color = if (chosen) KColors.Soft else KColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---- Dialog building blocks ------------------------------------------------

@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) { content() }
}

@Composable
private fun TextAction(text: String, color: Color = KColors.Accent, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).pressable(onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    negative: String,
    positive: String,
    dismiss: () -> Unit,
    onPositive: () -> Unit,
) {
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
    Text(message, color = KColors.Muted, fontSize = 15.sp)
    DialogButtons {
        TextAction(negative, KColors.Muted, dismiss)
        TextAction(positive) {
            dismiss()
            onPositive()
        }
    }
}

/** Ending a running workout: the choice between keeping what was finished and dropping it is the point. */
@Composable
fun StopWorkoutDialog(
    title: String,
    message: String,
    dismiss: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
    Text(message, color = KColors.Muted, fontSize = 15.sp)
    Spacer(Modifier.height(20.dp))
    Box(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(KShape.Control).background(KColors.Accent)
            .pressable { dismiss(); onSave() },
        contentAlignment = Alignment.Center,
    ) { Text("저장하고 종료", color = KColors.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(KShape.Control).background(KColors.DangerFill)
            .pressable { dismiss(); onDiscard() },
        contentAlignment = Alignment.Center,
    ) { Text("저장 안 하고 종료", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextAction("계속 운동", KColors.Muted, dismiss) }
}

@Composable
fun InputDialog(title: String, hint: String, dismiss: () -> Unit, initial: String = "", onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    Box(Modifier.fillMaxWidth().clip(KShape.Control).background(KColors.Selected).padding(16.dp)) {
        if (text.isEmpty()) Text(hint, color = KColors.Faint, fontSize = 16.sp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = KColors.White, fontSize = 16.sp),
            cursorBrush = SolidColor(KColors.Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    DialogButtons {
        TextAction("취소", KColors.Muted, dismiss)
        TextAction("추가") {
            dismiss()
            onSubmit(text.trim())
        }
    }
}

@Composable
fun PickDialog(title: String, items: List<String>, dismiss: () -> Unit, onPick: (Int) -> Unit) {
    Text(title, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        items.forEachIndexed { index, label ->
            Text(
                label, color = KColors.White, fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .pressable {
                        dismiss()
                        onPick(index)
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
    DialogButtons { TextAction("취소", KColors.Muted, dismiss) }
}

/** Large centred call-to-action used by the start guide and the "my mountain" prompt. */
@Composable
fun BigChoiceSheet(
    eyebrow: String?,
    eyebrowColor: Color,
    title: String,
    body: String,
    bodyColor: Color,
    cancel: String,
    confirm: String,
    dismiss: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    filledButtons: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (eyebrow == "▶") KIcon(KIcon.Play, eyebrowColor, size = 38.dp)
        else if (eyebrow != null) Text(eyebrow, color = eyebrowColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(title, color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(body, color = bodyColor, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cancelModifier = Modifier.weight(1f).height(if (filledButtons) 60.dp else 58.dp)
            if (filledButtons) {
                BigButton(cancel, KColors.SurfaceRaised, KColors.White, cancelModifier) { dismiss(); onCancel() }
                BigButton(confirm, KColors.Accent, KColors.OnAccent, Modifier.weight(1f).height(60.dp)) { dismiss(); onConfirm() }
            } else {
                TextButton(cancel, cancelModifier) { dismiss(); onCancel() }
                TextButton(confirm, Modifier.weight(1f).height(58.dp)) { dismiss(); onConfirm() }
            }
        }
    }
}

@Composable
private fun BigButton(text: String, background: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(KShape.Control).background(background).pressable(onClick), contentAlignment = Alignment.Center) {
        Text(text, color = content, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TextButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.pressable(onClick), contentAlignment = Alignment.Center) {
        Text(text, color = KColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
