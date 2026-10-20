package dev.kyungmin.kcoach.phone.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutRoutine
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Tab order matches MainActivity.RootTab. */
private val NavItems = listOf(KIcon.Home to "홈", KIcon.Records to "기록", KIcon.Stats to "통계", KIcon.Calibrate to "보정", KIcon.Menu to "메뉴")

/** False on the first frame, true right after: lets values animate in when a screen opens. */
@Composable
fun rememberEntered(): Boolean {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    return entered
}

/** Press feedback shared by every tappable surface: a small shrink on the UI curve. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.ui(), label = "press")
    return this.scale(scale).clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * Tab bar that floats above the screen. Only the chosen tab shows its label and widens; press and slide to move
 * the highlight along with the finger, release to switch. The bar stays alive across tab changes, so it can animate.
 */
@Composable
fun FloatingTabBar(selected: Int, visible: Boolean, onSelect: (Int) -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.panel()) + slideInVertically(Motion.panel()) { it / 2 },
        exit = fadeOut(Motion.ui()) + slideOutVertically(Motion.ui()) { it / 2 },
    ) {
        TabBarBody(selected, onSelect)
    }
}

@Composable
private fun TabBarBody(selected: Int, onSelect: (Int) -> Unit) {
    val count = NavItems.size
    val density = LocalDensity.current
    var preview by remember { mutableStateOf<Int?>(null) }
    val target = preview ?: selected
    // Concentric with the 6dp selected thumb inside a 4dp padding: 6 + 4 = 10 (KShape.Panel).
    val shape = KShape.Panel
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(KColors.Surface)
            .border(0.5.dp, KColors.Hairline, shape)
            .padding(4.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
            val innerPx = constraints.maxWidth.toFloat()
            // Selected button is 96 x 48 dp (2:1), independent of the phone's width.
            val thumbWidth = with(density) { 96.dp.toPx() }
            val unit = (innerPx - thumbWidth) / (count - 1)
            val expansion = thumbWidth / unit - 1f
            // Animate only endpoint widths; skipped tabs never expand.
            val weights = NavItems.indices.map { index ->
                val weight by animateFloatAsState(
                    if (index == target) 1f + expansion else 1f, Motion.ui(), label = "tabWeight$index",
                )
                weight
            }
            fun weight(i: Int) = weights[i]
            fun start(i: Int) = weights.take(i).sum() * unit
            val thumbLeft by animateFloatAsState(
                unit * target, Motion.ui(), label = "tabThumb",
            )

            Box(
                Modifier
                    .offset { IntOffset(thumbLeft.roundToInt(), 0) }
                    .width(with(density) { thumbWidth.toDp() })
                    .fillMaxHeight()
                    .clip(KShape.Control)
                    .background(KColors.AccentWash)
                    .border(1.dp, KColors.Accent, KShape.Control),
            )
            Row(Modifier.fillMaxSize()) {
                NavItems.forEachIndexed { index, (icon, title) ->
                    val chosen = if (expansion != 0f) ((weight(index) - 1f) / expansion).coerceIn(0f, 1f) else if (index == target) 1f else 0f
                    val tint = lerp(KColors.Muted, KColors.Accent, chosen)
                    Row(
                        Modifier.weight(weight(index)).fillMaxHeight().testTag("nav-item-$index"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Center the drawn paths, not just their 24-unit canvas bounds.
                        val iconCenterCorrection = when (icon) {
                            KIcon.Stats -> (-22f / 24f).dp // path midpoint y=13
                            KIcon.Records -> (22f / 96f).dp // path midpoint y=11.75
                            else -> 0.dp
                        }
                        KIcon(icon, tint, modifier = Modifier.offset(y = iconCenterCorrection), size = 22.dp)
                        if (chosen > 0.7f) {
                            Spacer(Modifier.width(6.dp))
                            Text(title, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("nav-gesture")
                    .slidingSelection(
                        selected = selected,
                        indexAt = { x ->
                            NavItems.indices.firstOrNull { x < start(it) + weight(it) * unit } ?: count - 1
                        },
                        preview = { preview = it },
                        onSelect = onSelect,
                    ),
            )
        }
    }
}

class HomeActions(
    val openRoutine: (WorkoutRoutine) -> Unit,
    val routineMenu: (WorkoutRoutine) -> Unit,
    val freeWorkout: () -> Unit,
    val add: () -> Unit,
    val openRunning: () -> Unit,
    val openRunningList: () -> Unit,
    val togglePause: () -> Unit,
    val stop: () -> Unit,
)

class RunningUi(val routineName: String, val detail: String, val paused: Boolean, val elapsedSeconds: () -> Long)

/** One weekday on the strip. [load] is null for a rest day, otherwise 0..TrainingLoad.MAX_SCORE. */
class WeekDayUi(val label: String, val load: Double?, val isToday: Boolean, val isFuture: Boolean)

class RoutineRowUi2(val routine: WorkoutRoutine, val daysAgo: Int?)

class HomeUi(
    val week: List<WeekDayUi>,
    val weekCount: Int,
    val rows: List<RoutineRowUi2>,
    val longestRest: RoutineRowUi2?,
)

@Composable
fun HomeScreen(ui: HomeUi, running: RunningUi?, actions: HomeActions) {
    Column(Modifier.fillMaxSize().background(KColors.Bg)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Text("홈", color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("이번 주 ", color = KColors.Muted, fontSize = 13.sp)
                Text("${ui.weekCount}회", color = KColors.Bone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            WeekStrip(ui.week)
            ui.longestRest?.let {
                Spacer(Modifier.height(14.dp))
                RestHero(it) { actions.openRoutine(it.routine) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("내 루틴", color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("최근", color = KColors.Muted, fontSize = 13.sp)
                KIcon(KIcon.Down, KColors.Muted, size = 14.dp)
            }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(ROUTINE_DIVIDER))
            ui.rows.forEach { row ->
                RoutineRow(row, onClick = { actions.openRoutine(row.routine) }, onMenu = { actions.routineMenu(row.routine) })
            }
            Spacer(Modifier.height(12.dp))
        }
        // The bar for a running workout fades in over the idle buttons (and back).
        AnimatedContent(
            targetState = running,
            transitionSpec = { fadeIn(Motion.ui()) togetherWith fadeOut(Motion.ui()) },
            label = "homeBottom",
        ) { current ->
            if (current == null) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
                    // Each button is 10% smaller but keeps the centre of its original 52dp-high slot.
                    Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                        ActionButton("자유운동", KColors.Accent, KColors.OnAccent, outlined = false, modifier = Modifier.fillMaxWidth(0.9f).height(47.dp), onClick = actions.freeWorkout)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                        ActionButton("새 루틴", KColors.SurfaceRaised, KColors.White, outlined = false, modifier = Modifier.fillMaxWidth(0.9f).height(47.dp), onClick = actions.add, icon = KIcon.Plus)
                    }
                }
            } else {
                RunningBar(current, actions)
            }
        }
    }
}

/** Brighter than KColors.Hairline so the routine list rows read as separate rows on the near-black home. */
private val ROUTINE_DIVIDER = Color(0xFF302E2B)

@Composable
private fun WeekStrip(week: List<WeekDayUi>) {
    val maxBar = 28.dp
    val minBar = 4.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        week.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val entered = rememberEntered()
                val target = when {
                    day.load != null -> minBar + (maxBar - minBar) * (day.load / dev.kyungmin.kcoach.core.TrainingLoad.MAX_SCORE).toFloat()
                    else -> minBar
                }
                val barHeight by animateDpAsState(if (entered) target else minBar, Motion.panel(), label = "bar")
                val color = when {
                    day.load != null -> KColors.Bone
                    day.isToday -> KColors.Sage
                    day.isFuture -> KColors.Surface
                    else -> KColors.SurfaceRaised
                }
                Box(Modifier.fillMaxWidth().height(barHeight).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.height(3.dp))
                Text(
                    day.label,
                    color = if (day.isToday) KColors.White else KColors.Faint,
                    fontSize = 10.sp,
                    fontWeight = if (day.isToday) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun RestHero(row: RoutineRowUi2, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(KShape.Control)
            .border(0.5.dp, Color(0xFF383631), KShape.Control)
            .pressable(onClick)
            .padding(start = 12.dp, top = 12.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val entered = rememberEntered()
        val shownDays by animateIntAsState(if (entered) row.daysAgo ?: 0 else 0, tween(700, easing = LinearEasing), label = "heroDays")
        Text(
            "$shownDays", color = KColors.Bone, fontSize = 64.sp, fontWeight = FontWeight.Medium,
            letterSpacing = (-3).sp, lineHeight = 58.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = 2.dp)) {
            Text("일째 쉬는 루틴", color = KColors.Muted, fontSize = 11.sp)
            Text(row.routine.name, color = KColors.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text("${row.routine.muscles} · ${row.routine.exercises.size}종목", color = KColors.Soft, fontSize = 12.sp)
        }
        KIcon(KIcon.Forward, KColors.Muted, Modifier.padding(end = 6.dp, bottom = 4.dp), 20.dp)
    }
}

@Composable
private fun RoutineRow(row: RoutineRowUi2, onClick: () -> Unit, onMenu: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().pressable(onClick).padding(start = 16.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.routine.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("${row.routine.muscles} · ${row.routine.exercises.size}종목", color = KColors.Soft, fontSize = 12.sp)
            }
            if (row.daysAgo != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${row.daysAgo}", color = KColors.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
                    Text("일 전", color = KColors.Muted, fontSize = 10.sp)
                }
            } else {
                Text(row.routine.lastPerformed, color = KColors.Muted, fontSize = 12.sp)
            }
            Box(Modifier.size(48.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
                KIcon(KIcon.DotsVertical, KColors.Faint, size = 20.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(ROUTINE_DIVIDER))
    }
}

@Composable
private fun ActionButton(text: String, background: Color, content: Color, outlined: Boolean, modifier: Modifier, onClick: () -> Unit, icon: KIcon? = null) {
    // The scale sits outside clip/background so the fill, border and label shrink together as one piece.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "actionPress",
    )
    Box(
        modifier
            .height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(KShape.Control)
            .background(background)
            .then(if (outlined) Modifier.border(0.5.dp, Color(0xFF3F3D39), KShape.Control) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The thin plus reads lighter than the label, so an icon+label pair is nudged slightly left to look centred.
        Row(Modifier.then(if (icon != null) Modifier.offset(x = (-1).dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                KIcon(it, content, size = 20.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RunningBar(running: RunningUi, actions: HomeActions) {
    val elapsed by produceState(running.elapsedSeconds(), running) {
        while (true) {
            value = running.elapsedSeconds()
            delay(1_000)
        }
    }
    Column(Modifier.pointerInput(Unit) {
        // Pulled up like a drawer: switch to the running routine's exercise list.
        var pulled = 0f
        detectVerticalDragGestures(
            onDragStart = { pulled = 0f },
            onVerticalDrag = { _, amount -> pulled += amount },
            onDragEnd = { if (pulled < -48f) actions.openRunningList() },
        )
    }) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Accent))
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(KColors.Faint))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .background(KColors.Bg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).pressable(actions.openRunning)) {
                Text(running.routineName, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(running.detail, color = KColors.Soft, fontSize = 12.sp, maxLines = 1)
                Text("%02d:%02d".format(elapsed / 60, elapsed % 60), color = KColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.size(64.dp).pressable(actions.togglePause), contentAlignment = Alignment.Center) {
                KIcon(if (running.paused) KIcon.Play else KIcon.Pause, KColors.White, size = 26.dp)
            }
            Box(Modifier.size(64.dp).pressable(actions.stop), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Stop, KColors.White, size = 24.dp)
            }
        }
    }
}
