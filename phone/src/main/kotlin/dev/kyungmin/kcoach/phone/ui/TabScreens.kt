package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutRecord
import dev.kyungmin.kcoach.updates.OtaAction
import dev.kyungmin.kcoach.updates.OtaCard
import dev.kyungmin.kcoach.updates.OtaMessages
import dev.kyungmin.kcoach.updates.OtaNotice
import dev.kyungmin.kcoach.updates.OtaRow
import dev.kyungmin.kcoach.updates.OtaRowKind
import java.time.LocalDate
import java.time.YearMonth

@Composable
private fun TabPage(title: String, right: String, rightIcon: KIcon? = null, rightAction: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(KColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (right.isNotEmpty()) Text(right, color = KColors.Muted, fontSize = 14.sp)
            if (rightIcon != null) {
                if (rightAction == null) KIcon(rightIcon, KColors.Muted, Modifier.padding(start = 4.dp), 22.dp)
                else Box(Modifier.size(48.dp).clip(KShape.Control).semantics { contentDescription = "설정" }
                    .clickable(onClick = rightAction), contentAlignment = Alignment.Center) { KIcon(rightIcon, KColors.White, size = 24.dp) }
            }
        }
        content()
    }
}

@Composable
fun SectionCard(title: String, values: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(title, color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        HairlineRule()
        values.forEach { (name, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = KColors.Soft, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(value, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            HairlineRule()
        }
    }
}

@Composable
private fun HairlineRule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
}

@Composable
fun MetricGrid(metrics: List<Pair<String, String>>) {
    metrics.chunked(2).forEachIndexed { index, pair ->
        if (index > 0) HairlineRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            pair.forEach { (value, title) ->
                Column(Modifier.weight(1f)) {
                    Text(value, color = KColors.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
                    Spacer(Modifier.height(2.dp))
                    Text(title, color = KColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

class RecordsActions(val openResult: () -> Unit, val monthChanged: (YearMonth) -> Unit)

@Composable
fun RecordsScreen(
    records: List<WorkoutRecord>,
    initialMonth: YearMonth,
    lastResultTitle: String?,
    actions: RecordsActions,
) {
    var month by remember { mutableStateOf(initialMonth) }
    TabPage("운동 기록", "", KIcon.Plus) {
        Spacer(Modifier.height(30.dp))
        if (lastResultTitle != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(KShape.Control)
                    .border(0.5.dp, Color(0xFF3F3D39), KShape.Control)
                    .pressable(actions.openResult),
                contentAlignment = Alignment.Center,
            ) {
                Text("최근 완료 결과 · $lastResultTitle", color = KColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(18.dp))
        }
        Column(Modifier.fillMaxWidth().clip(KShape.Control).background(KColors.Surface).padding(16.dp, 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonthArrow(KIcon.Back) {
                    month = month.minusMonths(1)
                    actions.monthChanged(month)
                }
                AnimatedContent(
                    targetState = month,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        fadeIn(tween(Motion.UI_MS / 2, delayMillis = Motion.UI_MS / 2, easing = Motion.UiEasing)) togetherWith
                            fadeOut(tween(Motion.UI_MS / 2, easing = Motion.UiEasing))
                    },
                    label = "month",
                ) { m ->
                    Text(
                        "${m.year}년 ${m.monthValue}월",
                        color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
                MonthArrow(KIcon.Forward) {
                    month = month.plusMonths(1)
                    actions.monthChanged(month)
                }
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = month,
                // Digits of two months must never overlap: the old grid leaves
                // first, the new one arrives after it.
                transitionSpec = {
                    fadeIn(tween(Motion.UI_MS / 2, delayMillis = Motion.UI_MS / 2, easing = Motion.UiEasing)) togetherWith
                        fadeOut(tween(Motion.UI_MS / 2, easing = Motion.UiEasing))
                },
                label = "calendar",
            ) { m -> CalendarGrid(m, records) }
        }
        Spacer(Modifier.height(26.dp))
        Text("최근 운동", color = KColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
        if (records.isEmpty()) {
            Text("아직 기록이 없어요", color = KColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp))
        }
        records.take(4).forEach { record -> RecordCard(record) }
    }
}

@Composable
private fun MonthArrow(icon: KIcon, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).pressable(onClick), contentAlignment = Alignment.Center) {
        KIcon(icon, KColors.Muted, size = 22.dp)
    }
}

@Composable
private fun CalendarGrid(month: YearMonth, records: List<WorkoutRecord>) {
    val recorded = records
        .filter { it.date.startsWith(month.toString()) }
        .map { LocalDate.parse(it.date).dayOfMonth }
        .toSet()
    val offset = month.atDay(1).dayOfWeek.value % 7
    val cells: List<Int?> = List(offset) { null } + (1..month.lengthOfMonth()).toList()
    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach {
                Text(it, color = KColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    Column(Modifier.weight(1f).height(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (day != null) {
                            val has = day in recorded
                            val fill by animateColorAsState(if (has) KColors.Bone else Color.Transparent, Motion.ui(), label = "day")
                            Box(Modifier.clip(CircleShape).background(fill).padding(horizontal = 9.dp, vertical = 4.dp)) {
                                Text(day.toString(), color = if (has) KColors.OnAccent else KColors.Muted, fontSize = 15.sp, fontWeight = if (has) FontWeight.Medium else FontWeight.Normal)
                            }
                            if (has) Text("•", color = KColors.Bone, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordCard(record: WorkoutRecord) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.title, color = KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(record.date.replace("-", "."), color = KColors.Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${record.minutes}분 · ${record.sets}세트", color = KColors.Soft, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("%,dkg".format(record.volumeKg), color = KColors.Muted, fontSize = 12.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
    }
}

class StatsUi(
    val monthLabel: String,
    val metrics: List<Pair<String, String>>,
    val byRoutine: List<Pair<String, String>>,
    /** Sets per body part over the last seven days. */
    val weekSets: Map<String, Int> = emptyMap(),
)

@Composable
fun StatsScreen(ui: StatsUi) {
    TabPage("통계", ui.monthLabel) {
        Spacer(Modifier.height(24.dp))
        Text("최근 7일", color = KColors.Soft, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BodyMap(dev.kyungmin.kcoach.core.BodyLoad.intensity(ui.weekSets), Modifier.weight(1.5f))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                listOf("가슴", "등", "어깨", "삼두", "이두", "하체").forEach { part ->
                    val sets = ui.weekSets[part] ?: 0
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(part, color = if (sets > 0) KColors.White else KColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(if (sets > 0) "${sets}세트" else "-", color = if (sets > 0) KColors.Accent else KColors.Faint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    HairlineRule()
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        if (ui.metrics.isEmpty()) {
            Text("이번 달 기록이 없어요", color = KColors.Soft, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("운동을 끝내면 여기에 쌓여요", color = KColors.Muted, fontSize = 13.sp)
        } else {
            Text("이번 달", color = KColors.Soft, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            MetricGrid(ui.metrics)
            Spacer(Modifier.height(28.dp))
            SectionCard("루틴별 세트", ui.byRoutine)
        }
    }
}

class UpdateUi(val message: String, val readyToInstall: Boolean, val busy: Boolean = false, val card: OtaCard? = null) {
    /** The card to draw; a plain message (before the first check, or from older callers) becomes a card of the same shape. */
    fun asCard(): OtaCard = card ?: OtaCard(
        headline = message,
        phone = OtaRow("폰", if (busy) "확인 중" else "확인 전", if (busy) OtaRowKind.WORKING else OtaRowKind.IDLE),
        watch = OtaRow("워치", if (busy) "확인 중" else "확인 전", if (busy) OtaRowKind.WORKING else OtaRowKind.IDLE),
        action = if (busy) OtaAction.WORKING else if (readyToInstall) OtaAction.INSTALL else OtaAction.CHECK,
        actionLabel = if (busy) "확인 중" else if (readyToInstall) "설치" else "업데이트 확인")
}

class MenuActions(
    val checkUpdate: ((UpdateUi) -> Unit) -> Unit,
    val install: () -> String?,
    val openSettings: () -> Unit = {},
    val observeUpdate: (((UpdateUi) -> Unit) -> (() -> Unit))? = null,
)

@Composable
fun MenuScreen(version: String, actions: MenuActions, wearing: dev.kyungmin.kcoach.phone.WorkoutWearingSelection? = null, notice: OtaNotice? = null,
    onNoticeShown: () -> Unit = {}) {
    var state by remember { mutableStateOf(UpdateUi("업데이트를 확인해 보세요", false)) }
    DisposableEffect(actions.observeUpdate) {
        val unsubscribe = actions.observeUpdate?.invoke { state = it }
        onDispose { unsubscribe?.invoke() }
    }
    TabPage("메뉴", "", KIcon.Settings, actions.openSettings) {
        Spacer(Modifier.height(20.dp))
        UpdatedBanner(notice, onNoticeShown)
        MenuTipBox()
        Spacer(Modifier.height(28.dp))
        SectionCard("기기", listOf(
            "확인한 착용 손" to when (wearing?.side) { "LEFT" -> "왼손"; "RIGHT" -> "오른손"; else -> "미확인" },
            "확인한 화면 방향" to when (wearing?.placement) { "OUTSIDE" -> "손등 쪽"; "INSIDE" -> "손바닥 쪽"; else -> "미확인" }))
        Spacer(Modifier.height(28.dp))
        SectionCard("앱", listOf("현재 버전" to version))
        Spacer(Modifier.height(28.dp))
        val card = state.asCard()
        UpdateCard(card) {
            when (card.action) {
                OtaAction.WORKING -> Unit
                OtaAction.INSTALL -> {
                    // The button answers at once; the coordinator's own states replace this a moment later.
                    state = UpdateUi(card.headline, true, true, card.copy(headline = "업데이트하는 중", action = OtaAction.WORKING, actionLabel = "설치 중"))
                    val refusal = actions.install()
                    if (refusal == OtaMessages.NOTHING_TO_INSTALL || refusal == OtaMessages.CHECK_FIRST)
                        state = UpdateUi(refusal, false, false, card.copy(headline = refusal, action = OtaAction.CHECK, actionLabel = "업데이트 확인"))
                }
                else -> {
                    state = UpdateUi("업데이트 확인 중", false, busy = true)
                    actions.checkUpdate { result -> state = result }
                }
            }
        }
    }
}

/** Shown once, at the top of the menu, the first time the app opens after it was replaced by an update. */
@Composable
private fun UpdatedBanner(notice: OtaNotice?, onShown: () -> Unit) {
    var visible by remember { mutableStateOf(notice != null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        visible = true
        kotlinx.coroutines.delay(3_800)
        visible = false
        kotlinx.coroutines.delay(400)
        onShown()
    }
    AnimatedVisibility(visible && notice != null, enter = fadeIn(tween(260)) + expandVertically(tween(260)), exit = fadeOut(tween(260)) + shrinkVertically(tween(260))) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(KShape.Control).background(KColors.AccentWash).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KIcon(KIcon.Check, KColors.Accent, Modifier.padding(end = 12.dp), 22.dp)
                Column {
                    Text("${notice?.to.orEmpty()}로 업데이트했어요", color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("이전 ${notice?.from.orEmpty()}", color = KColors.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Two fixed rows (phone, watch) and one button whose label and colour change in place, so nothing moves while updating. */
@Composable
private fun UpdateCard(card: OtaCard, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("업데이트", color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        HairlineRule()
        UpdateRow(card.phone)
        HairlineRule()
        UpdateRow(card.watch ?: OtaRow("워치", "연결 안 됨", OtaRowKind.IDLE))
        HairlineRule()
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            Crossfade(card.headline, animationSpec = tween(200), label = "updateHeadline") {
                Text(it, color = KColors.Muted, fontSize = 14.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        UpdateButton(card.action, card.actionLabel, onClick)
    }
}

@Composable
private fun UpdateRow(row: OtaRow) {
    val bar by animateFloatAsState(row.progress ?: 0f, tween(300), label = "updateProgress")
    val color by animateColorAsState(
        when (row.kind) {
            OtaRowKind.READY -> KColors.Accent
            OtaRowKind.FAIL -> KColors.Red
            OtaRowKind.IDLE -> KColors.Muted
            else -> KColors.White
        }, tween(240), label = "updateRowColor")
    Column(Modifier.fillMaxWidth().height(60.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.device, color = KColors.Soft, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Crossfade(row.text, animationSpec = tween(200), label = "updateRowText") {
                Text(it, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        // The bar's slot is always there, so a row never gets taller when a progress bar appears.
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (row.progress != null) KColors.Hairline else Color.Transparent)) {
            if (row.progress != null) Box(Modifier.fillMaxWidth(bar).height(3.dp).background(KColors.Accent))
        }
    }
}

@Composable
private fun UpdateButton(action: OtaAction, label: String, onClick: () -> Unit) {
    val working = action == OtaAction.WORKING
    val fill by animateColorAsState(when (action) {
        OtaAction.INSTALL -> KColors.Accent
        OtaAction.WORKING -> KColors.SurfaceRaised
        else -> Color.Transparent
    }, tween(240), label = "updateButtonFill")
    val outlined = action == OtaAction.CHECK || action == OtaAction.RETRY
    Box(
        Modifier
            .testTag("updateAction")
            .fillMaxWidth()
            .height(58.dp)
            .clip(KShape.Control)
            .background(fill)
            .then(if (outlined) Modifier.border(0.5.dp, Color(0xFF3F3D39), KShape.Control) else Modifier)
            .then(if (working) Modifier else Modifier.pressable(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(working) {
                Row {
                    CircularProgressIndicator(Modifier.size(18.dp), color = KColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
            }
            Crossfade(label, animationSpec = tween(200), label = "updateButtonLabel") {
                Text(it, color = if (action == OtaAction.INSTALL) KColors.OnAccent else KColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private val MENU_TIPS = listOf(
    "워치를 손목에 단단히 고정하면\n센서가 움직임을 더 정확하게 읽어요.",
    "운동 전 측정·보정을 해 두면\n내 동작에 맞춰 횟수를 세어줘요.",
    "기록은 로그인 없이 이 폰에만 저장돼요.\n센서 기록은 운동과 보정 중에만 켜져요.",
)

@Composable
private fun MenuTipBox() {
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            index = (index + 1) % MENU_TIPS.size
        }
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(KShape.Control)
            .border(0.5.dp, Color(0xFF3F3D39), KShape.Control).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(MENU_TIPS[index], color = KColors.Soft, fontSize = 14.sp, maxLines = 2)
    }
}
