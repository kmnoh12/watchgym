package dev.kyungmin.kcoach.wear

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Watch side of the measurement probe: start/stop streaming and toggle screen-on. */
class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var startButton: TextView
    private lateinit var screenButton: TextView
    private var keepScreenOn = true

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 40)
        }
        column.addView(text("KCOACH 실측", 12f, WatchWorkoutFace.SAGE, bold = true))
        statusText = text("", 11f, Color.WHITE).apply { setPadding(0, 10, 0, 10) }
        column.addView(statusText)
        startButton = button("시작") { toggleProbe() }
        column.addView(startButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        screenButton = button("") {
            keepScreenOn = !keepScreenOn
            applyScreenPolicy()
            render()
        }
        column.addView(screenButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        column.addView(text(lastExitSummary(), 9f, WatchWorkoutFace.MUTED).apply { setPadding(0, 14, 0, 0) })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
        applyScreenPolicy()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun toggleProbe() {
        val intent = Intent(this, ProbeService::class.java)
        if (ProbeStatus.running) stopService(intent) else startForegroundService(intent)
        handler.postDelayed({ render() }, 300L)
    }

    private fun applyScreenPolicy() {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun render() {
        val s = ProbeStatus
        startButton.text = if (s.running) "정지" else "시작"
        screenButton.text = if (keepScreenOn) "화면 켜둠: 켬" else "화면 켜둠: 끔"
        statusText.text = if (!s.running) {
            "폰에서 실측 화면을 열면 자동으로 시작해요"
        } else buildString {
            append(if (s.connected) "● 폰 연결됨\n" else "○ ${s.message}\n")
            append("가속도 %.0fHz · 자이로 %.0fHz\n".format(s.accelHz, s.gyroHz))
            append("보냄 ${s.sentSamples} · 대기 ${s.bufferedSamples}")
            if (s.droppedSamples > 0) append("\n버림 ${s.droppedSamples}")
            append("\n최소 주기 ${s.accelMinDelayUs}/${s.gyroMinDelayUs}µs")
        }
    }

    /** Why the app's previous process ended, to diagnose screen-off kills. */
    private fun lastExitSummary(): String {
        val info = getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 1)
            .firstOrNull() ?: return "지난 종료 기록 없음"
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "앱 스스로 종료"
            ApplicationExitInfo.REASON_SIGNALED -> "시스템 신호로 종료"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "메모리 부족"
            ApplicationExitInfo.REASON_CRASH -> "앱 오류(크래시)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "네이티브 크래시"
            ApplicationExitInfo.REASON_ANR -> "응답 없음(ANR)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "시작 실패"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "권한 변경"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "자원 과다 사용"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "사용자/설정에서 종료"
            ApplicationExitInfo.REASON_USER_STOPPED -> "사용자 강제 중지"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "연결된 프로세스 종료"
            ApplicationExitInfo.REASON_OTHER -> "시스템 기타 사유"
            ApplicationExitInfo.REASON_FREEZER -> "시스템이 앱을 얼림"
            else -> "코드 ${info.reason}"
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(info.timestamp))
        return "지난 종료 $time · $reason\n${info.description.orEmpty().take(80)}"
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, onClick: () -> Unit) = text(value, 13f, Color.WHITE, bold = true).apply {
        setPadding(16, 14, 16, 14)
        background = GradientDrawable().apply {
            setColor(WatchWorkoutFace.CARD)
            cornerRadius = 40f
        }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
