package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class ExerciseInfoUi(
    val name: String,
    val muscles: String,
    val description: String,
    val myRecord: List<Pair<String, String>>,
    val stats: List<Pair<String, String>>,
    val analysisNote: String,
)

@Composable
fun ExerciseInfoScreen(info: ExerciseInfoUi, back: () -> Unit, animation: (heightDp: Int) -> View) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 58.dp).pressable(back), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Back, KColors.White, size = 28.dp)
            }
            Text(info.name, color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        AndroidView(factory = { animation(220) }, modifier = Modifier.fillMaxWidth().height(220.dp))
        Spacer(Modifier.height(6.dp))
        Text(info.name, color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(info.muscles, color = KColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("설명", "나의 기록", "통계").forEachIndexed { index, title ->
                val color by animateColorAsState(if (index == tab) KColors.Accent else KColors.White, Motion.ui(), label = "infoTab")
                Text(
                    title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { tab = index }.padding(vertical = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(Motion.ui()) togetherWith fadeOut(Motion.ui()) },
            label = "infoContent",
        ) { selected ->
            Column {
                when (selected) {
                    0 -> InfoText(info.description)
                    1 -> {
                        info.myRecord.forEachIndexed { i, (title, body) ->
                            if (i > 0) Spacer(Modifier.height(22.dp))
                            Text(title, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            InfoText(body)
                        }
                    }
                    else -> {
                        MetricGrid(info.stats)
                        if (info.analysisNote.isNotBlank()) {
                            Spacer(Modifier.height(20.dp))
                            Text("분석 메모", color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            InfoText(info.analysisNote)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, color = KColors.Soft, fontSize = 14.sp, lineHeight = 21.sp)
}

class RecordSetUi(val index: Int, val reps: Int, val completed: Boolean)

class ExerciseRecordUi(
    val name: String,
    val muscles: String,
    val targetTotal: Int,
    val performed: Int,
    val sets: List<RecordSetUi>,
    val restSeconds: Int,
    val recent: List<RecentLogUi>,
    val best: List<Pair<String, String>>,
)

class RecentLogUi(val date: String, val lines: String)

@Composable
fun ExerciseRecordScreen(record: ExerciseRecordUi, back: () -> Unit, openInfo: () -> Unit, animation: (heightDp: Int) -> View) {
    val scroll = rememberScrollState()
    val progress = (scroll.value / 420f / androidx.compose.ui.platform.LocalDensity.current.density).coerceIn(0f, 1f)
    val animationHeight = lerp(320.dp, 96.dp, progress)
    Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 34.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 58.dp).pressable(back), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Back, KColors.White, size = 28.dp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(54.dp, 58.dp), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Dots, KColors.Muted, size = 24.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        AndroidView(factory = { animation(320) }, modifier = Modifier.fillMaxWidth().height(animationHeight))
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(record.name, color = KColors.White, fontSize = 25.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(54.dp, 58.dp).pressable(openInfo), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Info, KColors.Muted, size = 24.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(record.muscles, color = KColors.Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Row {
            Text("횟수 ${record.targetTotal}회", color = KColors.Accent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(22.dp))
            Text("${record.performed}회", color = KColors.Muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { KIcon(KIcon.Forward, KColors.Muted, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("메모", color = KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { KIcon(KIcon.Forward, KColors.Muted, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("기록", color = KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("세트", color = KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("회", color = KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("완료", color = KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) { KIcon(KIcon.Dots, KColors.Muted, size = 20.dp) }
        }
        record.sets.forEach { set ->
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${set.index + 1}", color = KColors.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("${set.reps}", color = KColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f)) { if (set.completed) KIcon(KIcon.Check, KColors.Accent, size = 20.dp) }
                Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) { KIcon(KIcon.Dots, KColors.Muted, size = 20.dp) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { KIcon(KIcon.Plus, KColors.Accent, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("세트 추가", color = KColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { KIcon(KIcon.Minus, KColors.Soft, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("세트 삭제", color = KColors.Soft, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.height(28.dp))
        Text("현재 기록", color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        MetricGrid(listOf("${record.performed}회" to "최대 반복", "${record.performed}회" to "총 반복"))
        if (record.recent.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("최근 기록", color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                record.recent.forEach { log ->
                    Column(Modifier.width(190.dp).clip(KShape.Control).background(KColors.Surface).padding(14.dp, 12.dp)) {
                        Text(log.date, color = KColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(log.lines, color = KColors.White, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (record.best.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionCard("최고 기록", record.best)
        }
        Spacer(Modifier.height(26.dp))
        Text("휴식 시간", color = KColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("${record.restSeconds}초", color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium)
    }
}
