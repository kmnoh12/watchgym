package dev.kyungmin.kcoach.phone.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Active workout screen. The model is rebuilt whenever the activity mutates
 * the workout; only the parts that changed animate, so nothing jumps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActiveWorkoutScreen(
    model: WorkoutUiModel,
    actions: WorkoutActions,
    liveTitle: LiveLine,
    liveTelemetry: LiveLine,
    animationView: (exerciseIndex: Int) -> View,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var buffer by remember { mutableStateOf("") }
    var freshEdit by remember { mutableStateOf(true) }
    var applyAll by remember { mutableStateOf(false) }
    var restExpanded by remember { mutableStateOf(false) }

    val now by produceState(System.currentTimeMillis(), model.restEndAtMs, model.paused, model.isPreview) {
        // Browsing a routine is not a running workout. No clock ticks until it starts.
        if (model.isPreview) {
            value = model.startedAtMs
            return@produceState
        }
        while (true) {
            value = System.currentTimeMillis()
            delay(if (model.restEndAtMs > 0L) 50L else 500L)
        }
    }
    val rest = RestClock.of(model, now)
    val current = model.exercises.getOrNull(model.exerciseIndex)

    val pager = rememberPagerState(initialPage = model.exerciseIndex) { model.exercises.size }
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != model.exerciseIndex) {
            editing = null
            actions.onPageSettled(pager.settledPage)
        }
    }
    LaunchedEffect(model.exerciseIndex) {
        if (pager.currentPage != model.exerciseIndex && !pager.isScrollInProgress) {
            pager.animateScrollToPage(model.exerciseIndex, animationSpec = Motion.page())
        }
    }

    fun openEditor(target: EditTarget) {
        val exercise = model.exercises.getOrNull(target.exerciseIndex) ?: return
        val set = exercise.sets.getOrNull(target.setIndex) ?: return
        editing = target
        buffer = if (target.field == SetField.WEIGHT) set.weightText.takeIf { it != "체중" }.orEmpty() else set.reps?.toString().orEmpty()
        freshEdit = true
    }

    fun fieldsOf(exerciseIndex: Int): List<EditTarget> {
        val exercise = model.exercises.getOrNull(exerciseIndex) ?: return emptyList()
        return exercise.sets.indices.flatMap { setIndex ->
            if (exercise.weighted) listOf(EditTarget(exerciseIndex, setIndex, SetField.WEIGHT), EditTarget(exerciseIndex, setIndex, SetField.REPS))
            else listOf(EditTarget(exerciseIndex, setIndex, SetField.REPS))
        }
    }

    fun push(value: String) {
        val target = editing ?: return
        buffer = value
        actions.onEditValue(target.exerciseIndex, target.setIndex, target.field, value, applyAll)
    }

    fun closeEditor() {
        if (editing != null) actions.onEditFinished()
        editing = null
    }

    BackHandler(enabled = editing != null || restExpanded) {
        if (restExpanded) restExpanded = false else closeEditor()
    }

    Box(Modifier.fillMaxSize().background(KColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(model, now, actions)
            LivePanel(liveTitle, liveTelemetry, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                key = { model.exercises[it].id + it },
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = Motion.page()),
                modifier = Modifier.weight(1f),
            ) { page ->
                ExercisePage(
                    model = model,
                    exerciseIndex = page,
                    editing = editing?.takeIf { it.exerciseIndex == page },
                    keypadOpen = editing != null,
                    animationView = animationView,
                    actions = actions,
                    onOpenEditor = ::openEditor,
                    onRemoveSet = { setIndex ->
                        closeEditor()
                        actions.onRemoveSet(page, setIndex)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val result = snackbar.showSnackbar("세트를 삭제했어요", actionLabel = "되돌리기", duration = SnackbarDuration.Short)
                            if (result == SnackbarResult.ActionPerformed) actions.onUndoRemoveSet()
                        }
                    },
                    onNextExercise = {
                        scope.launch { pager.animateScrollToPage(page + 1, animationSpec = Motion.page()) }
                    },
                )
            }
            RecordBanner(model.recordBanner)
            RestBar(
                rest = rest,
                idleSeconds = current?.restSeconds ?: 0,
                paused = model.paused,
                onExpand = { closeEditor(); restExpanded = true },
                onAdjust = actions::onAdjustRest,
                onTogglePause = actions::onTogglePause,
                onStop = { if (rest.resting) actions.onSkipRest() else actions.onStop() },
            )
            AnimatedVisibility(
                visible = editing != null,
                enter = slideInVertically(Motion.panel()) { it },
                exit = slideOutVertically(Motion.panel()) { it },
            ) {
                val target = editing
                WorkoutKeypad(
                    field = target?.field ?: SetField.REPS,
                    applyAll = applyAll,
                    usePounds = model.usePounds,
                    onDigit = { c ->
                        val base = if (freshEdit) "" else buffer
                        freshEdit = false
                        if (c == '.' && base.contains('.')) return@WorkoutKeypad
                        val next = (if (base == "0" && c != '.') "" else base) + c
                        if (next.length <= 6) push(next)
                    },
                    onBackspace = { freshEdit = false; push(buffer.dropLast(1)) },
                    onDelta = { delta ->
                        freshEdit = false
                        val value = ((buffer.toDoubleOrNull() ?: 0.0) + delta).coerceAtLeast(0.0)
                        push(if (target?.field == SetField.WEIGHT) cleanNumber(value) else value.toInt().toString())
                    },
                    onPrevious = {
                        val all = fieldsOf(target?.exerciseIndex ?: model.exerciseIndex)
                        val i = all.indexOf(target)
                        if (i > 0) { actions.onEditFinished(); openEditor(all[i - 1]) }
                    },
                    onNext = {
                        val all = fieldsOf(target?.exerciseIndex ?: model.exerciseIndex)
                        val i = all.indexOf(target)
                        if (i in 0 until all.lastIndex) { actions.onEditFinished(); openEditor(all[i + 1]) }
                    },
                    onDone = {
                        val t = target
                        closeEditor()
                        if (t != null) {
                            val set = model.exercises.getOrNull(t.exerciseIndex)?.sets?.getOrNull(t.setIndex)
                            if (set != null && !set.completed) actions.onToggleSet(t.exerciseIndex, t.setIndex)
                        }
                    },
                    onToggleAll = { applyAll = !applyAll },
                    onToggleUnit = actions::onToggleUnit,
                )
            }
        }

        // Last seconds of rest: drop in the next-set banner.
        AnimatedVisibility(
            visible = rest.resting && rest.leftMs in 1..5_999 && model.nextSetText != null && !restExpanded,
            enter = slideInVertically(Motion.panel()) { -it },
            exit = slideOutVertically(Motion.panel()) { -it },
        ) {
            NextSetBanner(model.nextSetText.orEmpty(), rest.leftMs, actions::onSkipRest)
        }

        AnimatedVisibility(
            visible = restExpanded,
            enter = slideInVertically(Motion.panel()) { it },
            exit = slideOutVertically(Motion.panel()) { it },
        ) {
            Box {
                ExpandedRestPanel(
                    rest = rest,
                    idleSeconds = current?.restSeconds ?: 0,
                    paused = model.paused,
                    onClose = { restExpanded = false },
                    onAdjust = actions::onAdjustRest,
                    onPreset = actions::onRestPreset,
                    onTogglePause = actions::onTogglePause,
                    onSkip = { actions.onSkipRest(); restExpanded = false },
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(model: WorkoutUiModel, now: Long, actions: WorkoutActions) {
    val pausedNow = if (model.paused && model.pausedSinceMs > 0L) now - model.pausedSinceMs else 0L
    val elapsed = if (model.isPreview) 0L else
        ((now - model.startedAtMs - model.totalPausedMs - pausedNow) / 1000L).coerceAtLeast(0L)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable { actions.onBack() }, contentAlignment = Alignment.Center) { KIcon(KIcon.Back, KColors.White, size = 26.dp) }
        Column(Modifier.weight(1f)) {
            Text(model.routineName, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            PageDots(model.exercises.size, model.exerciseIndex, Modifier.padding(top = 5.dp))
        }
        // Long-press the clock to pause; the timer dims while paused.
        val clockColor by animateColorAsState(if (model.isPreview || model.paused) KColors.Muted else KColors.White, Motion.ui(), label = "clock")
        Text(
            String.format("%02d:%02d", elapsed / 60, elapsed % 60),
            color = clockColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { if (!model.isPreview) actions.onTogglePause() }).padding(horizontal = 8.dp),
        )
        Box(Modifier.size(44.dp).clickable { actions.onMenu() }, contentAlignment = Alignment.Center) { KIcon(KIcon.Dots, KColors.Muted, size = 22.dp) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePage(
    model: WorkoutUiModel,
    exerciseIndex: Int,
    editing: EditTarget?,
    keypadOpen: Boolean,
    animationView: (Int) -> View,
    actions: WorkoutActions,
    onOpenEditor: (EditTarget) -> Unit,
    onRemoveSet: (Int) -> Unit,
    onNextExercise: () -> Unit,
) {
    val exercise = model.exercises[exerciseIndex]
    val restingSetIndex = model.restingSet?.takeIf { it.first == exerciseIndex }?.second
    val scroll = rememberScrollState()
    val imageHeight by animateDpAsState(if (keypadOpen) 92.dp else 190.dp, Motion.panel(), label = "image")
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {
        AndroidView(
            factory = { animationView(exerciseIndex) },
            modifier = Modifier.fillMaxWidth().height(imageHeight).clip(KShape.Control),
            onRelease = ::stopAnimatedImages,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(exercise.name, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clickable { actions.onExerciseInfo(exerciseIndex) }, contentAlignment = Alignment.Center) { KIcon(KIcon.Info, KColors.Muted, size = 20.dp) }
        }
        Text(exercise.muscles, color = KColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("목표 ", color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            FlipText(exercise.targetReps.toString(), 14.sp, KColors.Accent)
            Text("회", color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("수행 ", color = if (exercise.performedReps > 0) KColors.Sage else KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            FlipText(exercise.performedReps.toString(), 14.sp, if (exercise.performedReps > 0) KColors.Sage else KColors.Muted)
            Text("회", color = if (exercise.performedReps > 0) KColors.Sage else KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (exercise.volumeTarget > 0) {
            Spacer(Modifier.height(10.dp))
            VolumeBar(exercise.volumeDone, exercise.volumeTarget, if (model.usePounds) "lb" else "kg", exercise.history?.lastVolume)
        }
        exercise.history?.let { history ->
            Spacer(Modifier.height(6.dp))
            Text(history.lastLine, color = KColors.Muted, fontSize = 12.sp)
            if (history.suggestion != null && exercise.completedWorkSets == 0) {
                Spacer(Modifier.height(8.dp))
                NextTargetRow(history.suggestion, history.reason, history.applicable) { actions.onApplySuggestion(exerciseIndex) }
            }
        }
        Spacer(Modifier.height(12.dp))
        SetHeader(exercise.weighted, model.usePounds)
        exercise.sets.forEachIndexed { setIndex, set ->
            androidx.compose.runtime.key(set.uid) {
                val requester = remember { BringIntoViewRequester() }
                val focused = editing?.setIndex == setIndex
                LaunchedEffect(focused, keypadOpen) {
                    // Keep the edited row above the keypad once it has slid in.
                    if (focused && keypadOpen) { delay(Motion.PANEL_MS.toLong()); requester.bringIntoView() }
                }
                SwipeRevealDelete(
                    enabled = exercise.sets.size > 1,
                    onDelete = { onRemoveSet(setIndex) },
                    modifier = Modifier.bringIntoViewRequester(requester),
                ) {
                    SetRow(
                        set = set,
                        weighted = exercise.weighted,
                        isCurrent = !model.isPreview && (if (model.restingSet != null) model.restingSet == (exerciseIndex to setIndex)
                            else exerciseIndex == model.exerciseIndex && setIndex == exercise.currentSetIndex && !set.completed),
                        editingField = editing?.takeIf { it.setIndex == setIndex }?.field,
                        onLabel = { actions.onSetMenu(exerciseIndex, setIndex) },
                        onWeight = { onOpenEditor(EditTarget(exerciseIndex, setIndex, SetField.WEIGHT)) },
                        onReps = { onOpenEditor(EditTarget(exerciseIndex, setIndex, SetField.REPS)) },
                        onCheck = { actions.onToggleSet(exerciseIndex, setIndex) },
                        onMore = { actions.onSetMenu(exerciseIndex, setIndex) },
                    )
                }
                if (setIndex < exercise.sets.lastIndex) {
                    SetRestRow(set, onClick = { actions.onSetRest(exerciseIndex, setIndex) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Removes the last set that is not done yet, so finished work is never deleted from here.
        val removableSet = exercise.sets.indexOfLast { !it.completed }.takeIf { exercise.sets.size > 1 }
        Row {
            TextAction("세트 추가", KColors.Accent, Modifier.weight(1f), KIcon.Plus) { actions.onAddSet(exerciseIndex) }
            TextAction("세트 삭제", if (removableSet != null) KColors.Soft else KColors.Faint, Modifier.weight(1f), KIcon.Minus) {
                removableSet?.let { actions.onRemoveSet(exerciseIndex, it) }
            }
        }
        TextAction("모든 세트 완료", KColors.Accent, Modifier.fillMaxWidth(), KIcon.Check) { actions.onAllSetsDone(exerciseIndex) }
        if (exerciseIndex < model.exercises.lastIndex) {
            TextAction("다음 운동", KColors.White, Modifier.fillMaxWidth(), KIcon.Forward, iconAfter = true) { onNextExercise() }
        }
        Text(
            if (model.isPreview) "운동 시작 전 · 총 ${exercise.sets.size}세트" else
                if (restingSetIndex != null) "${exercise.sets.getOrNull(restingSetIndex)?.label ?: "-"}세트 후 휴식 · 완료 ${exercise.completedWorkSets}/${exercise.workSetCount}세트" else
                "현재 ${exercise.sets.getOrNull(exercise.currentSetIndex)?.label ?: "-"}세트 · 완료 ${exercise.completedWorkSets}/${exercise.workSetCount}세트",
            color = if (model.isPreview) KColors.Muted else KColors.Sage,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(if (keypadOpen) 24.dp else 40.dp))
    }
}

private val SetWeights = listOf(0.8f, 1.25f, 1.15f, 0.8f, 0.45f)

@Composable
private fun SetHeader(weighted: Boolean, usePounds: Boolean) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("세트", SetWeights[0])
        if (weighted) HeaderCell(if (usePounds) "LB" else "KG", SetWeights[1])
        HeaderCell("회", SetWeights[2])
        HeaderCell("완료", SetWeights[3])
        Spacer(Modifier.weight(SetWeights[4]))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(text, color = KColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(weight), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun SetRow(
    set: SetUi,
    weighted: Boolean,
    isCurrent: Boolean,
    editingField: SetField?,
    onLabel: () -> Unit,
    onWeight: () -> Unit,
    onReps: () -> Unit,
    onCheck: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(46.dp).background(KColors.Bg)) {
        Row(
            Modifier.fillMaxSize().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cell(set.label, Modifier.weight(SetWeights[0]), if (isCurrent) KColors.Sage else KColors.Muted, null, onLabel, sub = set.previous)
            if (weighted) Cell(set.weightText, Modifier.weight(SetWeights[1]), KColors.White, editingField == SetField.WEIGHT, onWeight)
            Cell(set.reps?.toString() ?: "—", Modifier.weight(SetWeights[2]), KColors.White, editingField == SetField.REPS, onReps)
            CheckCell(set.completed, Modifier.weight(SetWeights[3]).padding(horizontal = 4.dp).height(40.dp).clickable { onCheck() }, highlighted = isCurrent)
            Box(Modifier.weight(SetWeights[4]).clickable { onMore() }, contentAlignment = Alignment.Center) { KIcon(KIcon.Dots, KColors.Muted, size = 18.dp) }
        }
        if (isCurrent) {
            Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(20.dp)
                .clip(RoundedCornerShape(1.dp)).background(KColors.Sage))
        }
    }
}

@Composable
private fun Cell(
    text: String,
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color,
    editing: Boolean?,
    onClick: () -> Unit,
    sub: String? = null,
) {
    val border by animateColorAsState(if (editing == true) KColors.Accent else androidx.compose.ui.graphics.Color.Transparent, Motion.ui(), label = "cell")
    Box(
        modifier.padding(horizontal = 4.dp).height(40.dp)
            .clip(KShape.Control)
            .background(if (editing == null) androidx.compose.ui.graphics.Color.Transparent else KColors.SurfaceRaised)
            .border(1.5.dp, border, KShape.Control)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FlipText(text, 15.sp, color)
            if (sub != null) Text(sub, color = KColors.Faint, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NextTargetRow(text: String, reason: String?, applicable: Boolean, onApply: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KShape.Control)
            .border(0.5.dp, KColors.Hairline, KShape.Control)
            .then(if (applicable) Modifier.pressable(onApply) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("다음 목표  ", color = KColors.Muted, fontSize = 11.sp)
                Text(text, color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (reason != null) Text(reason, color = KColors.Muted, fontSize = 11.sp)
        }
        if (applicable) Text("적용", color = KColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SetRestRow(set: SetUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (set.insight != null) "${set.grip} · ${set.insight}" else set.grip,
            color = if (set.insight != null) KColors.Soft else KColors.Faint,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text("${set.restSeconds}초 ⏱", color = if (set.customRest) KColors.Accent else KColors.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextAction(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    icon: KIcon? = null,
    iconAfter: Boolean = false,
    onClick: () -> Unit,
) {
    Box(modifier.height(46.dp).clip(KShape.Control).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null && !iconAfter) { KIcon(icon, color, size = 18.dp); Spacer(Modifier.width(6.dp)) }
            Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (icon != null && iconAfter) { Spacer(Modifier.width(4.dp)); KIcon(icon, color, size = 18.dp) }
        }
    }
}

/** Stop GIF playback threads when a page's view leaves the composition. */
private fun stopAnimatedImages(view: View) {
    when (view) {
        is android.widget.ImageView -> (view.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
        is ViewGroup -> for (i in 0 until view.childCount) stopAnimatedImages(view.getChildAt(i))
    }
}

fun cleanNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
