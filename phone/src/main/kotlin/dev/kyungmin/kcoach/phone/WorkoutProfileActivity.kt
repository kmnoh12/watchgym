package dev.kyungmin.kcoach.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.ui.*

/** Local body and wearing defaults. */
class WorkoutProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = UserProfileStore(applicationContext)
        val loaded = runCatching { store.deviceProfile() }
        val wearing = runCatching { WorkoutWearingStore(this).defaultSelection().getOrThrow() }
        setContent {
            WorkoutProfileTheme {
                if (loaded.isFailure || wearing.isFailure) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text("저장한 프로필을 열지 못했어요. 기존 정보는 유지돼요.", color = KColors.White)
                        TextButton(onClick = ::finish) { Text("돌아가기") }
                    }
                } else {
                    val profile = remember { loaded.getOrNull()?.profile ?: WorkoutProfile() }
                    WorkoutProfileEditor(profile, wearing.getOrNull(), ::finish) { value ->
                        store.saveDeviceProfile(value)
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkoutProfileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, background = KColors.Bg,
        surface = KColors.Surface, onSurface = KColors.White), content = content)
}

@Composable
internal fun WorkoutProfileEditor(profile: WorkoutProfile, previous: WorkoutWearingSelection?,
    back: () -> Unit, save: (DeviceWorkoutProfile) -> Unit) {
    var side by rememberSaveable { mutableStateOf(previous?.side) }
    ProfileEditor(profile, onDismiss = back, extraContent = {
        Text("평소 워치 착용", color = KColors.White, fontSize = 17.sp)
        Text("새 운동의 기본값이에요. 진행 중인 운동의 착용 조건은 바뀌지 않아요.", color = KColors.Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("LEFT" to "왼손", "RIGHT" to "오른손").forEach { (value, label) ->
                OutlinedButton(onClick = { side = value }, modifier = Modifier.weight(1f), shape = KShape.Control,
                    border = BorderStroke(1.dp, if (side == value) KColors.Accent else KColors.Hairline)) { Text(label) }
            }
        }
        Text("워치는 손등 쪽에 착용해 주세요.", color = KColors.Muted, fontSize = 13.sp)
        TextButton(onClick = { side = null }) { Text("착용 상태 미설정") }
        Text("프로필은 폰에 저장해요. 이전 운동에 저장된 신체 정보는 바뀌지 않아요.",
            color = KColors.Muted, fontSize = 12.sp)
    }) { draft ->
        save(DeviceWorkoutProfile(draft, side?.let { WorkoutWearingSelection(it, "OUTSIDE") }))
    }
}
