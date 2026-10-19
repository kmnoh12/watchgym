package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.kyungmin.kcoach.core.ProgressionSettings
import dev.kyungmin.kcoach.phone.ui.SettingsActions
import dev.kyungmin.kcoach.phone.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    private val saveDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open selected document")
                    output.use { DiagnosticsExport.write(applicationContext, it) }
                }
            }
            Toast.makeText(this@SettingsActivity,
                if (saved.isSuccess) "오류 기록을 저장했어요. 저장한 ZIP 파일을 채팅에 첨부해 주세요."
                else "오류 기록을 저장하지 못했어요. 저장 위치를 바꿔 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = SettingsActions(back = ::finish,
            tempo = { startActivity(Intent(this, WorkoutTempoSettingsActivity::class.java)) },
            posture = { startActivity(Intent(this, WorkoutPostureActivity::class.java)) },
            profile = { startActivity(Intent(this, WorkoutProfileActivity::class.java)) },
            diagnostics = { saveDiagnostics.launch("watchgym-errors-${System.currentTimeMillis()}.zip") })
        val store = ProgressionSettingsStore(this)
        val progression = store.load().getOrElse {
            Log.w("KCoachEvent", "progression settings unreadable; using defaults", it)
            ProgressionSettings()
        }
        setContent {
            SettingsScreen(actions, progression, PersonalizationSetting.isEnabled(this), { on ->
                if (PersonalizationSetting.setEnabled(this, on)) PersonalizationSetting.pushToWatch(this)
                else Toast.makeText(this, "설정을 저장하지 못했어요. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
            }) { next ->
                store.save(next).onFailure { Log.e("KCoachEvent", "progression settings not saved", it) }
            }
        }
    }
}
