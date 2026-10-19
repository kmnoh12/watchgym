package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.core.ProgressionSettings
import dev.kyungmin.kcoach.core.ProgressionSpeed
import kotlin.math.roundToInt

class SettingsActions(val back: () -> Unit, val profile: () -> Unit = {}, val tempo: () -> Unit = {}, val posture: () -> Unit = {},
    val diagnostics: () -> Unit = {})

@Composable
fun SettingsScreen(
    actions: SettingsActions,
    progression: ProgressionSettings = ProgressionSettings(),
    onProgressionChange: (ProgressionSettings) -> Unit = {},
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, onPrimary = KColors.OnAccent,
        background = KColors.Bg, surface = KColors.Surface, onSurface = KColors.White, outline = KColors.Hairline)) {
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.back) { KIcon(KIcon.Back, KColors.White) }
                Text("설정", color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(28.dp))
            ProgressionSection(progression, onProgressionChange)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = actions.profile, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("내 운동 프로필 · 워치 착용", color = KColors.White)
            }
            Text("신체 정보와 평소 착용 상태를 폰에 저장해요. 로그인 없이 설정할 수 있어요.", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = actions.diagnostics, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("오류 기록 저장", color = KColors.White)
            }
            Text("앱 종료 원인을 확인할 ZIP 파일을 저장해요. 저장한 파일을 채팅에 첨부해 주세요.", color = KColors.Muted, fontSize = 13.sp)
        }
    }
}

private val GROUP_BORDER = Color(0xFF3F3D39)

/** One switch for the whole app; the speed controls underneath dim while it is off. */
@Composable
private fun ProgressionSection(settings: ProgressionSettings, onChange: (ProgressionSettings) -> Unit) {
    var current by remember { mutableStateOf(settings) }
    val haptic = LocalHapticFeedback.current
    fun update(next: ProgressionSettings) {
        if (next == current) return // a drag repeats the same detent; buzz and save only on a real change
        haptic.performHapticFeedback(if (next.enabled != current.enabled) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        current = next
        onChange(next)
    }
    Column(Modifier.fillMaxWidth().clip(KShape.Panel).border(0.5.dp, GROUP_BORDER, KShape.Panel).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("점진적 과부하 사용", color = KColors.White, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text("운동 결과에 맞춰 다음 목표를 미리 채워요", color = KColors.Soft, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            SquareSwitch(current.enabled) { update(current.copy(enabled = it)) }
        }
        Column(Modifier.alpha(if (current.enabled) 1f else .35f)) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom) {
                Text("진행 속도", color = KColors.Soft, fontSize = 12.sp)
                Text(current.speed.label(), color = KColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            SpeedSlider(current.speed, current.enabled) { update(current.copy(speed = it)) }
            Spacer(Modifier.height(10.dp))
            Text(current.speed.description(), color = KColors.Soft, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(14.dp))
            ProgressionExample(current.speed)
        }
    }
}

@Composable
private fun SquareSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val position by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "switch")
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).clip(KShape.Control)
            .background(if (on) KColors.Accent else KColors.Hairline)
            .toggleable(on, MutableInteractionSource(), indication = null, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "점진적 과부하 사용" },
    ) {
        Box(
            Modifier.offset(x = (3 + 18 * position).dp, y = 3.dp).size(20.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(if (on) KColors.OnAccent else KColors.Muted),
        )
    }
}

private val THUMB = 22.dp

/** Three detents on a hairline track; a tap or a drag snaps to the nearest one. */
@Composable
private fun SpeedSlider(speed: ProgressionSpeed, enabled: Boolean, onSpeed: (ProgressionSpeed) -> Unit) {
    val all = ProgressionSpeed.values()
    val index = all.indexOf(speed)
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }
    val thumbPx = with(density) { THUMB.toPx() }
    fun nearest(x: Float): ProgressionSpeed {
        val span = (widthPx - thumbPx).coerceAtLeast(1f)
        return all[(((x - thumbPx / 2) / span).coerceIn(0f, 1f) * (all.size - 1)).roundToInt()]
    }
    val fraction by animateFloatAsState(index.toFloat() / (all.size - 1), tween(200), label = "speed")
    val travel = with(density) { (widthPx - thumbPx).coerceAtLeast(0f).toDp() }
    Box(
        Modifier.fillMaxWidth().height(40.dp).padding(top = 6.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics { contentDescription = "진행 속도"; stateDescription = speed.label() }
            .pointerInput(enabled) { if (enabled) detectTapGestures { onSpeed(nearest(it.x)) } }
            .pointerInput(enabled) { if (enabled) detectHorizontalDragGestures { change, _ -> onSpeed(nearest(change.position.x)) } },
    ) {
        Box(Modifier.align(Alignment.CenterStart).padding(horizontal = THUMB / 2).fillMaxWidth().height(2.dp).background(KColors.Hairline))
        Box(Modifier.align(Alignment.CenterStart).padding(start = THUMB / 2).width(travel * fraction).height(2.dp).background(KColors.Accent))
        all.indices.forEach { i ->
            Box(Modifier.align(Alignment.CenterStart).offset(x = THUMB / 2 + travel * (i.toFloat() / (all.size - 1)) - 1.dp)
                .size(width = 2.dp, height = 12.dp).background(KColors.Faint))
        }
        Box(Modifier.align(Alignment.CenterStart).offset(x = travel * fraction).size(THUMB).clip(KShape.Control).background(KColors.Accent))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        all.forEach { Text(it.label(), color = if (it == speed) KColors.White else KColors.Muted, fontSize = 12.sp) }
    }
}

/** A labelled sample of what the chosen speed does, so the numbers are not mistaken for the user's own. */
@Composable
private fun ProgressionExample(speed: ProgressionSpeed) {
    Column(Modifier.fillMaxWidth().clip(KShape.Control).background(KColors.Surface).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("예시", color = KColors.Soft, fontSize = 11.sp,
                modifier = Modifier.border(0.5.dp, GROUP_BORDER, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            Spacer(Modifier.width(6.dp))
            Text("벤치 프레스 · 목표 6~10회", color = KColors.Soft, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("이런 날이면", color = KColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("다음 목표", color = KColors.Muted, fontSize = 11.sp)
        }
        ExampleRow("10회를 다 채운 첫날", speed.exampleFirst())
        ExampleRow("10회를 다 채운 날이 2번 이어진 때", speed.exampleSecond())
    }
}

@Composable
private fun ExampleRow(situation: String, next: String) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(situation, color = KColors.Soft, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(next, color = KColors.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun ProgressionSpeed.label() = when (this) {
    ProgressionSpeed.SLOW -> "천천히"
    ProgressionSpeed.NORMAL -> "보통"
    ProgressionSpeed.FAST -> "빠르게"
}

private fun ProgressionSpeed.description() = when (this) {
    ProgressionSpeed.SLOW -> "목표 횟수를 다 채운 날이 3번 이어지면 무게를 올려요."
    ProgressionSpeed.NORMAL -> "목표 횟수를 다 채운 날이 2번 이어지면 무게를 올려요."
    ProgressionSpeed.FAST -> "목표 횟수를 다 채운 날, 바로 무게를 올려요."
}

private fun ProgressionSpeed.exampleFirst() = if (this == ProgressionSpeed.FAST) "85kg 6회" else "80kg 10회"
private fun ProgressionSpeed.exampleSecond() = if (this == ProgressionSpeed.SLOW) "80kg 10회" else "85kg 6회"
