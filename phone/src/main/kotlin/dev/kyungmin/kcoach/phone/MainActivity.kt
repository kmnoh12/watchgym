package dev.kyungmin.kcoach.phone

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.kyungmin.kcoach.phone.ui.CalibrationActions
import dev.kyungmin.kcoach.phone.ui.CalibrationButtonUi
import dev.kyungmin.kcoach.phone.ui.CalibrationProfileUi
import dev.kyungmin.kcoach.phone.ui.LiveLine
import dev.kyungmin.kcoach.phone.ui.CalibrationRowUi
import dev.kyungmin.kcoach.phone.ui.CalibrationScreen
import dev.kyungmin.kcoach.phone.ui.CalibrationUi
import dev.kyungmin.kcoach.phone.ui.MyMountainScreen
import dev.kyungmin.kcoach.phone.ui.ResultSectionUi
import dev.kyungmin.kcoach.phone.ui.ResultUi
import dev.kyungmin.kcoach.phone.ui.WorkoutResultScreen
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.kyungmin.kcoach.phone.ui.BigChoiceSheet
import dev.kyungmin.kcoach.phone.ui.ChoiceSheet
import dev.kyungmin.kcoach.phone.ui.ConfirmDialog
import dev.kyungmin.kcoach.phone.ui.RecordBannerUi
import dev.kyungmin.kcoach.phone.ui.RecordUi
import dev.kyungmin.kcoach.phone.ui.StopWorkoutDialog
import dev.kyungmin.kcoach.phone.ui.InputDialog
import dev.kyungmin.kcoach.phone.ui.ExerciseInfoScreen
import dev.kyungmin.kcoach.phone.ui.ExerciseInfoUi
import dev.kyungmin.kcoach.phone.ui.ExerciseRecordScreen
import dev.kyungmin.kcoach.phone.ui.ExerciseRecordUi
import dev.kyungmin.kcoach.phone.ui.KColors
import dev.kyungmin.kcoach.phone.ui.KIcon
import dev.kyungmin.kcoach.phone.ui.RecordSetUi
import dev.kyungmin.kcoach.phone.ui.LibraryActions
import dev.kyungmin.kcoach.phone.ui.LibraryEntry
import dev.kyungmin.kcoach.phone.ui.LibraryScreen
import dev.kyungmin.kcoach.phone.ui.Overlay
import dev.kyungmin.kcoach.phone.ui.OverlayHost
import dev.kyungmin.kcoach.phone.ui.OverlayState
import dev.kyungmin.kcoach.phone.ui.PickDialog
import dev.kyungmin.kcoach.phone.ui.SheetItem
import dev.kyungmin.kcoach.phone.ui.SheetOptions
import androidx.compose.ui.platform.ComposeView
import dev.kyungmin.kcoach.phone.ui.ActiveWorkoutScreen
import dev.kyungmin.kcoach.phone.ui.ExerciseUi
import dev.kyungmin.kcoach.core.TrainingLoad
import dev.kyungmin.kcoach.core.BodyLoad
import dev.kyungmin.kcoach.core.PersonalRecords
import dev.kyungmin.kcoach.core.SetRecordCommand
import dev.kyungmin.kcoach.core.RecordKind
import dev.kyungmin.kcoach.core.WorkoutLog
import dev.kyungmin.kcoach.core.WorkoutStats
import dev.kyungmin.kcoach.core.ExerciseHistory
import dev.kyungmin.kcoach.core.ExerciseSubstitutes
import dev.kyungmin.kcoach.core.ExerciseTaxonomy
import dev.kyungmin.kcoach.core.SetInsight
import dev.kyungmin.kcoach.core.SubstituteCandidate
import dev.kyungmin.kcoach.phone.ui.HistoryUi
import dev.kyungmin.kcoach.phone.ui.RecentLogUi
import dev.kyungmin.kcoach.phone.ui.HomeActions
import dev.kyungmin.kcoach.phone.ui.HomeUi
import dev.kyungmin.kcoach.phone.ui.RoutineRowUi2
import dev.kyungmin.kcoach.phone.ui.WeekDayUi
import dev.kyungmin.kcoach.phone.ui.HomeScreen
import dev.kyungmin.kcoach.phone.ui.Motion
import dev.kyungmin.kcoach.phone.ui.MenuActions
import dev.kyungmin.kcoach.phone.ui.MenuScreen
import dev.kyungmin.kcoach.phone.ui.RecordsActions
import dev.kyungmin.kcoach.phone.ui.RecordsScreen
import dev.kyungmin.kcoach.phone.ui.FloatingTabBar
import dev.kyungmin.kcoach.phone.ui.RoutineDetailActions
import dev.kyungmin.kcoach.phone.ui.RoutineDetailScreen
import dev.kyungmin.kcoach.phone.ui.WorkoutPreparationPanel
import dev.kyungmin.kcoach.phone.ui.RoutineRowUi
import dev.kyungmin.kcoach.phone.ui.RunningUi
import dev.kyungmin.kcoach.phone.ui.SetField
import dev.kyungmin.kcoach.phone.ui.SetUi
import dev.kyungmin.kcoach.phone.ui.StatsScreen
import dev.kyungmin.kcoach.phone.ui.SetSheet
import dev.kyungmin.kcoach.phone.ui.SheetAction
import dev.kyungmin.kcoach.phone.ui.SegGroup
import dev.kyungmin.kcoach.phone.ui.SegOption
import dev.kyungmin.kcoach.phone.ui.StatsUi
import dev.kyungmin.kcoach.phone.ui.UpdateUi
import dev.kyungmin.kcoach.phone.ui.WorkoutActions
import dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.KCoachEventMarker
import dev.kyungmin.kcoach.core.KCoachEventTrace
import dev.kyungmin.kcoach.core.CaptureState
import dev.kyungmin.kcoach.core.PlannedSetSnapshot
import dev.kyungmin.kcoach.core.WorkoutPhase
import dev.kyungmin.kcoach.core.WorkoutSnapshot
import dev.kyungmin.kcoach.core.WorkoutSessionReducer
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WirePayloadKeys
import dev.kyungmin.kcoach.core.SetCompletionEvent
import dev.kyungmin.kcoach.core.SetIdentity
import dev.kyungmin.kcoach.core.SetRepSource
import dev.kyungmin.kcoach.core.SetCompletionResult
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import dev.kyungmin.kcoach.core.ExerciseMeasurementContracts
import dev.kyungmin.kcoach.core.PersonalCalibrationBuilder
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import dev.kyungmin.kcoach.core.TrackingMode
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.time.YearMonth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private enum class RootTab { HOME, RECORDS, STATS, CALIBRATION, MENU }
    private enum class Screen { ROOT, ROUTINE, FREE, LIBRARY, EXERCISE_INFO, EXERCISE_RECORD, ACTIVE, REST, MY_MOUNTAIN }

    private var visibleWorkoutResultSessionId: String? = null
    private val handledResultRequests = mutableSetOf<String>()

    private data class WorkoutResultUi(
        val sessionId: String,
        val title: String,
        val durationMinutes: Int,
        val completedSets: Int,
        val analyses: MutableMap<String, SetAnalysisUi> = mutableMapOf(),
    )

    private data class ActiveWorkout(
        val routine: WorkoutRoutine,
        val startedAtMs: Long = System.currentTimeMillis(),
        val sessionId: String = "phone-${System.currentTimeMillis()}",
        var exerciseIndex: Int = 0,
        // Browsing during rest never moves the execution cursor.
        var restingPageIndex: Int? = null,
        var setIndex: Int = 0,
        var completedSets: Int = 0,
        val performedRepsByExercise: MutableMap<String, Int> = mutableMapOf(),
        var paused: Boolean = false,
        var pausedSinceMs: Long = 0L,
        var totalPausedMs: Long = 0L,
        var usePounds: Boolean = false,
        var selectedEditSetIndex: Int = 0,
        var scrollY: Int = 0,
        val setIndexByExercise: MutableMap<String, Int> = mutableMapOf(),
        val selectedSetIndexByExercise: MutableMap<String, Int> = mutableMapOf(),
        val scrollYByExercise: MutableMap<String, Int> = mutableMapOf(),
        val analyses: MutableMap<String, SetAnalysisUi> = mutableMapOf(),
        var phase: WorkoutPhase = WorkoutPhase.WORKING,
        var lastSequence: Long = 0L,
        var lastCompletedSetKey: String? = null,
        val captureStates: MutableMap<String, CaptureState> = mutableMapOf(),
    )

    private val bg = Color.rgb(0, 0, 0)
    private val surface = Color.rgb(14, 14, 13)
    private val surfaceRaised = Color.rgb(28, 27, 25)
    private val white = Color.rgb(237, 232, 220)
    private val muted = Color.rgb(143, 139, 130)
    private val faint = Color.rgb(92, 89, 82)
    private val amber = Color.rgb(118, 219, 185)
    private val amberDark = Color.rgb(118, 219, 185)
    private val blue = Color.rgb(163, 159, 152)
    private val green = Color.rgb(118, 219, 185)

    private val routines = WorkoutData.defaultRoutines()
    private val routineLibraryStore by lazy { RoutineLibraryStore(this) }
    private val freeExercises = mutableListOf<RoutineExercise>()
    private val handler = Handler(Looper.getMainLooper())

    private var wearLiveStatus: LiveLine? = null
    private var activeLiveStatus: LiveLine? = null
    private var restHeartStatus: TextView? = null
    private var activeRestStartAtMs = 0L
    private var activeRestEndAtMs = 0L
    private var pausedRestRemainingMs = 0L
    private var lastCompletedLiveKey: String? = null
    private var lastWearLiveSessionId = ""
    private var lastWearLiveTitle = ""
    private var lastWearLiveTelemetry = ""
    private var detailTransitionRunning = false
    private var workoutComposeView: ComposeView? = null
    private var workoutComposeWorkout: ActiveWorkout? = null
    private val workoutVersion = mutableIntStateOf(0)
    private var lastRemovedSet: Triple<RoutineExercise, Int, PlannedSet>? = null
    private var lastRestCueSecond = -1
    private val restTone by lazy { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80) }
    private val wearReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val payload = intent.getStringExtra("payload").orEmpty()
                when (intent.action) {
                PhoneWorkoutService.ACTION_SESSION_CHANGED -> {
                    if (intent.getBooleanExtra("recordsRecovered", false)) {
                        historyCache = null
                        lastWorkoutResult?.let(::hydrateResultAnalyses)
                        if (currentScreen == Screen.ROOT) when (currentTab) {
                            RootTab.HOME -> showHome()
                            RootTab.RECORDS -> if (visibleWorkoutResultSessionId == null) showRecords() else refreshVisibleWorkoutResult()
                            RootTab.STATS -> showStats()
                            else -> Unit
                        }
                    }
                    refreshActiveSession()
                }
                PhoneWearListenerService.ACTION_COMMAND_REJECTED -> {
                    activeLiveStatus?.apply {
                        text = when (intent.getStringExtra("reason")) {
                            "CLOSED_SESSION" -> "워치에서 종료된 세션이에요 · 새 운동을 시작해 주세요"
                            "STALE" -> "워치 명령 순서를 확인할 수 없어요 · 연결을 다시 확인해 주세요"
                            else -> "워치 명령을 적용하지 못했어요 · 운동 상태를 확인해 주세요"
                        }
                        setTextColor(amber)
                    }
                }
                PhoneWearListenerService.ACTION_PAIR -> {
                    updateWearConnectionStatus()
                    wearLiveStatus?.apply {
                        if (tag != WATCH_PANEL_TELEMETRY) {
                            text = "Watch8 연결됨"
                            setTextColor(green)
                        }
                    }
                }
                PhoneWearListenerService.ACTION_WATCH_UI_READY -> {
                    watchUiReady = true
                    updateWearConnectionStatus()
                    wearLiveStatus?.apply {
                        if (tag != WATCH_PANEL_TELEMETRY) {
                            text = "Watch8 연결됨 · 운동 시작 가능"
                            setTextColor(green)
                        }
                    }
                    pendingWatchStartPayload?.let { payload ->
                        pendingWatchStartPayload = null
                        sendWear(WearSync.PATH_COMMAND, payload)
                        activeLiveStatus?.text = "워치 앱 준비 완료 · 운동 시작"
                    } ?: activeWorkout?.let { workout ->
                        resyncActiveWorkoutToWatch(workout)
                    }
                }
                PhoneWearListenerService.ACTION_LIVE -> {
                    val fields = WearSync.parse(payload)
                    if (fields.getOrNull(1) == calibrationSessionId) {
                        val phase = fields.getOrNull(9).orEmpty()
                        val reps = fields.getOrNull(5)?.toIntOrNull() ?: 0
                        val liveText = when (phase) {
                            "BASELINE" -> "기준 자세 유지 중…"
                            "CONTROLLED_REPS" -> "자연스러운 왕복 $reps/5회"
                            "CALIBRATION_RESULT" -> "원본을 폰으로 전송 중…"
                            else -> null
                        }
                        if (liveText != null) {
                            calibrationButtonOverride.value = CalibrationButtonUi(liveText, calibrationButtonOverride.value?.enabled ?: true)
                        }
                    } else {
                        handleLivePayload(payload)
                    }
                }
                PhoneWearListenerService.ACTION_STATE -> {
                    val fields = WearSync.parse(payload)
                    if (
                        fields.size >= 7 &&
                        currentScreen != Screen.ACTIVE &&
                        wearLiveStatus?.tag != WATCH_PANEL_TELEMETRY
                    ) {
                        val reps = fields[4]
                        val heart = heartRateText(fields[5].toIntOrNull() ?: 0)
                        val active = fields[6].toBooleanStrictOrNull() == true
                        wearLiveStatus?.text = if (active) {
                            "Watch8 연결됨 · ${reps}회 · ${heart}"
                        } else {
                            "Watch8 연결됨 · ${heart}"
                        }
                        wearLiveStatus?.setTextColor(green)
                    }
                }
                PhoneWearListenerService.ACTION_COMPLETE -> {
                    val fields = WearSync.parse(payload)
                    val phase = fields.getOrNull(6).orEmpty()
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = if (phase == "COMPLETE") "Watch8 운동 데이터 수신 완료" else "Watch8 운동 종료"
                        setTextColor(green)
                    }
                }
                PhoneWearListenerService.ACTION_CAPTURE_MANIFEST -> {
                    runCatching { WireCodec.decode(payload) }.getOrNull()?.let { manifest ->
                        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
                        activeWorkout?.takeIf { it.sessionId == manifest.sessionId }?.let { workout ->
                            if (captureId.isNotBlank()) workout.captureStates[captureId] = CaptureState.PENDING_UPLOAD
                            persistActiveWorkout(workout)
                        }
                    }
                }
                PhoneWearListenerService.ACTION_CAPTURE_STATE -> {
                    val captureId = intent.getStringExtra("captureId").orEmpty()
                    val sessionId = intent.getStringExtra("sessionId").orEmpty()
                    val state = intent.getStringExtra("captureState")
                        ?.let { name -> CaptureState.entries.firstOrNull { it.name == name } }
                        ?: return
                    activeWorkout?.takeIf { it.sessionId == sessionId }?.let { workout ->
                        if (captureId.isNotBlank()) workout.captureStates[captureId] = state
                        persistActiveWorkout(workout)
                    }
                    if (state == CaptureState.ANALYZING) {
                        activeLiveStatus
                            ?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }
                            ?.text = "센서 원본 저장 완료 · 분석 중"
                    }
                }
                PhoneWearListenerService.ACTION_CALIBRATION_RAW_SAVED -> {
                    if (intent.getStringExtra("sessionId").orEmpty() == calibrationSessionId) {
                        calibrationButtonOverride.value = CalibrationButtonUi("원본 저장 완료 · 보정 설정 만드는 중…", false)
                    }
                }
                PhoneWearListenerService.ACTION_CALIBRATION_RESULT -> {
                    val sessionId = intent.getStringExtra("sessionId").orEmpty()
                    if (sessionId != calibrationSessionId) return
                    cancelCalibrationSaveTimeout()
                    val succeeded = intent.getBooleanExtra("succeeded", false)
                    val validReps = intent.getIntExtra("validReps", 0)
                    if (succeeded) {
                        Toast.makeText(
                            this@MainActivity,
                            "보정 설정을 저장했어요 · 감지 ${validReps}회",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, "유효 반복이 ${validReps}회라 프로필을 만들지 않았어요 · 다시 보정해 주세요", Toast.LENGTH_LONG).show()
                    }
                    calibrationSessionId = null
                    activeCalibrationExerciseId = null
                    calibrationStopRequested = false
                    if (currentTab == RootTab.CALIBRATION) showCalibration()
                }
                PhoneWearListenerService.ACTION_CAPTURE -> {
                    historyCache = null
                    refreshActiveSession()
                    val captureId = intent.getStringExtra("captureId").orEmpty()
                    val sessionId = intent.getStringExtra("sessionId").orEmpty()
                    val exerciseId = intent.getStringExtra("exerciseId").orEmpty()
                    val exerciseName = intent.getStringExtra("exerciseName").orEmpty().ifBlank { "운동" }
                    val setIndex = intent.getIntExtra("setIndex", 0)
                    if (calibrationSessionId == sessionId) {
                        val calibratedName = WorkoutData.catalog
                            .firstOrNull { it.id == activeCalibrationExerciseId }
                            ?.name ?: exerciseName
                        calibrationSessionId = null
                        activeCalibrationExerciseId = null
                        calibrationStopRequested = false
                        Toast.makeText(this@MainActivity, "$calibratedName 보정 기록을 저장했어요", Toast.LENGTH_LONG).show()
                        if (currentTab == RootTab.CALIBRATION) showCalibration()
                        return@onReceive
                    }
                    val analysis = SetAnalysisStore(this@MainActivity).loadForSession(sessionId).values.firstOrNull { it.captureId == captureId } ?: return@onReceive
                    activeWorkout?.takeIf { it.sessionId == sessionId }?.let { workout ->
                        workout.analyses[analysis.key] = analysis
                        if (
                            captureId.isNotBlank() &&
                            workout.captureStates[captureId] != CaptureState.ACKED
                        ) {
                            workout.captureStates[captureId] = CaptureState.RECEIVED
                        }
                        persistActiveWorkout(workout)
                    }
                    lastWorkoutResult?.takeIf { it.sessionId == sessionId }?.analyses?.set(analysis.key, analysis)
                    lastWorkoutResult?.takeIf { it.sessionId == sessionId }?.let(::persistLastWorkoutResult)
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = "세트 분석 완료 · 자동 추정 ${measuredRepsText(analysis.confirmedReps, "확인 필요")} · 실제 횟수를 확인해 주세요"
                        setTextColor(green)
                    }
                    if (visibleWorkoutResultSessionId == sessionId) refreshVisibleWorkoutResult()
                }
                PhoneWearListenerService.ACTION_CAPTURE_ERROR -> {
                    val captureId = intent.getStringExtra("captureId").orEmpty()
                    val stage = intent.getStringExtra("stage").orEmpty()
                    if (intent.getStringExtra("sessionId").orEmpty() == calibrationSessionId) {
                        cancelCalibrationSaveTimeout()
                        calibrationStopRequested = false
                        calibrationButtonOverride.value = CalibrationButtonUi("보정 종료·저장", true)
                    }
                    activeWorkout?.let { workout ->
                        if (captureId.isNotBlank()) workout.captureStates[captureId] = CaptureState.ANALYSIS_FAILED
                        persistActiveWorkout(workout)
                    }
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = if (stage == "analysis") {
                            "센서 원본 저장 완료 · 분석만 실패"
                        } else {
                            "센서 원본 검증 실패 · 재전송 대기"
                        }
                        setTextColor(amber)
                    }
                }
                }
            } catch (error: Throwable) {
                Log.e(EVENT_TAG, "wear UI event failed action=${intent.action}", error)
                getSharedPreferences("wear-sync", MODE_PRIVATE).edit()
                    .putString("lastUiError", "${intent.action}:${error.message}")
                    .putLong("lastUiErrorAt", System.currentTimeMillis())
                    .apply()
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@MainActivity, "워치 데이터 표시에 오류가 있었지만 운동 기록은 그대로 두었어요", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private lateinit var root: FrameLayout
    private val overlays = OverlayState()
    private val historyStore by lazy { ExerciseHistoryStore(applicationContext) }
    private var historyCache: ExerciseHistory? = null
    private val history: ExerciseHistory get() = historyCache ?: historyStore.load().also { historyCache = it }
    private lateinit var otaCoordinator: dev.kyungmin.kcoach.updates.OtaCoordinator
    private lateinit var sessionStore: SessionStore
    private var currentTab = RootTab.HOME
    private var currentScreen = Screen.ROOT
        set(value) {
            field = value
            LocalExitDiagnostics.state(value.name, activeWorkout?.phase?.name ?: "IDLE")
        }
    private var selectedRoutine: WorkoutRoutine? = null
    private var activeWorkout: ActiveWorkout? = null
    private var previewWorkout: ActiveWorkout? = null
    private var lastWorkoutResult: WorkoutResultUi? = null
    private var recordMonth: YearMonth = YearMonth.now()
    private var ticker: Runnable? = null
    private var watchUiReady = false
    private var defaultWorkoutWearing by mutableStateOf<WorkoutWearingSelection?>(null)
    private var workoutWearingReadFailed by mutableStateOf(false)
    private var preparationConnection by mutableStateOf("워치 연결 확인 중")
    private var pendingWatchStartPayload: String? = null
    private var calibrationSessionId: String? = null
    private var activeCalibrationExerciseId: String? = null
    private var selectedCalibrationExerciseId = "external_rotation"
    private var calibrationStopRequested = false
    private val calibrationButtonOverride = mutableStateOf<CalibrationButtonUi?>(null)
    private var calibrationSaveTimeoutTask: Runnable? = null

    /** Set when this start follows an update; shown once at the top of the menu, then cleared. */
    private var pendingUpdateNotice: dev.kyungmin.kcoach.updates.OtaNotice? = null
    private var returnFromLibrary: (() -> Unit)? = null
    private var returnFromExerciseInfo: (() -> Unit)? = null
    private var returnFromExerciseRecord: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val createdAtMs = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        // Give the first start after an update a longer icon splash, then show the completion notice in the menu.
        // OEM update overlays have their own timing; this does not guarantee that they expose our splash.
        val startupUpdates = dev.kyungmin.kcoach.updates.OtaCoordinator.get(this)
        startupUpdates.recover()
        pendingUpdateNotice = startupUpdates.store.let { store ->
            store.notice()?.takeIf { System.currentTimeMillis() - it.atMs < UPDATE_NOTICE_MAX_AGE_MS }.also { if (it == null) store.clearNotice() }
        }
        val splashMinMs = if (pendingUpdateNotice != null) SPLASH_AFTER_UPDATE_MS else SPLASH_MIN_MS
        // Cold start only: keep the system splash (icon on the app background) up for a beat so the
        // home screen never just pops in; the listener does not fire on a resume.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                val wait = (splashMinMs - (android.os.SystemClock.elapsedRealtime() - createdAtMs)).coerceAtLeast(0L)
                splash.postDelayed({ splash.remove() }, wait)
            }
        }
        val resetHome = WorkoutTermination(this).consumeHomeReset()
        val restorationState = savedInstanceState.takeUnless { resetHome }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        otaCoordinator = dev.kyungmin.kcoach.updates.OtaCoordinator.get(this)
        sessionStore = SessionStore(this)
        restoreRoutineLibrary()
        lastWorkoutResult = restoreLastWorkoutResult()
        activeWorkout = restoreActiveWorkout()
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Android 15+ draws edge-to-edge; keep every screen clear of both bars here
            // and consume the insets so nested Compose content doesn't pad twice.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        addContentView(
            tabBarView(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        addContentView(
            ComposeView(this).apply { setContent { OverlayHost(overlays) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        startService(Intent(this, PhoneWearListenerService::class.java))
        startService(Intent(this, PhoneWorkoutService::class.java))
        handledResultRequests.addAll(restorationState?.getStringArrayList(STATE_HANDLED_RESULT_REQUESTS).orEmpty())
        val requestKey = WorkoutResultIntent.requestKey(intent)
        val replayedRequest = requestKey != null && requestKey in handledResultRequests
        val requestedResultShown = !resetHome && !replayedRequest && showRequestedWorkoutResult(intent)
        val restoredResult = if (requestedResultShown) null else restorationState?.getString(STATE_VISIBLE_RESULT)
            ?.let { loadFinishedWorkoutResult(it) ?: lastWorkoutResult?.takeIf { saved -> saved.sessionId == it } }
        if (restoredResult != null) showWorkoutResult(restoredResult)
        val resultShown = requestedResultShown || restoredResult != null
        selectedCalibrationExerciseId = restorationState?.getString(STATE_CALIBRATION_EXERCISE)
            ?: selectedCalibrationExerciseId
        val restoredTab = if (pendingUpdateNotice != null && activeWorkout == null) RootTab.MENU else
            restorationState?.getString(STATE_ROOT_TAB)
                ?.let { saved -> RootTab.values().firstOrNull { it.name == saved } }
        if (!resultShown && restoredTab != null) {
            when (restoredTab) {
                RootTab.HOME -> showHome()
                RootTab.RECORDS -> showRecords()
                RootTab.STATS -> showStats()
                RootTab.CALIBRATION -> showCalibration()
                RootTab.MENU -> showMenu()
            }
        }
        val screenRestored = resultShown || restoredTab != null
        activeWorkout?.let { workout ->
            lastCompletedLiveKey = workout.lastCompletedSetKey
            if (!screenRestored) {
                if (workout.exerciseIndex >= workout.routine.exercises.size) showMyMountainDecision()
                else showActiveWorkout(forceRebuild = true)
            }
            sendWear(
                WearSync.PATH_COMMAND,
                WearSync.command("QUERY", workout.sessionId, "", workout.setIndex, 0),
            )
        } ?: run { if (!screenRestored) showHome() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showRequestedWorkoutResult(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_HANDLED_RESULT_REQUESTS, ArrayList(handledResultRequests))
        if (currentScreen == Screen.ROOT) outState.putString(STATE_ROOT_TAB, currentTab.name)
        outState.putString(STATE_CALIBRATION_EXERCISE, selectedCalibrationExerciseId)
        if (currentScreen == Screen.ROOT && currentTab == RootTab.RECORDS) {
            visibleWorkoutResultSessionId?.let { outState.putString(STATE_VISIBLE_RESULT, it) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            wearReceiver,
            IntentFilter().apply {
                addAction(PhoneWearListenerService.ACTION_PAIR)
                addAction(PhoneWearListenerService.ACTION_COMMAND_REJECTED)
                addAction(PhoneWorkoutService.ACTION_SESSION_CHANGED)
                addAction(PhoneWearListenerService.ACTION_WATCH_UI_READY)
                addAction(PhoneWearListenerService.ACTION_STATE)
                addAction(PhoneWearListenerService.ACTION_LIVE)
                addAction(PhoneWearListenerService.ACTION_COMPLETE)
                addAction(PhoneWearListenerService.ACTION_CAPTURE_MANIFEST)
                addAction(PhoneWearListenerService.ACTION_CAPTURE_STATE)
                addAction(PhoneWearListenerService.ACTION_CALIBRATION_RAW_SAVED)
                addAction(PhoneWearListenerService.ACTION_CALIBRATION_RESULT)
                addAction(PhoneWearListenerService.ACTION_CAPTURE)
                addAction(PhoneWearListenerService.ACTION_CAPTURE_ERROR)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        historyCache = null
        val defaults = WorkoutWearingStore(this).defaultSelection()
        val answers = WorkoutWearingStore(this).preparationAnswers()
        defaultWorkoutWearing = defaults.getOrNull()
        workoutWearingReadFailed = defaults.isFailure || answers.isFailure
        lastWorkoutResult?.let { result ->
            hydrateResultAnalyses(result)
            persistLastWorkoutResult(result)
        }
        refreshVisibleWorkoutResult()
        refreshActiveSession()
        updateWearConnectionStatus()
        WatchOverviewPublisher.publish(this)
    }

    override fun onStop() {
        stopTicker()
        unregisterReceiver(wearReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        if (::otaCoordinator.isInitialized) otaCoordinator.detach(this)
        calibrationSaveTimeoutTask?.let(handler::removeCallbacks)
        calibrationSaveTimeoutTask = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        otaCoordinator.attach(this)
    }

    override fun onPause() {
        otaCoordinator.detach(this)
        super.onPause()
    }

    @Deprecated("Screen stack is managed manually; Compose BackHandlers still get first say.")
    override fun onBackPressed() {
        // Let Compose close the keypad or rest panel before leaving the screen.
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        when (currentScreen) {
            Screen.ROOT -> {
                if (currentTab != RootTab.HOME) showHome()
                // Backing out of the last screen would finish the activity while the workout keeps running.
                else if (activeWorkout != null) confirmStopWorkout()
                else super.onBackPressed()
            }
            Screen.ROUTINE, Screen.FREE -> showHome()
            Screen.LIBRARY -> returnFromLibrary?.invoke() ?: showHome()
            Screen.EXERCISE_INFO -> returnFromExerciseInfo?.invoke() ?: showHome()
            Screen.EXERCISE_RECORD -> returnFromExerciseRecord?.invoke() ?: showHome()
            Screen.ACTIVE, Screen.REST -> showHome()
            Screen.MY_MOUNTAIN -> showRecords()
        }
    }

    private val workoutLogStore by lazy { WorkoutLogStore(applicationContext) }

    private fun workoutRecords(): List<WorkoutRecord> =
        workoutLogStore.load().sortedByDescending { it.epochDay }.map {
            WorkoutRecord(LocalDate.ofEpochDay(it.epochDay).toString(), it.title, it.minutes, it.sets, it.volumeKg)
        }

    private fun weekRecords(today: LocalDate): List<WorkoutRecord> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return workoutRecords().filter {
            val date = LocalDate.parse(it.date)
            !date.isBefore(monday) && !date.isAfter(monday.plusDays(6))
        }
    }

    private fun weekStrip(today: LocalDate): List<WeekDayUi> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val byDate = weekRecords(today).groupBy { LocalDate.parse(it.date) }
        return listOf("월", "화", "수", "목", "금", "토", "일").mapIndexed { index, label ->
            val date = monday.plusDays(index.toLong())
            val load = byDate[date]?.sumOf { TrainingLoad.score(it.minutes, it.sets) }?.coerceAtMost(TrainingLoad.MAX_SCORE)
            WeekDayUi(label, load, date == today, date.isAfter(today))
        }
    }

    private fun showHome() {
        stopTicker()
        if (activeWorkout == null) previewWorkout = null
        currentTab = RootTab.HOME
        currentScreen = Screen.ROOT

        val running = activeWorkout
        val runningUi = running?.let { workout ->
            val current = workout.routine.exercises.getOrNull(workout.exerciseIndex)
            val detail = current?.let { "${it.exercise.name} · ${it.sets.completedWorkSetCount()}/${it.sets.workSetCount()}세트" }.orEmpty()
            RunningUi(workout.routine.name, detail, workout.paused) { workoutElapsedSeconds(workout) }
        }
        val today = LocalDate.now()
        routines.forEach { it.lastPerformed = it.lastPerformedLabel(today) }
        val rows = routines.map { RoutineRowUi2(it, TrainingLoad.daysAgo(it.lastPerformed)) }
        val homeUi = HomeUi(
            week = weekStrip(LocalDate.now()),
            weekCount = weekRecords(LocalDate.now()).size,
            rows = rows,
            longestRest = rows.filter { it.daysAgo != null }.maxByOrNull { it.daysAgo!! },
        )
        val actions = HomeActions(
            openRoutine = { routine ->
                selectedRoutine = routine
                showRoutine(routine, active = activeWorkout?.takeIf { it.routine === routine }, animateIn = true)
            },
            routineMenu = ::showRoutineMenu,
            freeWorkout = ::showFreeWorkout,
            add = ::showAddSheet,
            openRunning = { showActiveWorkout() },
            togglePause = {
                running?.let(::toggleWorkoutPause)
                showHome()
            },
            stop = ::confirmStopWorkout,
        )
        val home = ComposeView(this).apply {
            setContent { HomeScreen(homeUi, runningUi, actions) }
        }
        showScaffold(home, null, RootTab.HOME)
    }

    private fun showRoutine(
        routine: WorkoutRoutine,
        reorderMode: Boolean = false,
        active: ActiveWorkout? = null,
        onExit: (() -> Unit)? = null,
        animateIn: Boolean = false,
    ) {
        if (active == null && activeWorkout == null) {
            prefillProgression(routine)
        }
        stopTicker()
        currentScreen = Screen.ROUTINE
        selectedRoutine = routine
        updateWearConnectionStatus()
        if (active == null && activeWorkout == null) prepareRoutineForWatch(routine)

        val reload = { showRoutine(routine, reorderMode, active, onExit) }
        val rows = routine.exercises.mapIndexed { index, item ->
            RoutineRowUi(
                key = index,
                name = item.exercise.name,
                subtitle = "${item.exercise.muscles} · ${item.sets.workSetCount()}세트 · 총 ${item.sets.sumOf { it.targetReps }}회",
                thumbnail = { exerciseAnimationView(item.exercise, 58, compact = true) },
                doneSets = active?.takeIf { it.routine === routine }?.let { item.sets.completedWorkSetCount() },
                plannedSets = item.sets.workSetCount(),
            )
        }
        val actions = RoutineDetailActions(
            back = { onExit?.invoke() ?: onBackPressed() },
            open = { index ->
                if (active != null && active.routine === routine) showWorkoutExercise(active, index)
                else showExercisePreview(routine, index)
            },
            info = { index -> showExerciseInfo(routine.exercises[index].exercise, reload) },
            menu = { index -> showExerciseMenu(routine, index, active, reload) },
            move = { from, to -> moveRoutineExercise(routine, from, to, active, announce = false) },
            moveFinished = {
                active?.takeIf { it.routine === routine }?.let(::persistActiveWorkout)
                if (routines.any { it === routine }) saveRoutineLibrary()
                Toast.makeText(this, "순서를 바꿨어요", Toast.LENGTH_SHORT).show()
            },
            primary = { if (active != null) showMyMountainDecision() else startWorkout(routine) },
            addExercise = {
                openLibrary(
                    onSelect = {
                        routine.exercises += RoutineExercise(
                            exercise = it,
                            sets = mutableListOf(PlannedSet(null, 10)),
                        )
                        if (routines.any { saved -> saved === routine }) saveRoutineLibrary()
                    },
                    onReturn = { showRoutine(routine) },
                )
            },
        )
        val body = ComposeView(this).apply {
            setContent {
                RoutineDetailScreen(
                    title = routine.name,
                    rows = rows,
                    primaryLabel = if (active != null) "운동 완료" else "시작",
                    primaryIcon = if (active != null) KIcon.Flag else KIcon.Play,
                    showHandle = reorderMode,
                    actions = actions,
                    preparationContent = if (active == null) ({ RoutinePreparation(routine) }) else null,
                )
            }
        }
        showDetail(body, null, animateFromRight = animateIn)
    }

    private fun freeRoutine() = WorkoutRoutine("free", "자유운동", "선택 운동", "오늘", freeExercises)

    private fun showFreeWorkout() {
        stopTicker()
        currentScreen = Screen.FREE
        updateWearConnectionStatus()
        val reload = { showFreeWorkout() }
        val rows = freeExercises.mapIndexed { index, item ->
            RoutineRowUi(
                key = index,
                name = item.exercise.name,
                subtitle = "${item.exercise.muscles} · ${item.sets.workSetCount()}세트 · 총 ${item.sets.sumOf { it.targetReps }}회",
                thumbnail = { exerciseAnimationView(item.exercise, 58, compact = true) },
            )
        }
        val actions = RoutineDetailActions(
            back = { onBackPressed() },
            open = { index -> showExerciseRecord(freeRoutine(), index, null, reload) },
            info = { index -> showExerciseInfo(freeExercises[index].exercise, reload) },
            menu = { index -> showExerciseMenu(freeRoutine(), index, null, reload) },
            move = { from, to -> freeExercises.add(to, freeExercises.removeAt(from)) },
            moveFinished = { Toast.makeText(this, "순서를 바꿨어요", Toast.LENGTH_SHORT).show() },
            primary = { startWorkout(freeRoutine()) },
            addExercise = {
                openLibrary(
                    onSelect = {
                        if (freeExercises.none { row -> row.exercise.id == it.id }) {
                            freeExercises += RoutineExercise(it, mutableListOf(PlannedSet(null, 10)))
                        }
                    },
                    onReturn = reload,
                )
            },
        )
        val body = ComposeView(this).apply {
            setContent {
                RoutineDetailScreen(
                    title = "7월 27일, 밤 운동",
                    rows = rows,
                    primaryLabel = if (freeExercises.isNotEmpty()) "시작" else null,
                    primaryIcon = KIcon.Play,
                    showHandle = false,
                    actions = actions,
                    preparationContent = { RoutinePreparation(freeRoutine()) },
                    addFilled = freeExercises.isEmpty(),
                )
            }
        }
        showDetail(body, null)
    }

    private fun openLibrary(onSelect: (ExerciseDefinition) -> Unit, onReturn: () -> Unit) {
        stopTicker()
        currentScreen = Screen.LIBRARY
        returnFromLibrary = onReturn
        val actions = LibraryActions(
            back = onReturn,
            custom = { showCustomExerciseDialog(onSelect, onReturn) },
            add = {
                onSelect(it)
                Toast.makeText(this, "${it.name} 추가됨", Toast.LENGTH_SHORT).show()
            },
        )
        val frequent = setOf(
            "pull_up", "lat_pull_down", "flat_bench", "incline_bench",
            "dumbbell_lateral_raise", "leg_press", "cable_pushdown",
        )
        showStandalone(ComposeView(this).apply {
            setContent {
                val entries = WorkoutData.catalog.map { item ->
                    val (order, title) = ExerciseTaxonomy.section(item.bodyCategory, item.pattern)
                    LibraryEntry(item, order, title, ExerciseTaxonomy.bodyRank(item.bodyCategory))
                }
                LibraryScreen(entries, frequent, actions) { exerciseAnimationView(it, 56, compact = true) }
            }
        })
    }

    private fun persistLastWorkoutResult(result: WorkoutResultUi) {
        val encode = { value: String -> Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
        val analyses = result.analyses.values.joinToString(";") { analysis ->
            listOf(
                analysis.exerciseId,
                analysis.exerciseName,
                analysis.setIndex.toString(),
                analysis.watchReps?.toString() ?: "",
                analysis.phoneReps?.toString() ?: "",
                analysis.confirmedReps?.toString() ?: "",
                analysis.confidence.toString(),
                analysis.trackingMode,
                analysis.trackingProfileId,
                analysis.trackingStatus,
                analysis.capabilityTier,
                analysis.repConfidence?.toString() ?: "",
                analysis.wristMotionSpeed?.toString() ?: "",
                analysis.wristMotionRange?.toString() ?: "",
                analysis.tempoConsistency?.toString() ?: "",
                analysis.signalQuality?.toString() ?: "",
                analysis.averageSpeed?.toString() ?: "",
                analysis.relativeRom?.toString() ?: "",
                analysis.lateDrift?.toString() ?: "",
                analysis.setId,
                analysis.attemptId,
                analysis.captureId,
                analysis.motionMetrics?.let(dev.kyungmin.kcoach.core.ObservedMotionMetricsCodec::encode) ?: "",
            ).joinToString(",", transform = encode)
        }
        getSharedPreferences("workout_results", MODE_PRIVATE).edit()
            .putString("last", listOf(encode(result.sessionId), encode(result.title), result.durationMinutes, result.completedSets, analyses).joinToString("#"))
            .apply()
    }

    private fun restoreLastWorkoutResult(): WorkoutResultUi? {
        val raw = getSharedPreferences("workout_results", MODE_PRIVATE).getString("last", null) ?: return null
        return runCatching {
            val fields = raw.split("#", limit = 5)
            require(fields.size == 5)
            val decode = { value: String -> String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8) }
            val analyses = mutableMapOf<String, SetAnalysisUi>()
            fields[4].takeIf { it.isNotBlank() }?.split(';')?.forEach { encoded ->
                val values = encoded.split(',', limit = 40)
                if (values.size == 7 || values.size == 15 || values.size == 18 || values.size == 19 || values.size == 21 || values.size == 22 || values.size == 23 || values.size == 24 || values.size >= 30) {
                    val currentFormat = values.size == 18 || values.size == 19 || values.size == 21 || values.size == 22 || values.size == 23
                    val legacyExpanded = values.size >= 30
                    val tempoIndex = when {
                        currentFormat -> 14
                        legacyExpanded -> 21
                        else -> 20
                    }
                    val signalIndex = when {
                        currentFormat -> 15
                        legacyExpanded -> 22
                        else -> 21
                    }
                    val speedIndex = when {
                        currentFormat -> 16
                        legacyExpanded -> 25
                        else -> 22
                    }
                    val rangeIndex = when {
                        currentFormat -> 17
                        legacyExpanded -> 26
                        else -> 23
                    }
                    val analysis = SetAnalysisUi(
                        exerciseId = decode(values[0]),
                        exerciseName = decode(values[1]),
                        setIndex = decode(values[2]).toInt(),
                        watchReps = decode(values[3]).toIntOrNull()?.takeIf { it >= 0 },
                        phoneReps = decode(values[4]).toIntOrNull()?.takeIf { it >= 0 },
                        confirmedReps = decode(values[5]).toIntOrNull()?.takeIf { it >= 0 },
                        confidence = decode(values[6]).toDouble(),
                        trackingMode = values.getOrNull(7)?.let(decode).orEmpty().ifBlank { "WRIST_IMU_CAUTION" },
                        trackingProfileId = values.getOrNull(8)?.let(decode).orEmpty().ifBlank { "generic-wrist" },
                        trackingStatus = values.getOrNull(9)?.let(decode).orEmpty(),
                        capabilityTier = values.getOrNull(10)?.let(decode).orEmpty().ifBlank { "C_PERSONALIZED_AUTO_COUNT_BETA" },
                        repConfidence = values.getOrNull(11)?.let(decode)?.toDoubleOrNull(),
                        wristMotionSpeed = values.getOrNull(12)?.let(decode)?.toDoubleOrNull(),
                        wristMotionRange = values.getOrNull(13)?.let(decode)?.toDoubleOrNull(),
                        tempoConsistency = values.getOrNull(tempoIndex)?.let(decode)?.toIntOrNull(),
                        signalQuality = values.getOrNull(signalIndex)?.let(decode)?.toDoubleOrNull(),
                        averageSpeed = values.getOrNull(speedIndex)?.let(decode)?.toDoubleOrNull(),
                        relativeRom = values.getOrNull(rangeIndex)?.let(decode)?.toDoubleOrNull(),
                        lateDrift = if (values.size == 19 || values.size == 21 || values.size == 22 || values.size == 23) values[18].let(decode).toDoubleOrNull() else null,
                        setId = if (values.size == 21 || values.size == 22 || values.size == 23) values[19].let(decode) else "",
                        attemptId = if (values.size == 21 || values.size == 22 || values.size == 23) values[20].let(decode) else "",
                        captureId = if (values.size == 22 || values.size == 23) values[21].let(decode) else "",
                        motionMetrics = if (values.size == 23) values[22].let(decode).takeIf { it.isNotBlank() }?.let { runCatching { dev.kyungmin.kcoach.core.ObservedMotionMetricsCodec.decode(it) }.getOrNull() } else null,
                    )
                    analyses[analysis.key] = analysis
                }
            }
            WorkoutResultUi(
                sessionId = decode(fields[0]),
                title = decode(fields[1]),
                durationMinutes = fields[2].toInt(),
                completedSets = fields[3].toInt(),
                analyses = SetAnalysisStore(this@MainActivity).matchingSaved(decode(fields[0]), analyses.values).toMutableMap().apply { putAll(SetAnalysisStore(this@MainActivity).loadForSession(decode(fields[0]))) },
            )
        }.getOrNull()
    }

    private fun showRecords() {
        visibleWorkoutResultSessionId = null
        stopTicker()
        currentTab = RootTab.RECORDS
        currentScreen = Screen.ROOT
        val actions = RecordsActions(
            openResult = { showWorkoutResult(lastWorkoutResult) },
            monthChanged = { recordMonth = it },
        )
        val title = lastWorkoutResult?.title
        showScaffold(ComposeView(this).apply {
            setContent { RecordsScreen(workoutRecords(), recordMonth, title, actions) }
        }, null, RootTab.RECORDS)
    }

    private fun hydrateResultAnalyses(result: WorkoutResultUi) {
        val store = SetAnalysisStore(this)
        val matching = store.matchingSaved(result.sessionId, result.analyses.values)
        result.analyses.clear()
        result.analyses.putAll(matching)
        result.analyses.putAll(store.loadForSession(result.sessionId))
    }

    private fun loadFinishedWorkoutResult(sessionId: String): WorkoutResultUi? {
        val saved = FinishedWorkoutStore(this).evidenceForSessions(setOf(sessionId)).singleOrNull() ?: return null
        return WorkoutResultUi(sessionId, saved.title, saved.minutes, saved.snapshot.plannedSets.count { it.completed },
            SetAnalysisStore(this).loadForSession(sessionId).toMutableMap())
    }

    private fun showRequestedWorkoutResult(request: Intent?): Boolean {
        if (request == null || !WorkoutResultIntent.isRequest(request)) return false
        WorkoutResultIntent.requestKey(request)?.let(handledResultRequests::add)
        // Consume this navigation request so leaving the result does not reopen it on recreation.
        setIntent(Intent(request).apply { action = Intent.ACTION_MAIN; data = null; removeExtra(WorkoutResultIntent.EXTRA_SESSION_ID) })
        val id = WorkoutResultIntent.sessionId(request)
        val result = id?.let { runCatching { loadFinishedWorkoutResult(it) }.getOrNull() }
        if (result == null) {
            Toast.makeText(this, "완료된 운동 결과를 찾지 못했어요. 운동 기록을 확인해 주세요.", Toast.LENGTH_LONG).show()
            return false
        }
        overlays.dismiss()
        showWorkoutResult(result)
        return true
    }

    private fun refreshVisibleWorkoutResult() {
        if (currentScreen != Screen.ROOT || currentTab != RootTab.RECORDS) return
        val id = visibleWorkoutResultSessionId ?: return
        val result = loadFinishedWorkoutResult(id) ?: lastWorkoutResult?.takeIf { it.sessionId == id }
        if (result != null) showWorkoutResult(result)
    }

    private fun showWorkoutResult(result: WorkoutResultUi?) {
        if (result == null) return showRecords()
        hydrateResultAnalyses(result)
        if (lastWorkoutResult?.sessionId == result.sessionId) {
            lastWorkoutResult = result
            val store = FinishedWorkoutStore(this)
            val pounds = store.usePounds(result.sessionId)
            lastWorkoutRecords = store.recordHits(result.sessionId).map { (name, hit) ->
                recordDisplay(name, hit, pounds)
            }
        }
        if (lastWorkoutResult?.sessionId == result.sessionId) persistLastWorkoutResult(result)
        stopTicker()
        currentTab = RootTab.RECORDS
        currentScreen = Screen.ROOT
        val sections = if (result.analyses.isEmpty()) {
            listOf(ResultSectionUi("분석 대기", listOf("센서 기록" to "워치에서 받는 중", "자동 추정 횟수" to "센서 기록을 받으면 표시돼요")))
        } else {
            result.analyses.values.sortedWith(compareBy<SetAnalysisUi> { it.exerciseName }.thenBy { it.setIndex }).map { analysis ->
                ResultSectionUi("${analysis.exerciseName} · 세트 ${analysis.setIndex + 1}", analysis.resultMetricRows(),
                    analysis.motionMetrics?.reps?.map { it.durationMs / 1000.0 }.orEmpty())
            }
        }
        val ui = ResultUi(
            title = result.title,
            subtitle = "${result.durationMinutes}분 · ${result.completedSets}세트 · 세션 ${result.sessionId.takeLast(8)}",
            sections = sections,
            records = if (lastWorkoutResult?.sessionId == result.sessionId) lastWorkoutRecords else emptyList(),
            footnote = ANALYSIS_ESTIMATE_NOTE,
        )
        showStandalone(ComposeView(this).apply {
            setContent { WorkoutResultScreen(ui, back = { onBackPressed() }, toRecords = { showRecords() },
                summary = { dev.kyungmin.kcoach.phone.ui.WorkoutSummaryPanel(result.sessionId) }) }
        })
        visibleWorkoutResultSessionId = result.sessionId
    }

    private fun showStats() {
        stopTicker()
        currentTab = RootTab.STATS
        currentScreen = Screen.ROOT
        showScaffold(ComposeView(this).apply { val stats = statsUi()
            setContent { StatsScreen(stats) } }, null, RootTab.STATS)
    }

    private fun startOrStopCalibration() {
        val session = calibrationSessionId
        if (session != null) {
            if (calibrationStopRequested) return
            calibrationStopRequested = true
            calibrationButtonOverride.value = CalibrationButtonUi("보정 원본 저장 중…", false)
            sendWear(WearSync.PATH_COMMAND, WearSync.command("STOP", session, "", 0, 0))
            Toast.makeText(this, "워치 센서 기록을 저장하는 중이에요", Toast.LENGTH_LONG).show()
            scheduleCalibrationSaveTimeout(session)
            return
        }

        if (activeWorkout != null || sessionStore.loadActive().getOrNull() != null) {
            Toast.makeText(this, "진행 중인 운동을 종료한 뒤 함께 촬영해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val selected = calibrationExercises().firstOrNull { it.id == selectedCalibrationExerciseId } ?: return
        startActivity(Intent(this, CalibrationCameraActivity::class.java).putExtra("exerciseId", selected.id).putExtra("exerciseName", selected.name))
    }

    private fun scheduleCalibrationSaveTimeout(sessionId: String) {
        cancelCalibrationSaveTimeout()
        val timeout = Runnable {
            if (calibrationSessionId != sessionId || !calibrationStopRequested) return@Runnable
            calibrationSessionId = null
            activeCalibrationExerciseId = null
            calibrationStopRequested = false
            calibrationButtonOverride.value = CalibrationButtonUi("보정 전송 지연 · 다시 시작", true, textAmber = true)
            Toast.makeText(this, "20초 동안 워치 기록을 받지 못했어요 · 연결을 확인하고 다시 보정해 주세요", Toast.LENGTH_LONG).show()
        }
        calibrationSaveTimeoutTask = timeout
        handler.postDelayed(timeout, CALIBRATION_SAVE_TIMEOUT_MS)
    }

    private fun cancelCalibrationSaveTimeout() {
        calibrationSaveTimeoutTask?.let(handler::removeCallbacks)
        calibrationSaveTimeoutTask = null
    }

    private fun calibrationExercises(): List<ExerciseDefinition> {
        val additional = listOf(
            ExerciseDefinition("squat", "스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "맨몸"),
            ExerciseDefinition("barbell_squat", "백 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "바벨"),
            ExerciseDefinition("front_squat", "프런트 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "바벨"),
            ExerciseDefinition("goblet_squat", "고블릿 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "덤벨"),
            ExerciseDefinition("smith_squat", "스미스 스쿼트 · 전신 촬영", "대퇴, 둔근", "하체", "머신"))
        return WorkoutData.catalog + additional.filter { extra -> WorkoutData.catalog.none { it.id == extra.id } }
    }

    private fun showCalibration() {
        stopTicker()
        currentTab = RootTab.CALIBRATION
        currentScreen = Screen.ROOT
        val selectable = calibrationExercises()
        val selected = selectable.firstOrNull { it.id == selectedCalibrationExerciseId } ?: selectable.first()
        val selectedProfile = ExerciseTrackingProfiles.resolve(selected.id, selected.name)
        val manualRequired = selectedProfile.mode == TrackingMode.MANUAL_REQUIRED
        val calibrationPrefs = getSharedPreferences("wear-sync", MODE_PRIVATE)
        fun personalProfile(exerciseId: String) = calibrationPrefs
            .getString("personalProfile:$exerciseId", null)
            ?.let { runCatching { PersonalCalibrationProfileCodec.decode(it) }.getOrNull() }
        fun calibrationLabel(exerciseId: String): String {
            val profile = personalProfile(exerciseId)
            if (profile != null) return "최근 보정 저장됨 · 감지 ${profile.validRepCount}회"
            return when (calibrationPrefs.getString("calibration:$exerciseId:status", null)) {
                "CALIBRATION_FAILED" -> "보정 실패 · 다시 수행 필요"
                "RAW_SAVED" -> "원본 저장 완료 · 보정 설정 만드는 중"
                else -> "자동 인식 후보 · 프로필 미보정"
            }
        }
        val instructions = "1. 운동을 고르고 설정에 저장한 워치 착용 정보 확인\n" +
            "2. 폰을 고정하고 필요한 관절이 보이게 촬영 (스쿼트는 머리부터 발까지 전신)\n" +
            "3. 안내 자세로 촬영을 시작하고 평소 동작으로 10회 수행해 주세요\n" +
            "4. 시작 자세로 돌아와 3초 멈추면 자동으로 종료해요\n" +
            "5. 영상을 보고 실제 횟수를 확인해 주세요"
        calibrationButtonOverride.value = null
        val profileUi = personalProfile(selected.id)?.let { profile ->
            val created = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(profile.createdAtMs))
            CalibrationProfileUi(
                "이전 보정 설정",
                listOf(
                    "저장 시각" to created,
                    "감지된 반복" to "${profile.validRepCount}회",
                    "보정 때 반복 시간" to "${"%.2f".format(profile.medianRepDurationMs / 1_000.0)}초",
                    "설정 용도" to if (selectedProfile.family == dev.kyungmin.kcoach.core.MotionFamily.FLEXION_EXTENSION)
                        "반복 시간 기준" else "움직임·반복 시간 기준",
                ),
                if (selectedProfile.family == dev.kyungmin.kcoach.core.MotionFamily.FLEXION_EXTENSION)
                    "이전 컬 설정은 반복 시간에만 반영돼요. 새 촬영은 확인·검증 전까지 이 설정을 바꾸지 않아요."
                else "새 촬영 자료는 확인·검증 전까지 이전 설정을 바꾸지 않아요.",
            )
        }
        val ui = CalibrationUi(
            selectedName = selected.name,
            status = if (ExerciseMeasurementContracts.resolve(selected.id).recipe == dev.kyungmin.kcoach.core.MeasurementRecipe.SQUAT)
                "전신 영상 관측 · 워치 단독 횟수는 수동 입력" else if (manualRequired) "손목 센서 직접 관측 불가 · 수동 기록" else calibrationLabel(selected.id),
            statusAmber = manualRequired,
            instructions = instructions,
            manualRequired = manualRequired,
            cameraObservationAvailable = ExerciseMeasurementContracts.resolve(selected.id).recipe == dev.kyungmin.kcoach.core.MeasurementRecipe.SQUAT,
            profile = profileUi,
            rows = selectable.map { exercise ->
                val profile = ExerciseTrackingProfiles.resolve(exercise.id, exercise.name)
                CalibrationRowUi(
                    id = exercise.id,
                    name = exercise.name,
                    muscles = exercise.muscles,
                    status = if (profile.mode == TrackingMode.MANUAL_REQUIRED) {
                        if (ExerciseMeasurementContracts.resolve(exercise.id).recipe == dev.kyungmin.kcoach.core.MeasurementRecipe.SQUAT)
                            "전신 영상 관측 · 워치 횟수는 수동" else "수동 기록만"
                    } else {
                        val status = calibrationLabel(exercise.id)
                        if (exercise.id == selectedCalibrationExerciseId) "선택됨 · $status" else status
                    },
                    selected = exercise.id == selectedCalibrationExerciseId,
                    thumbnail = { exerciseAnimationView(exercise, 58, compact = true) },
                )
            },
        )
        val actions = CalibrationActions(
            toggle = ::startOrStopCalibration,
            deleteProfile = { confirmDeletePersonalProfile(selected.id, selected.name) },
            openSensorView = ::showSensorView,
            openCaptureReview = { startActivity(Intent(this, CalibrationResultActivity::class.java)) },
            select = { id ->
                selectedCalibrationExerciseId = id
                showCalibration()
            },
        )
        showScaffold(ComposeView(this).apply {
            setContent {
                CalibrationScreen(
                    ui = ui,
                    sessionActive = { calibrationSessionId != null },
                    button = {
                        calibrationButtonOverride.value ?: CalibrationButtonUi(
                            if (ui.cameraObservationAvailable) "워치와 함께 전신 영상 촬영"
                            else if (manualRequired) "이 운동은 수동 기록만 지원"
                            else if (calibrationSessionId == null) "워치와 함께 영상 촬영"
                            else if (calibrationStopRequested) "보정 원본 저장 중…"
                            else "보정 종료·저장",
                            !calibrationStopRequested,
                        )
                    },
                    actions = actions,
                )
            }
        }, null, RootTab.CALIBRATION)
    }

    private fun showSensorView() {
        if (calibrationSessionId != null || activeWorkout != null || sessionStore.loadActive().getOrNull() != null) {
            Toast.makeText(this, "운동이나 보정을 종료한 뒤 센서 화면을 열어 주세요", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, ProbeActivity::class.java))
    }

    private fun confirmDeletePersonalProfile(exerciseId: String, exerciseName: String) {
        showPopup { dismiss ->
            ConfirmDialog(
                title = "보정된 기록 삭제",
                message = "$exerciseName 보정 기록을 폰과 워치에서 삭제할까요?\n" +
                    "삭제하면 이 운동은 일반 기준으로 측정돼요.",
                negative = "취소",
                positive = "삭제",
                dismiss = dismiss,
            ) {
                val prefs = getSharedPreferences("wear-sync", MODE_PRIVATE)
                prefs.edit()
                    .remove("personalProfile:$exerciseId")
                    .remove("calibration:$exerciseId:status")
                    .remove("calibration:$exerciseId:rawSavedAt")
                    .remove("calibration:$exerciseId:completedAt")
                    .remove("calibration:$exerciseId:error")
                    .apply()
                sendWear(WearSync.PATH_PERSONAL_PROFILE_DELETE, exerciseId)
                Toast.makeText(this, "$exerciseName 보정 기록을 삭제했어요", Toast.LENGTH_LONG).show()
                showCalibration()
            }
        }
    }

    private fun showSheet(content: @Composable (dismiss: () -> Unit) -> Unit) = overlays.show(Overlay(true, content))

    private fun showPopup(content: @Composable (dismiss: () -> Unit) -> Unit) = overlays.show(Overlay(false, content))

    private fun showMenu() {
        stopTicker()
        currentTab = RootTab.MENU
        currentScreen = Screen.ROOT
        val actions = MenuActions(
            checkUpdate = { report ->
                otaCoordinator.checkBoth()
                val state = otaCoordinator.ui()
                report(UpdateUi(state.message, state.readyToInstall, state.busy, state.card))
            },
            install = { otaCoordinator.installBoth(this) },
            openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            observeUpdate = { report -> otaCoordinator.observe { state -> report(UpdateUi(state.message, state.readyToInstall, state.busy, state.card)) } },
        )
        showScaffold(ComposeView(this).apply {
            setContent {
                val wearing = if (activeWorkout != null) WorkoutWearingStore(this@MainActivity).selection(activeWorkout!!.sessionId).getOrNull()
                    else defaultWorkoutWearing
                MenuScreen(BuildConfig.VERSION_NAME, actions, wearing, pendingUpdateNotice) {
                    otaCoordinator.store.clearNotice(); pendingUpdateNotice = null
                }
            }
        }, null, RootTab.MENU)
    }

    @Composable
    private fun RoutinePreparation(routine: WorkoutRoutine) {
        WorkoutPreparationPanel(
            wearing = defaultWorkoutWearing,
            connection = preparationConnection, wearingReadFailed = workoutWearingReadFailed,
            openProfile = { startActivity(Intent(this, WorkoutProfileActivity::class.java)) },
        )
    }

    private fun startWorkout(
        routine: WorkoutRoutine,
        startExerciseIndex: Int = 0,
        startSetIndex: Int = 0,
        onStarted: ((ActiveWorkout) -> Unit)? = null,
    ) {
        if (calibrationSessionId != null) {
            Toast.makeText(this, "보정을 종료한 뒤 운동을 시작해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        if (sessionStore.loadActive().getOrNull() != null) {
            activeWorkout = restoreActiveWorkout()
            if (activeWorkout != null) showActiveWorkout()
            return
        }
        prefillProgression(routine)
        val wearing = runCatching {
            val settings = WorkoutWearingStore(this)
            WorkoutPreparation.selection(settings.defaultSelection().getOrThrow(),
                routine.exercises.map { it.exercise.id }, settings.preparationAnswers().getOrThrow())
        }.getOrElse {
            Toast.makeText(this, "착용 설정을 불러오지 못했어요. 프로필 설정을 확인해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        previewWorkout = null
        val safeExerciseIndex = startExerciseIndex.coerceIn(
            0,
            routine.exercises.lastIndex.coerceAtLeast(0),
        )
        val safeSetIndex = startSetIndex.coerceIn(
            0,
            routine.exercises.getOrNull(safeExerciseIndex)
                ?.sets?.lastIndex?.coerceAtLeast(0) ?: 0,
        )
        val workout = ActiveWorkout(
            routine = routine.copy(exercises = routine.exercises.map { item ->
                item.copy(sets = item.sets.map { it.copy(completed = false, performedReps = null, repSource = null, setId = java.util.UUID.randomUUID().toString(), attemptId = java.util.UUID.randomUUID().toString(), identityRequired = true) }.toMutableList())
            }.toMutableList()),
            exerciseIndex = safeExerciseIndex,
            setIndex = safeSetIndex,
        )
        if (ProgressionPlanStore(this).register(workout.sessionId, routine,
                workout.routine.exercises.flatMap { it.sets }.map { it.setId }).isFailure) return
        WorkoutWearingStore(this).beginSession(workout.sessionId, wearing).onFailure {
            Toast.makeText(this, "착용 상태를 저장하지 못했어요. 다시 시작해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        activeWorkout = workout
        if (!persistActiveWorkout(workout)) {
            activeWorkout = restoreActiveWorkout()
            if (activeWorkout != null) showActiveWorkout()
            return
        }
        val first = workout.routine.exercises.getOrNull(safeExerciseIndex)
        if (first != null) {
            val startPayload = PhoneSessionController.command(workoutSnapshot(workout), "START")
            if (watchUiReady) {
                sendWear(WearSync.PATH_COMMAND, startPayload)
                activeLiveStatus?.text = "워치 앱 준비 완료 · 운동 시작"
            } else {
                pendingWatchStartPayload = startPayload
                activeLiveStatus?.text = "워치 연결 중 · 워치 앱을 열어 주세요"
                wearLiveStatus?.apply {
                    text = "워치 연결 중 · 워치 앱을 열어 주세요"
                    setTextColor(amber)
                }
                syncWorkingSet(workout)
            }
        }
        if (onStarted != null) onStarted(workout) else showActiveWorkout()
    }

    private fun prepareRoutineForWatch(routine: WorkoutRoutine) {
        if (calibrationSessionId != null || routine.exercises.none { it.sets.isNotEmpty() }) return
        val prepared = ActiveWorkout(routine = routine.copy(exercises = routine.exercises.map { item ->
            item.copy(sets = item.sets.map { it.copy(completed = false, performedReps = null, repSource = null,
                setId = java.util.UUID.randomUUID().toString(), attemptId = java.util.UUID.randomUUID().toString(),
                identityRequired = true) }.toMutableList())
        }.toMutableList()))
        if (ProgressionPlanStore(this).register(prepared.sessionId, routine,
                prepared.routine.exercises.flatMap { it.sets }.map { it.setId }).isFailure) return
        val plan = workoutSnapshot(prepared).copy(startedAtMs = 0, restEndAtMs = 0, pausedRestRemainingMs = 0)
        sessionStore.prepareForWatch(plan, System.currentTimeMillis())
            .onSuccess { sendWear(WearSync.PATH_WORKOUT_OFFER, it.encode()) }
            .onFailure { Log.w(EVENT_TAG, "watch routine offer not prepared", it) }
    }

    private fun adjustRestDuration(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        deltaSeconds: Int,
    ) {
        if (activeWorkout === workout) {
            val index = workout.routine.exercises.indexOf(routineExercise)
            val snapshot = sessionStore.adjustRestDuration(workout.sessionId, index, deltaSeconds, System.currentTimeMillis())
                .getOrNull() ?: return
            applySnapshotToWorkout(workout, snapshot)
            resyncActiveWorkoutToWatch(workout)
        } else {
            routineExercise.restSeconds = (routineExercise.restSeconds + deltaSeconds).coerceIn(0, 1800)
            persistActiveWorkout(workout)
        }
        bumpWorkoutUi()
    }

    private fun resyncActiveWorkoutToWatch(workout: ActiveWorkout) {
        // Resynchronize from the committed session, including paused rest.
        PhoneSessionController(SessionStore(this), ::sendWear).resync()
            .onFailure { Log.e(EVENT_TAG, "active watch resync failed", it) }
        activeLiveStatus?.apply {
            text = "현재 운동 상태를 워치와 다시 맞추는 중"
            tag = WATCH_PANEL_CONNECTION
        }
    }

    private fun showActiveWorkout(preview: ActiveWorkout? = null, forceRebuild: Boolean = false) {
        if (preview != null) previewWorkout = preview
        val workout = activeWorkout ?: previewWorkout ?: return showHome()
        if (workout.exerciseIndex >= workout.routine.exercises.size) return
        val reuse = currentScreen == Screen.ACTIVE &&
            workoutComposeView?.parent === root &&
            workoutComposeWorkout === workout
        if (reuse) {
            // Same screen: re-render in place so only what changed animates.
            bumpWorkoutUi()
        } else {
            stopTicker()
            currentScreen = Screen.ACTIVE
            workoutComposeWorkout = workout
            val (liveTitle, liveTelemetry) = buildLivePanel(workout)
            val composeView = ComposeView(this).apply {
                setContent {
                    workoutVersion.intValue
                    val current = workoutComposeWorkout ?: return@setContent
                    ActiveWorkoutScreen(
                        model = buildWorkoutModel(current),
                        actions = workoutActions,
                        liveTitle = liveTitle,
                        liveTelemetry = liveTelemetry,
                        animationView = { index ->
                            exerciseAnimationView(current.routine.exercises[index].exercise, 190)
                        },
                    )
                }
            }
            workoutComposeView = composeView
            navVisible.value = false
            root.removeAllViews()
            root.addView(composeView, FrameLayout.LayoutParams(-1, -1))
            updateWearConnectionStatus()
        }
        val isPreview = activeWorkout == null
        if (isPreview || ticker != null) return
        ticker = object : Runnable {
            override fun run() {
                try {
                    if (activeWorkout !== workout || currentScreen != Screen.ACTIVE) {
                        ticker = null
                        return
                    }
                    if (activeRestEndAtMs > 0L) {
                        val leftMs = activeRestEndAtMs - System.currentTimeMillis()
                        restCue(leftMs)
                        if (leftMs <= 0L) {
                            restCue(0L)
                            // PhoneWorkoutService advances the durable session, even without this screen.
                        }
                    }
                    handler.postDelayed(this, if (activeRestEndAtMs > 0L) 100 else 1_000)
                } catch (error: Exception) {
                    reportWorkoutUiError("active_ticker", error)
                    ticker = null
                }
            }
        }.also { handler.post(it) }
    }

    private fun bumpWorkoutUi() {
        workoutVersion.intValue++
    }

    /** Only workout edits and position changes save state and update the watch. */
    private fun saveWorkoutChanges(workout: ActiveWorkout, forceRebuild: Boolean = false): Boolean {
        if (routines.any { it === workout.routine } && !saveRoutineLibrary()) return false
        if (activeWorkout === workout) {
            if (!persistActiveWorkout(workout)) return false
            if (workout.exerciseIndex >= workout.routine.exercises.size) {
                showMyMountainDecision()
                return true
            }
            syncWorkingSet(workout)
        }
        showActiveWorkout(forceRebuild = forceRebuild)
        return true
    }

    private fun syncWorkingSet(workout: ActiveWorkout) {
        if (activeWorkout === workout && !workout.paused && workout.phase == WorkoutPhase.WORKING && activeRestEndAtMs <= 0L) {
            sendWear(WearSync.PATH_COMMAND, PhoneSessionController.command(workoutSnapshot(workout), "SET"))
        }
    }

    private fun refreshActiveSession() {
        if (activeWorkout == null) {
            activeWorkout = restoreActiveWorkout() ?: return
            previewWorkout = null
            if (currentScreen in setOf(Screen.ACTIVE, Screen.REST, Screen.ROUTINE)) showActiveWorkout(forceRebuild = true)
            else if (currentScreen == Screen.ROOT && currentTab == RootTab.HOME) showHome()
        }
        val workout = activeWorkout ?: return
        val snapshot = sessionStore.loadActive().getOrElse {
            Log.e(EVENT_TAG, "active session refresh failed", it)
            return
        } ?: return
        if (snapshot.sessionId != workout.sessionId) return
        applySnapshotToWorkout(workout, snapshot)
        workout.analyses.clear()
        workout.analyses.putAll(SetAnalysisStore(this).loadForSession(workout.sessionId))
        lastCompletedLiveKey = snapshot.lastCompletedSetKey
        if (snapshot.exerciseIndex >= workout.routine.exercises.size) {
            showMyMountainDecision()
            return
        }
        if (currentScreen == Screen.ACTIVE || currentScreen == Screen.REST) showActiveWorkout()
        else if (currentScreen == Screen.ROOT && currentTab == RootTab.HOME) showHome()
    }

    /** 3·2·1 short cues and a longer one at zero, once each per rest. */
    private fun restCue(leftMs: Long) {
        val second = if (leftMs <= 0L) 0 else ((leftMs + 999L) / 1000L).toInt()
        if (second > 3 || second == lastRestCueSecond || activeRestEndAtMs <= 0L && second != 0) return
        lastRestCueSecond = second
        val vibrator = getSystemService(android.os.Vibrator::class.java)
        if (second == 0) {
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 260), -1))
            runCatching { restTone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 220) }
        } else {
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            runCatching { restTone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90) }
        }
    }

    private fun buildLivePanel(workout: ActiveWorkout): Pair<LiveLine, LiveLine> {
        val cached = lastWearLiveSessionId == workout.sessionId &&
            lastWearLiveTitle.isNotBlank() && lastWearLiveTelemetry.isNotBlank()
        val tag = if (cached) WATCH_PANEL_TELEMETRY else WATCH_PANEL_CONNECTION
        val title = LiveLine(if (cached) lastWearLiveTitle else "Watch8 연결 확인 중", tag)
        val telemetry = LiveLine(if (cached) lastWearLiveTelemetry else "워치 운동 상태 수신 대기 중", tag)
        if (!cached) telemetry.setTextColor(muted)
        activeLiveStatus = telemetry
        wearLiveStatus = title
        return title to telemetry
    }

    private fun effectiveRestSeconds(routineExercise: RoutineExercise, setIndex: Int): Int =
        routineExercise.sets.getOrNull(setIndex)?.restSeconds ?: routineExercise.restSeconds

    private fun volumeUnit(workout: ActiveWorkout) = if (workout.usePounds) 2.20462 else 1.0

    /** Remembers what was really done today, so the next session can compare and build on it. */
    private var newRecords = mutableListOf<RecordUi>()
    private var lastWorkoutRecords: List<RecordUi> = emptyList()

    private fun recordDisplay(name: String, hit: dev.kyungmin.kcoach.core.RecordHit, pounds: Boolean): RecordUi =
        RecordPresentation.record(name, hit, pounds)

    // Record shown for a few seconds after a set. State is per running session so a restart does not replay it.
    private var recordBannerSession: String? = null
    private var recordBannerKey: String? = null
    private var recordBannerState: RecordBannerUi? = null
    private var recordBannerAtMs = 0L
    private var recordBannerSeq = 0L
    private var recordBase: dev.kyungmin.kcoach.core.ExerciseHistory? = null
    private val recordTracker = RecordBannerTracker()

    private fun recordBannerFor(workout: ActiveWorkout): RecordBannerUi? {
        if (activeWorkout !== workout) return null
        if (workout.sessionId != recordBannerSession) {
            recordBannerSession = workout.sessionId
            recordBannerKey = workout.lastCompletedSetKey // already done before this screen existed: nothing to announce
            recordBannerState = null
            recordBase = null
            recordTracker.reset()
            return null
        }
        val key = workout.lastCompletedSetKey
        if (key != recordBannerKey) {
            recordBannerKey = key
            if (key != null) runCatching { detectRecordAfterSet(workout, key) }
                .onFailure { Log.w(EVENT_TAG, "record check after set failed", it) }
        }
        return recordBannerState?.takeIf { System.currentTimeMillis() - recordBannerAtMs < RECORD_BANNER_MODEL_MS }
    }

    private fun detectRecordAfterSet(workout: ActiveWorkout, key: String) {
        val exerciseIndex = workout.routine.exercises.withIndex().firstNotNullOfOrNull { (exerciseIndex, exercise) ->
            exercise.sets.withIndex().firstOrNull { (setIndex, set) ->
                set.completed && "${workout.sessionId}:${SetIdentity.key(exercise.exercise.id, setIndex, set.setId, set.attemptId)}" == key
            }?.let { exerciseIndex }
        } ?: return
        val today = java.time.Instant.ofEpochMilli(workout.startedAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val log = ProgressionPlanStore(this).logs(workoutSnapshot(workout), today, workout.analyses)
            .firstOrNull { it.exerciseIndex == exerciseIndex } ?: return
        if (log.sets.none { it.completed && it.repsEntered }) return
        val base = recordBase ?: FinishedWorkoutStore(this).historyForRecords().also { recordBase = it }
        val hit = recordTracker.pick(PersonalRecords.detect(base, log)) ?: return
        recordBannerState = RecordBannerUi(++recordBannerSeq,
            RecordPresentation.record(workout.routine.exercises[exerciseIndex].exercise.name, hit, workout.usePounds))
        recordBannerAtMs = System.currentTimeMillis()
        val snapshot = workoutSnapshot(workout)
        snapshot.plannedSets.firstOrNull { SetIdentity.completionKey(snapshot.sessionId, it) == key }
            ?.takeIf { it.setId.isNotBlank() }?.let { set ->
                sendWear(WearSync.PATH_SET_RECORD,
                    SetRecordCommand.fromHit(snapshot.sessionId, set.setId, set.attemptId, hit).encode())
            }
    }

    private fun recordHistory(workout: ActiveWorkout) {
        newRecords = mutableListOf()
        val today = java.time.Instant.ofEpochMilli(workout.startedAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val previous = FinishedWorkoutStore(this).historyForRecords()
        ProgressionPlanStore(this).logs(workoutSnapshot(workout), today, workout.analyses).forEach { log ->
            if (log.sets.none { it.completed && it.repsEntered }) return@forEach
            val item = workout.routine.exercises[log.exerciseIndex]
            PersonalRecords.detect(previous, log).forEach { hit ->
                newRecords += recordDisplay(item.exercise.name, hit, workout.usePounds)
            }
            historyCache = historyStore.add(log)
        }
        lastWorkoutRecords = newRecords.toList()
    }

    private fun recordWorkoutLog(workout: ActiveWorkout) {
        FinishedWorkoutStore(this).finish(workoutSnapshot(workout), workout.routine.name,
            (workoutElapsedSeconds(workout) / 60L).toInt(), workout.usePounds)
        val index = routines.indexOfFirst { it.id == workout.routine.id }
        if (index >= 0) {
            val performedDay = java.time.Instant.ofEpochMilli(workout.startedAtMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val updated = routines[index].copy(lastPerformedEpochDay = performedDay)
            val candidate = routines.toMutableList().apply { set(index, updated) }
            if (saveRoutineLibrary(candidate)) routines[index] = updated
        }
        WatchOverviewPublisher.publish(this)
        historyCache = null
    }

    private fun statsUi(): StatsUi {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val logs = workoutLogStore.load()
        val inMonth = logs.filter { LocalDate.ofEpochDay(it.epochDay).let { d -> YearMonth.from(d) == month } }
        val bodyOf = WorkoutData.catalog.associate { it.id to it.bodyCategory }
        val weekSets = BodyLoad.setsByPart(history.all, bodyOf::get, today.toEpochDay())
        if (inMonth.isEmpty()) return StatsUi("${month.monthValue}월", emptyList(), emptyList(), weekSets)
        val totals = WorkoutStats.between(logs, month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
        return StatsUi(
            "${month.monthValue}월",
            listOf(
                "${totals.workouts}회" to "운동",
                "${totals.minutes}분" to "총 시간",
                "${totals.sets}세트" to "총 세트",
                "%,dkg".format(totals.volumeKg) to "총 볼륨",
            ),
            WorkoutStats.byTitle(inMonth).map { (title, sets) -> title to "${sets}세트" },
            weekSets,
        )
    }

    private fun prefillProgression(routine: WorkoutRoutine) {
        ProgressionPlanStore(this).prefill(routine)
            .onSuccess { if (routines.any { it === routine }) saveRoutineLibrary() }
            .onFailure { Log.e(EVENT_TAG, "progression prefill failed", it) }
    }

    private fun historyUi(item: RoutineExercise, workout: ActiveWorkout): HistoryUi? {
        val last = history.last(item.exercise.id) ?: return null
        val performed = last.sets.filter { it.completed && it.repsEntered }
        if (performed.isEmpty()) return null
        val lastLine = "지난번 " + performed.take(4).joinToString(" · ") { set ->
            if (set.weightKg == null) "${set.reps}회" else "${formatActiveWeight(set.weightKg, workout.usePounds)}×${set.reps}"
        } + if (performed.size > 4) " …" else ""
        return HistoryUi(lastLine, (last.volumeKg * volumeUnit(workout)).toInt(), null, null, false)
    }

    /** Replaces the not-yet-started sets of an exercise with what was done last time. */
    private fun loadLastSets(item: RoutineExercise) {
        val last = history.last(item.exercise.id) ?: return
        if (item.sets.any { it.completed }) return
        item.sets.clear()
        last.sets.forEach { item.sets += PlannedSet(it.weightKg, it.targetReps ?: it.reps,
            setType = if (it.isWarmup) SetType.WARMUP else SetType.NORMAL) }
    }

    private fun buildWorkoutModel(workout: ActiveWorkout): WorkoutUiModel {
        val next = if (activeWorkout === workout) nextWorkoutTarget(workout) else null
        val nextText = next?.takeIf { it.exerciseId.isNotEmpty() }?.let { target ->
            val exercise = workout.routine.exercises.getOrNull(target.exerciseIndex)
            val setLabel = exercise?.sets?.workSetLabel(target.setIndex)?.let { if (it == "W") "웜업" else "${it}세트" }.orEmpty()
            val weight = target.weightKg?.let { "${formatActiveWeight(it, workout.usePounds)}${if (workout.usePounds) "lb" else "kg"} × " }.orEmpty()
            "다음: ${target.exerciseName} $setLabel · $weight${target.targetReps}회"
        }
        return WorkoutUiModel(
            routineName = workout.routine.name,
            isPreview = activeWorkout !== workout,
            paused = workout.paused,
            startedAtMs = workout.startedAtMs,
            totalPausedMs = workout.totalPausedMs,
            pausedSinceMs = workout.pausedSinceMs,
            exerciseIndex = workout.restingPageIndex ?: workout.exerciseIndex,
            usePounds = workout.usePounds,
            restStartAtMs = activeRestStartAtMs,
            restEndAtMs = activeRestEndAtMs,
            pausedRestRemainingMs = pausedRestRemainingMs,
            nextSetText = nextText,
            recordBanner = recordBannerFor(workout),
            restingSet = if (activeWorkout === workout && (activeRestEndAtMs > 0L || pausedRestRemainingMs > 0L)) {
                workout.routine.exercises.flatMapIndexed { exerciseIndex, exercise ->
                    exercise.sets.mapIndexedNotNull { setIndex, set ->
                        val key = "${workout.sessionId}:${SetIdentity.key(exercise.exercise.id, setIndex, set.setId, set.attemptId)}"
                        if (set.completed && key == workout.lastCompletedSetKey) exerciseIndex to setIndex else null
                    }
                }.singleOrNull()
            } else null,
            exercises = workout.routine.exercises.mapIndexed { exerciseIndex, routineExercise ->
                val currentSet = if (exerciseIndex == workout.exerciseIndex) workout.setIndex
                else workout.setIndexByExercise[workoutExerciseKey(routineExercise)]
                    ?: routineExercise.sets.indexOfFirst { !it.completed }.coerceAtLeast(0)
                ExerciseUi(
                    id = routineExercise.exercise.id,
                    name = routineExercise.exercise.name,
                    muscles = routineExercise.exercise.muscles,
                    weighted = routineExercise.exercise.equipment != "맨몸",
                    restSeconds = routineExercise.restSeconds,
                    targetReps = routineExercise.sets.sumOf { it.targetReps },
                    performedReps = routineExercise.sets.filter { it.completed }.sumOf { it.performedReps ?: 0 },
                    completedWorkSets = routineExercise.sets.completedWorkSetCount(),
                    workSetCount = routineExercise.sets.workSetCount(),
                    currentSetIndex = currentSet,
                    volumeDone = (routineExercise.sets.filter { it.completed }.sumOf { (it.weightKg ?: 0.0) * (it.performedReps ?: 0) } * volumeUnit(workout)).toInt(),
                    volumeTarget = (routineExercise.sets.sumOf { (it.weightKg ?: 0.0) * it.targetReps } * volumeUnit(workout)).toInt(),
                    history = historyUi(routineExercise, workout),
                    sets = routineExercise.sets.mapIndexed { setIndex, set ->
                        SetUi(
                            uid = System.identityHashCode(set),
                            label = routineExercise.sets.workSetLabel(setIndex),
                            weightText = formatActiveWeight(set.weightKg, workout.usePounds),
                            reps = if (set.completed) set.performedReps else set.targetReps,
                            completed = set.completed,
                            warmup = set.setType == SetType.WARMUP,
                            grip = set.gripType,
                            restSeconds = effectiveRestSeconds(routineExercise, setIndex),
                            customRest = set.restSeconds != null,
                            insight = (workout.analyses[SetIdentity.key(routineExercise.exercise.id, setIndex, set.setId, set.attemptId)]
                                ?: if (!set.identityRequired) workout.analyses["${routineExercise.exercise.id}:$setIndex"] else null)
                                ?.let { SetInsight.describe(it.tempoConsistency, it.lateDrift) },
                            previous = history.last(routineExercise.exercise.id)?.sets?.getOrNull(setIndex)?.let { last ->
                                val weight = last.weightKg
                                if (weight == null) "${last.reps}회" else "${formatActiveWeight(weight, workout.usePounds)}×${last.reps}"
                            },
                        )
                    },
                )
            },
        )
    }

    private val workoutActions = object : WorkoutActions {
        private fun workout() = activeWorkout ?: previewWorkout
        private fun exercise(index: Int) = workout()?.routine?.exercises?.getOrNull(index)

        override fun onBack() {
            val workout = workout() ?: return showHome()
            if (activeWorkout == null) {
                previewWorkout = null
                showRoutine(workout.routine)
            } else {
                showHome()
            }
        }

        override fun onMenu() {
            workout()?.let(::showWorkoutMenu)
        }

        override fun onExerciseInfo(exerciseIndex: Int) {
            val exercise = exercise(exerciseIndex) ?: return
            showExerciseInfo(exercise.exercise) { showActiveWorkout() }
        }

        override fun onPageSettled(exerciseIndex: Int) {
            val workout = workout() ?: return
            if (exerciseIndex != (workout.restingPageIndex ?: workout.exerciseIndex)) showWorkoutExercise(workout, exerciseIndex)
        }

        override fun onEditValue(exerciseIndex: Int, setIndex: Int, field: SetField, value: String, applyAll: Boolean) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            val targets = if (applyAll) routineExercise.sets.filter { !it.completed } + listOfNotNull(routineExercise.sets.getOrNull(setIndex))
            else listOfNotNull(routineExercise.sets.getOrNull(setIndex))
            targets.distinct().forEach { set ->
                when (field) {
                    SetField.WEIGHT -> {
                        val parsed = value.toDoubleOrNull()
                        set.weightKg = when {
                            value.isBlank() -> 0.0
                            parsed == null -> set.weightKg
                            workout.usePounds -> parsed / 2.20462
                            else -> parsed
                        }
                    }
                    SetField.REPS -> {
                        val reps = value.toIntOrNull()?.takeIf { it in 0..9_999 } ?: return@forEach
                        if (set.completed) {
                            set.performedReps = reps
                            set.repSource = SetRepSource.MANUAL
                        } else set.targetReps = reps
                    }
                }
            }
            workout.selectedEditSetIndex = setIndex
            bumpWorkoutUi()
        }

        override fun onEditFinished() {
            // Persists and, while working, re-sends the current set target to the watch.
            workout()?.let { saveWorkoutChanges(it) }
        }

        override fun onToggleSet(exerciseIndex: Int, setIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            val plannedSet = routineExercise.sets.getOrNull(setIndex) ?: return
            if (!plannedSet.completed) {
                if (activeWorkout !== workout) {
                    startWorkout(
                        routine = workout.routine,
                        startExerciseIndex = exerciseIndex,
                        startSetIndex = setIndex,
                    ) { startedWorkout ->
                        val startedExercise = startedWorkout.routine.exercises
                            .getOrNull(startedWorkout.exerciseIndex) ?: return@startWorkout
                        val startedSet = startedExercise.sets.getOrNull(setIndex) ?: return@startWorkout
                        requestSetCompletion(startedWorkout, startedExercise, setIndex, startedSet)
                    }
                    return
                }
                requestSetCompletion(workout, routineExercise, setIndex, plannedSet)
            } else {
                plannedSet.completed = false
                val current = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0
                workout.performedRepsByExercise[routineExercise.exercise.id] =
                    (current - (plannedSet.performedReps ?: 0)).coerceAtLeast(0)
                plannedSet.performedReps = null
                plannedSet.repSource = null
                plannedSet.attemptId = java.util.UUID.randomUUID().toString()
                plannedSet.identityRequired = true
                workout.analyses.clear()
                workout.phase = if (workout.paused) WorkoutPhase.PAUSED else WorkoutPhase.WORKING
                workout.exerciseIndex = exerciseIndex
                workout.setIndex = setIndex
                activeRestStartAtMs = 0L
                activeRestEndAtMs = 0L
                pausedRestRemainingMs = 0L
                if (workout.lastCompletedSetKey != null) {
                    workout.lastCompletedSetKey = null
                    lastCompletedLiveKey = null
                }
                if (!persistActiveWorkout(workout)) return
                if (activeWorkout === workout) resyncActiveWorkoutToWatch(workout)
                bumpWorkoutUi()
            }
        }

        override fun onSetMenu(exerciseIndex: Int, setIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            workout.selectedEditSetIndex = setIndex
            showSetMenu(workout, routineExercise, setIndex)
        }

        override fun onApplySuggestion(exerciseIndex: Int) {
            if (exercise(exerciseIndex) == null) return
            // Targets are prefilled once from finished history; user edits remain authoritative.
            workout()?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onAddSet(exerciseIndex: Int) {
            val routineExercise = exercise(exerciseIndex) ?: return
            val source = routineExercise.sets.lastOrNull() ?: PlannedSet(null, 10)
            routineExercise.sets += PlannedSet(source.weightKg, source.targetReps, gripType = source.gripType, restSeconds = source.restSeconds)
            workout()?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onRemoveSet(exerciseIndex: Int, setIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            if (routineExercise.sets.size <= 1 || setIndex !in routineExercise.sets.indices) return
            val measuring = routineExercise.sets.getOrNull(workout.setIndex)
            val removed = routineExercise.sets.removeAt(setIndex)
            lastRemovedSet = Triple(routineExercise, setIndex, removed)
            workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceAtMost(routineExercise.sets.lastIndex)
            if (exerciseIndex == workout.exerciseIndex) {
                workout.setIndex = routineExercise.sets.indexOf(measuring).takeIf { it >= 0 } ?: workout.setIndex.coerceAtMost(routineExercise.sets.lastIndex)
            }
            if (!persistActiveWorkout(workout)) return
            if (activeWorkout === workout) resyncActiveWorkoutToWatch(workout)
            bumpWorkoutUi()
        }

        override fun onUndoRemoveSet() {
            val (routineExercise, index, set) = lastRemovedSet ?: return
            lastRemovedSet = null
            val workout = workout() ?: return
            val measuring = routineExercise.sets.getOrNull(workout.setIndex)
            routineExercise.sets.add(index.coerceAtMost(routineExercise.sets.size), set)
            if (workout.routine.exercises.getOrNull(workout.exerciseIndex) === routineExercise) {
                workout.setIndex = routineExercise.sets.indexOf(measuring).takeIf { it >= 0 } ?: index
            }
            if (!persistActiveWorkout(workout)) return
            if (activeWorkout === workout) resyncActiveWorkoutToWatch(workout)
            bumpWorkoutUi()
        }

        override fun onSetRest(exerciseIndex: Int, setIndex: Int) {
            val routineExercise = exercise(exerciseIndex) ?: return
            val set = routineExercise.sets.getOrNull(setIndex) ?: return
            showChoiceSheet(
                "이 세트 뒤 휴식",
                selected = set.restSeconds?.let { "${it}초" } ?: "기본값",
                items = listOf("기본값" to "운동 기본 휴식 ${routineExercise.restSeconds}초") +
                    listOf(30, 45, 60, 90, 120, 150, 180).map { "${it}초" to "" },
            ) { value ->
                set.restSeconds = value.removeSuffix("초").toIntOrNull()
                workout()?.let(::persistActiveWorkout)
                bumpWorkoutUi()
            }
        }

        override fun onAllSetsDone(exerciseIndex: Int) {
            val workout = workout() ?: return
            if (activeWorkout !== workout) {
                startWorkout(workout.routine, startExerciseIndex = exerciseIndex) { started ->
                    started.routine.exercises.getOrNull(exerciseIndex)?.let { requestAllSetsCompletion(started, it) }
                }
                return
            }
            exercise(exerciseIndex)?.let { requestAllSetsCompletion(workout, it) }
        }

        override fun onAdjustRest(deltaSeconds: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(workout.exerciseIndex) ?: return
            adjustRestDuration(workout, routineExercise, deltaSeconds)
        }

        override fun onRestPreset(seconds: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(workout.exerciseIndex) ?: return
            adjustRestDuration(workout, routineExercise, seconds - routineExercise.restSeconds)
        }

        override fun onSkipRest() {
            if (activeWorkout == null) return
            if (activeRestEndAtMs <= 0L) return
            stopTicker()
            PhoneSessionController(sessionStore, ::sendWear).advanceRest(System.currentTimeMillis(), skip = true)
                .onSuccess { refreshActiveSession() }
                .onFailure { Log.e(EVENT_TAG, "rest skip failed; session retained", it) }
        }

        override fun onTogglePause() {
            activeWorkout?.let(::toggleWorkoutPause)
        }

        override fun onStop() {
            if (activeWorkout != null) confirmStopWorkout()
        }

        override fun onToggleUnit() {
            val workout = workout() ?: return
            workout.usePounds = !workout.usePounds
            bumpWorkoutUi()
        }
    }

    private var telemetrySessionId = ""
    private var telemetrySequence = 0L

    private fun handleLivePayload(payload: String) {
        val fields = WearSync.parse(payload)
        if (fields.size < 12 || fields[0] != "2") return
        val sessionId = fields[1]
        val exerciseName = fields[3]
        val setIndex = fields[4].toIntOrNull() ?: return
        val reps = fields[5].toIntOrNull() ?: 0
        val targetReps = fields[6].toIntOrNull() ?: 0
        val weight = fields[7].takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val heartRate = fields[8].toIntOrNull() ?: 0
        val phase = fields[9]
        val sequence = fields[11].toLongOrNull() ?: 0L
        val personalizedTracking = fields.getOrNull(15)?.toBooleanStrictOrNull() == true
        val workout = activeWorkout ?: return
        if (workout.sessionId != sessionId) return
        val observed = SetIdentity.resolve(workoutSnapshot(workout), fields[2], setIndex, fields.getOrNull(17).orEmpty(), fields.getOrNull(18).orEmpty()) ?: return
        // The watch's telemetry clock must never be compared with a phone-side completion timestamp.
        if (sessionId != telemetrySessionId) {
            telemetrySessionId = sessionId
            telemetrySequence = 0L
        }
        if (phase != "SET_COMPLETE") {
            val target = nextWorkoutTarget(workout) ?: return
            if (fields[2] != target.exerciseId || observed.setId != target.setId || observed.attemptId != target.attemptId) return
            if (sequence > 0L && sequence < telemetrySequence) return
            telemetrySequence = sequence
        }

        val weightText = weight?.let { " · ${cleanWeight(it)}kg" } ?: ""
        // While resting the watch already points at the next set, so the title carries only the exercise.
        val firstLine = (if (phase == "RESTING") exerciseName else "$exerciseName · 세트 ${observed.setIndex + 1}") +
            if (personalizedTracking) " · 개인보정 적용" else ""
        val secondLine = when (phase) {
            "POSITIONING" -> "측정 준비 중 · 손목 안정 확인"
            "PREPARING" -> "손목 안정 확인 · 흔들림 없이 2.5초 유지"
            "START_DIRECTION_WAITING" -> "준비 손목 방향 확인 · 2초 유지 또는 워치에서 직접 시작"
            "WORKING_START_CONFIRMED" -> "시작 손목 방향 확인 · ${reps}/${targetReps}회$weightText · ${heartRateText(heartRate)}"
            "WORKING_START_BYPASSED" -> "직접 시작 · 방향 미확인 · ${reps}/${targetReps}회$weightText · ${heartRateText(heartRate)}"
            "WORKING_START_UNCHECKED" -> "시작 방향 미확인 · ${reps}/${targetReps}회$weightText · ${heartRateText(heartRate)}"
            "RESTING" -> "${observed.setIndex}세트 종료 · 다음 세트: ${observed.setIndex + 1}세트$weightText · ${targetReps}회 · ${heartRateText(heartRate)}"
            "SET_COMPLETE" -> "세트 완료 · ${reps}회$weightText · ${heartRateText(heartRate)}"
            else -> "${reps}/${targetReps}회$weightText · ${heartRateText(heartRate)}"
        }
        lastWearLiveSessionId = sessionId
        lastWearLiveTitle = firstLine
        lastWearLiveTelemetry = secondLine
        activeLiveStatus?.apply {
            text = secondLine
            tag = WATCH_PANEL_TELEMETRY
            setTextColor(green)
        }
        restHeartStatus?.text = if (heartRate in 20..240) "워치 심박수  ${heartRate} bpm" else "워치 심박수 측정 대기"
        wearLiveStatus?.apply {
            text = firstLine
            tag = WATCH_PANEL_TELEMETRY
            setTextColor(green)
        }

        // Completion and rest progression are committed by the background service.
        // This screen only reads the durable result, including when the notification arrived first.
        if (phase == "SET_COMPLETE") refreshActiveSession()
    }

    private fun updateWearConnectionStatus() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                preparationConnection = if (nodes.isNotEmpty()) "워치 연결됨" else "워치 연결 안 됨 · 연결 후 자동으로 전달해요"
                wearLiveStatus?.takeIf { it.tag == WATCH_PANEL_CONNECTION }?.apply {
                    text = if (nodes.isNotEmpty()) {
                        "Watch8 연결됨 · 운동 시작 가능"
                    } else {
                        "Watch8 연결 안 됨"
                    }
                    setTextColor(if (nodes.isNotEmpty()) green else muted)
                }
            }
            .addOnFailureListener {
                preparationConnection = "워치 연결을 확인하지 못했어요"
                wearLiveStatus?.takeIf { it.tag == WATCH_PANEL_CONNECTION }?.apply {
                    text = "Watch8 연결 안 됨"
                    setTextColor(muted)
                }
            }
    }

    private fun workoutElapsedSeconds(workout: ActiveWorkout, now: Long = System.currentTimeMillis()): Long {
        val pausedNow = if (workout.paused) now - workout.pausedSinceMs else 0L
        return ((now - workout.startedAtMs - workout.totalPausedMs - pausedNow) / 1000).coerceAtLeast(0)
    }

    private fun toggleWorkoutPause(workout: ActiveWorkout) {
        val snapshot = sessionStore.setPaused(workout.sessionId, !workout.paused, System.currentTimeMillis())
            .getOrNull() ?: return
        applySnapshotToWorkout(workout, snapshot)
        if (!snapshot.paused && snapshot.phase == WorkoutPhase.WORKING) syncWorkingSet(workout)
        else resyncActiveWorkoutToWatch(workout)
        showActiveWorkout(forceRebuild = true)
    }

    private fun formatActiveWeight(weightKg: Double?, usePounds: Boolean): String {
        if (weightKg == null) return "체중"
        val value = if (usePounds) weightKg * 2.20462 else weightKg
        return cleanWeight(value)
    }

    private fun requestSetCompletion(workout: ActiveWorkout, exercise: RoutineExercise, setIndex: Int, set: PlannedSet) {
        val revision = sessionStore.manualSetRevision(workout.sessionId, set.setId, set.attemptId).getOrNull() ?: return
        // A check tap just completes the set at its target; the actual count is edited in the set row afterwards.
        completeWorkoutSet(workout, exercise, setIndex, set, set.targetReps, revision)
    }

    private fun requestAllSetsCompletion(workout: ActiveWorkout, exercise: RoutineExercise) {
        val remaining = exercise.sets.withIndex().filter { !it.value.completed }
        if (remaining.isEmpty()) return
        val before = sessionStore.loadActive().getOrNull() ?: return
        val identities = remaining.map { it.value.setId to it.value.attemptId }
        showSheet { dismiss ->
            dev.kyungmin.kcoach.phone.ui.ManualCountSheet("모든 세트 완료", remaining.map { it.value.targetReps }, remaining.map { it.index + 1 }) { counts ->
                dismiss()
                val current = sessionStore.loadActive().getOrNull()
                if (activeWorkout !== workout || current != before || remaining.map { it.value.setId to it.value.attemptId } != identities) {
                    Toast.makeText(this, "세트가 변경됐어요. 최신 횟수를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    refreshActiveSession()
                    return@ManualCountSheet
                }
                val actual = identities.zip(counts).toMap()
                val sets = current.plannedSets.map { saved -> actual[saved.setId to saved.attemptId]?.let { count ->
                    saved.copy(completed = true, performedReps = count, repSource = SetRepSource.MANUAL)
                } ?: saved }
                val updated = current.copy(plannedSets = sets, performedReps = sets.filter { it.completed }
                    .groupBy { it.exerciseId }.mapValues { (_, values) -> values.sumOf { it.performedReps ?: 0 } })
                if (!sessionStore.saveActive(updated)) { refreshActiveSession(); return@ManualCountSheet }
                applySnapshotToWorkout(workout, updated)
                resyncActiveWorkoutToWatch(workout)
                bumpWorkoutUi()
            }
        }
    }

    private fun completeWorkoutSet(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        setIndex: Int,
        plannedSet: PlannedSet,
        performedReps: Int,
        expectedRevision: Long,
    ) {
        val restSeconds = effectiveRestSeconds(routineExercise, setIndex)
        val transaction = sessionStore.applyManualSet(
            dev.kyungmin.kcoach.core.ManualSetCommand(java.util.UUID.randomUUID().toString(), workout.sessionId,
                routineExercise.exercise.id, setIndex, plannedSet.setId, plannedSet.attemptId,
                performedReps, expectedRevision, dev.kyungmin.kcoach.core.ManualSetOperation.COMPLETE), System.currentTimeMillis(),
        ).getOrElse {
            activeLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                text = "세트 완료 저장 실패 · 세션은 유지 중"
                setTextColor(amber)
            }
            return
        }
        if (transaction.ack.status != dev.kyungmin.kcoach.core.ManualSetStatus.APPLIED) {
            Toast.makeText(this, "세트가 변경됐어요. 최신 횟수를 확인해 주세요.", Toast.LENGTH_LONG).show()
            refreshActiveSession()
            return
        }
        val saved = requireNotNull(transaction.snapshot)
        applySnapshotToWorkout(workout, saved)
        lastCompletedLiveKey = workout.lastCompletedSetKey
        activeRestStartAtMs = saved.restEndAtMs - restSeconds * 1_000L
        activeRestEndAtMs = saved.restEndAtMs
        if (activeWorkout === workout && transaction.restStarted && !saved.paused) {
            sendWear(
                WearSync.PATH_COMMAND,
                PhoneSessionController.command(saved, "REST", restSeconds),
            )
        }
        if (transaction.restStarted && !saved.paused) showRest(restSeconds, saved.restEndAtMs)
        else { resyncActiveWorkoutToWatch(workout); showActiveWorkout(forceRebuild = true) }
    }

    private fun workoutExerciseKey(exercise: RoutineExercise): String = exercise.sets.firstOrNull()?.setId ?: exercise.exercise.id

    private fun showWorkoutExercise(workout: ActiveWorkout, index: Int): Boolean {
        if (index !in workout.routine.exercises.indices) return false
        if (activeWorkout === workout && (activeRestEndAtMs > 0L || pausedRestRemainingMs > 0L)) {
            workout.restingPageIndex = index
            bumpWorkoutUi()
            return true
        }
        workout.restingPageIndex = null
        val previousExerciseId = workout.routine.exercises
            .getOrNull(workout.exerciseIndex)?.exercise?.id
        workout.routine.exercises.getOrNull(workout.exerciseIndex)?.let { previous ->
            val key = workoutExerciseKey(previous)
            workout.setIndexByExercise[key] = workout.setIndex
            workout.selectedSetIndexByExercise[key] = workout.selectedEditSetIndex
            workout.scrollYByExercise[key] = workout.scrollY
        }
        if (workout.exerciseIndex != index) {
            workout.routine.exercises.getOrNull(workout.exerciseIndex)?.sets?.getOrNull(workout.setIndex)?.takeIf { !it.completed }?.let { set ->
                workout.analyses.remove(SetIdentity.key(previousExerciseId.orEmpty(), workout.setIndex, set.setId, set.attemptId))
                set.attemptId = java.util.UUID.randomUUID().toString()
                set.identityRequired = true
            }
        }
        val targetExercise = workout.routine.exercises[index]
        val targetExerciseKey = workoutExerciseKey(targetExercise)
        workout.exerciseIndex = index
        workout.setIndex = workout.setIndexByExercise[targetExerciseKey]
            ?.coerceIn(0, targetExercise.sets.lastIndex.coerceAtLeast(0)) ?: 0
        workout.selectedEditSetIndex = workout.selectedSetIndexByExercise[targetExerciseKey]
            ?.coerceIn(0, targetExercise.sets.lastIndex.coerceAtLeast(0)) ?: workout.setIndex
        workout.scrollY = workout.scrollYByExercise[targetExerciseKey] ?: 0
        workout.phase = if (workout.paused) WorkoutPhase.PAUSED else WorkoutPhase.WORKING
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        pausedRestRemainingMs = 0L
        return saveWorkoutChanges(workout, forceRebuild = true)
    }

    private fun showWorkoutMenu(workout: ActiveWorkout) {
        val pageIndex = workout.restingPageIndex ?: workout.exerciseIndex
        val routineExercise = workout.routine.exercises.getOrNull(pageIndex) ?: return
        showSheet { dismiss ->
            SheetOptions(
                routineExercise.exercise.name,
                listOf(
                    SheetItem(KIcon.Sliders, if (workout.usePounds) "킬로그램으로 단위 변경" else "파운드로 단위 변경") {
                        workout.usePounds = !workout.usePounds
                        dismiss()
                        showActiveWorkout()
                    },
                    SheetItem(KIcon.Clock, "휴식 시간", featured = true, value = "${routineExercise.restSeconds}초") {
                        showChoiceSheet("휴식 시간", selected = "${routineExercise.restSeconds}초", items = listOf("30초" to "짧은 휴식", "60초" to "일반 휴식", "90초" to "근비대 기본", "120초" to "고중량 휴식", "160초" to "긴 휴식")) { value ->
                            routineExercise.restSeconds = value.removeSuffix("초").toInt()
                            saveWorkoutChanges(workout)
                        }
                    },
                    SheetItem(KIcon.Swap, "운동 대체", featured = true) {
                        dismiss()
                        showExerciseAlternatives(workout.routine, pageIndex) { saveWorkoutChanges(workout) }
                    },
                ) + if (history.last(routineExercise.exercise.id) != null && routineExercise.sets.none { it.completed }) listOf(
                    SheetItem(KIcon.Reload, "지난 세트 불러오기") {
                        dismiss()
                        loadLastSets(routineExercise)
                        saveWorkoutChanges(workout, forceRebuild = true)
                    },
                ) else emptyList(),
            )
        }
    }

    private fun showSetMenu(workout: ActiveWorkout, routineExercise: RoutineExercise, setIndex: Int) {
        val set = routineExercise.sets.getOrNull(setIndex) ?: return
        val setLabel = routineExercise.sets.workSetLabel(setIndex).let { if (it == "W") "웜업" else "${it}세트" }
        showSheet { dismiss ->
            var type by remember { mutableStateOf(set.setType) }
            var grip by remember { mutableStateOf(set.gripType) }
            var rpe by remember { mutableStateOf(set.rpe) }
            val quickTypes = listOf("일반", "웜업", "슈퍼", "드랍", "실패", "보조")
            val quickGrips = listOf("노멀" to "노멀", "오버핸드" to "오버", "언더핸드" to "언더", "뉴트럴" to "뉴트럴")
            val quickRpe = listOf("6", "7", "8", "9", "10")
            val typeNotes = mapOf("일반" to "기본 작업 세트", "웜업" to "근육을 준비하기 위한 워밍업", "슈퍼" to "두 운동을 쉬지 않고 연속으로", "드랍" to "휴식 없이 무게를 낮춰 이어서", "실패" to "실패까지 간 세트", "보조" to "도움을 받아 끝낸 세트")
            val gripNotes = mapOf("노멀" to "표준 그립", "오버핸드" to "손바닥이 몸 반대쪽을 향하는 그립", "언더핸드" to "손바닥이 몸 쪽을 향하는 그립", "뉴트럴" to "양손이 서로 마주 보는 그립", "와이드" to "어깨 너비보다 넓은 손 배치", "클로즈" to "어깨 너비보다 좁은 손 배치", "얼터네이트" to "한 손은 오버핸드, 다른 한 손은 언더핸드")
            val rpeNotes = mapOf("10" to "더는 못 해요", "9.5" to "1개 정도 더 할 수 있을 것 같아요", "9" to "확실히 1개 더 할 수 있어요", "8.5" to "아마 2개 더 할 수 있어요", "8" to "확실히 2개 더 할 수 있어요", "7" to "꽤 할 만했어요", "6" to "웜업 정도예요")
            SetSheet(
                title = setLabel,
                subtitle = "${routineExercise.exercise.name} · ${set.targetReps}회",
                groups = listOf(
                    SegGroup(
                        "세트 종류",
                        note = typeNotes[type.displayName] ?: "",
                        options = quickTypes.map { name ->
                            SegOption(name, type.displayName == name) {
                                set.setType = SetType.fromStorage(name)
                                type = set.setType
                                saveWorkoutChanges(workout)
                            }
                        },
                    ),
                    SegGroup(
                        "그립",
                        note = gripNotes[grip] ?: "",
                        options = quickGrips.map { (value, label) ->
                            SegOption(label, grip == value) {
                                set.gripType = value
                                grip = value
                                saveWorkoutChanges(workout)
                            }
                        } + SegOption(if (quickGrips.none { it.first == grip }) grip else "더보기", quickGrips.none { it.first == grip }) {
                            showChoiceSheet("그립", selected = grip, items = listOf("노멀" to "표준 그립", "오버핸드" to "손바닥이 몸 반대쪽을 향하는 그립", "언더핸드" to "손바닥이 몸 쪽을 향하는 그립", "뉴트럴" to "양손이 서로 마주 보는 그립", "와이드" to "어깨 너비보다 넓은 손 배치", "클로즈" to "어깨 너비보다 좁은 손 배치", "얼터네이트" to "한 손은 오버핸드, 다른 한 손은 언더핸드")) { value ->
                                set.gripType = value
                                saveWorkoutChanges(workout)
                            }
                        },
                    ),
                    SegGroup(
                        "운동 강도 (RPE)",
                        note = rpeNotes[rpe] ?: "기록하지 않아요",
                        options = listOf(SegOption("-", rpe == null) {
                            set.rpe = null
                            rpe = null
                            saveWorkoutChanges(workout)
                        }) + quickRpe.map { value ->
                            SegOption(value, rpe == value) {
                                set.rpe = value
                                rpe = value
                                saveWorkoutChanges(workout)
                            }
                        } + SegOption(if (rpe != null && rpe !in quickRpe) rpe!! else "상세", rpe != null && rpe !in quickRpe) {
                            showChoiceSheet("운동 강도 (RPE)", selected = rpe ?: "선택 안 함", items = listOf("선택 안 함" to "", "10" to "더는 못 해요", "9.5" to "1개 정도 더 할 수 있을 것 같아요", "9" to "확실히 1개 더 할 수 있어요", "8.5" to "아마 2개 더 할 수 있어요", "8" to "확실히 2개 더 할 수 있어요", "7" to "꽤 할 만했어요", "6" to "웜업 정도예요")) { value ->
                                set.rpe = value.takeUnless { it == "선택 안 함" }
                                saveWorkoutChanges(workout)
                            }
                        },
                    ),
                ),
                actions = listOf(
                    SheetAction("이전에 세트 추가", false) {
                        dismiss()
                        routineExercise.sets.add(setIndex, PlannedSet(set.weightKg, set.targetReps))
                        saveWorkoutChanges(workout)
                    },
                    SheetAction("세트 삭제", true) {
                        dismiss()
                        if (routineExercise.sets.size > 1) routineExercise.sets.removeAt(setIndex)
                        workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceAtMost(routineExercise.sets.lastIndex)
                        saveWorkoutChanges(workout)
                    },
                ),
            )
        }
    }

    private fun showChoiceSheet(title: String, items: List<Pair<String, String>>, selected: String? = null, onSelect: (String) -> Unit) {
        showSheet { dismiss ->
            ChoiceSheet(title, items, selected) { value ->
                dismiss()
                onSelect(value)
            }
        }
    }

    private fun nextWorkoutTarget(workout: ActiveWorkout): PlannedSetSnapshot? =
        WorkoutSessionReducer.nextPendingSet(workoutSnapshot(workout))

    private fun showRest(seconds: Int, synchronizedEndAtMs: Long? = null) {
        val workout = activeWorkout ?: run {
            Log.e(EVENT_TAG, "rest transition ignored: active workout missing")
            return
        }
        val wasActiveScreen = currentScreen == Screen.ACTIVE
        currentScreen = Screen.ACTIVE
        activeRestEndAtMs = synchronizedEndAtMs
            ?: (System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1_000L)
        activeRestStartAtMs = activeRestEndAtMs - seconds.coerceAtLeast(0) * 1_000L
        workout.phase = WorkoutPhase.RESTING
        lastRestCueSecond = -1
        if (!persistActiveWorkout(workout)) return
        try {
            showActiveWorkout(forceRebuild = !wasActiveScreen)
        } catch (error: Exception) {
            reportWorkoutUiError("enter_rest", error)
            // Session/rest state was committed before rendering. A clean rebuild
            // is safer than letting an Activity-only UI exception close the app.
            runCatching { showActiveWorkout(forceRebuild = true) }
                .onFailure { reportWorkoutUiError("enter_rest_rebuild", it) }
        }
    }

    private fun reportWorkoutUiError(stage: String, error: Throwable) {
        Log.e(EVENT_TAG, "workout UI failure stage=$stage", error)
        getSharedPreferences("wear-sync", MODE_PRIVATE).edit()
            .putString("lastUiError", "$stage:${error.message}")
            .putLong("lastUiErrorAt", System.currentTimeMillis())
            .apply()
    }

    private fun showMyMountainDecision() {
        stopTicker()
        val completedWorkout = activeWorkout
        completedWorkout?.let { it.analyses.putAll(SetAnalysisStore(this).loadForSession(it.sessionId)) }
        lastWorkoutResult = completedWorkout?.let { workout ->
            WorkoutResultUi(
                sessionId = workout.sessionId,
                title = workout.routine.name,
                durationMinutes = (workoutElapsedSeconds(workout) / 60L).toInt(),
                completedSets = workout.routine.exercises.sumOf { exercise -> exercise.sets.count { it.completed } },
                analyses = workout.analyses.toMutableMap(),
            )
        }
        lastWorkoutResult?.let(::persistLastWorkoutResult)
        if (completedWorkout != null) {
            if (!persistActiveWorkout(completedWorkout)) return
            if (!runCatching {
                    recordHistory(completedWorkout)
                    recordWorkoutLog(completedWorkout)
                }.onFailure {
                    Log.e(EVENT_TAG, "workout recording failed; active session retained", it)
                    Toast.makeText(this, "운동 기록 저장 실패 · 앱을 다시 열어 주세요", Toast.LENGTH_LONG).show()
                }.isSuccess) return
            sendWear(WearSync.PATH_COMMAND, WearSync.command("COMPLETE", completedWorkout.sessionId, "", 0, 0))
            if (!sessionStore.clearActive(completedWorkout.sessionId)) return
        }
        activeWorkout = null
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        pausedRestRemainingMs = 0L
        showSheet { dismiss ->
            BigChoiceSheet(
                eyebrow = "웨이트 완료", eyebrowColor = KColors.Accent,
                title = "마이마운틴도 할까요?",
                body = "오늘 컨디션에 따라 정해요.\n건너뛰어도 루틴은 완료로 기록돼요.",
                bodyColor = KColors.Muted,
                cancel = "건너뛰기", confirm = "시작",
                dismiss = dismiss,
                onCancel = { showWorkoutResult(lastWorkoutResult) },
                onConfirm = { showMyMountain() },
                filledButtons = true,
            )
        }
    }

    private fun showMyMountain() {
        currentScreen = Screen.MY_MOUNTAIN
        showStandalone(ComposeView(this).apply {
            setContent {
                MyMountainScreen(
                    back = { onBackPressed() },
                    start = { Toast.makeText(this@MainActivity, "워치가 연결되면 심박수와 함께 기록돼요", Toast.LENGTH_LONG).show() },
                    finish = { showRecords() },
                )
            }
        })
    }

    private fun showExerciseRecord(
        routine: WorkoutRoutine,
        index: Int,
        active: ActiveWorkout?,
        onReturn: () -> Unit,
    ) {
        val routineExercise = routine.exercises.getOrNull(index) ?: return
        stopTicker()
        currentScreen = Screen.EXERCISE_RECORD
        returnFromExerciseRecord = onReturn

        val record = ExerciseRecordUi(
            name = routineExercise.exercise.name,
            muscles = routineExercise.exercise.muscles,
            targetTotal = routineExercise.sets.sumOf { it.targetReps },
            performed = active?.performedRepsByExercise?.get(routineExercise.exercise.id) ?: 0,
            sets = routineExercise.sets.mapIndexed { setIndex, set ->
                RecordSetUi(
                    setIndex,
                    set.targetReps,
                    active != null && setIndex < active.completedSets && active.routine === routine && active.exerciseIndex > index,
                )
            },
            restSeconds = routineExercise.restSeconds,
            recent = history.recent(routineExercise.exercise.id, 4).map { log ->
                RecentLogUi(
                    date = LocalDate.ofEpochDay(log.epochDay).let { "${it.monthValue}월 ${it.dayOfMonth}일" },
                    lines = log.sets.mapIndexed { i, set ->
                        val weight = set.weightKg
                        "${i + 1}  ${if (weight == null) "" else cleanWeight(weight) + "kg × "}${set.reps}회"
                    }.take(5).joinToString("\n"),
                )
            },
            best = buildList {
                history.heaviestSet(routineExercise.exercise.id)?.let { (day, set) ->
                    add("최고 중량" to "${cleanWeight(set.weightKg!!)}kg × ${set.reps}회")
                }
                history.bestVolume(routineExercise.exercise.id)?.let { add("최고 볼륨" to "%,dkg".format(it.volumeKg.toInt())) }
            },
        )
        showStandalone(ComposeView(this).apply {
            setContent {
                ExerciseRecordScreen(
                    record,
                    back = onReturn,
                    openInfo = { showExerciseInfo(routineExercise.exercise) { showExerciseRecord(routine, index, active, onReturn) } },
                    animation = { height -> exerciseAnimationView(routineExercise.exercise, height) },
                )
            }
        })
    }

    private fun showExercisePreview(routine: WorkoutRoutine, index: Int) {
        if (index !in routine.exercises.indices) return
        if (activeWorkout == null) prefillProgression(routine)
        showActiveWorkout(ActiveWorkout(routine = routine, exerciseIndex = index))
    }

    private fun anatomyPlaceholder(heightDp: Int): View = FrameLayout(this).apply {
        minimumHeight = dp(heightDp)
        background = rounded(Color.rgb(30, 29, 27), 20)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    private fun exerciseAnimationView(
        exercise: ExerciseDefinition,
        heightDp: Int,
        compact: Boolean = false,
    ): View {
        val resName = "exercise_${exercise.id}"
        val animationRes = resources.getIdentifier(resName, "drawable", packageName).takeIf { it != 0 }
            ?: return anatomyPlaceholder(heightDp)
        return runCatching {
            // Decode per view: AnimatedImageDrawables that share a constant state also
            // share one native decoder, and two running at once (pager neighbours)
            // abort the process.
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, animationRes))
            val radiusDp = if (compact) 12 else 20
            FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(heightDp),
                )
                background = rounded(Color.WHITE, radiusDp)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                contentDescription = "${exercise.name} 운동 애니메이션"
                addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(drawable)
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))

                (drawable as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            }
        }.getOrElse {
            anatomyPlaceholder(heightDp)
        }
    }

    private fun detailTopBar(title: String): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("‹", 48f, white).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onBackPressed() }
        }, fixedParams(54, 58))
        addView(label(title, 25f, white, bold = true), weightParams(1f, -2))
        addView(label("•••", 18f, muted, bold = true).apply { gravity = Gravity.CENTER }, fixedParams(54, 58))
    }

    private fun showExerciseMenu(
        routine: WorkoutRoutine,
        index: Int,
        active: ActiveWorkout?,
        onReturn: () -> Unit,
    ) {
        val exercise = routine.exercises.getOrNull(index)?.exercise ?: return
        showSheet { dismiss ->
            SheetOptions(
                exercise.name,
                listOf(
                    SheetItem(KIcon.Play, "운동 애니메이션") { dismiss(); showExerciseInfo(exercise, onReturn) },
                    SheetItem(KIcon.Info, "운동 정보", featured = true) { dismiss(); showExerciseInfo(exercise, onReturn) },
                    SheetItem(KIcon.Trash, "운동 삭제", destructive = true) { dismiss(); confirmRemoveExercise(routine, index, active, onReturn) },
                    SheetItem(KIcon.Swap, "운동 대체", featured = true) { dismiss(); showExerciseAlternatives(routine, index, onReturn) },
                ),
            )
        }
    }

    private fun exerciseDescription(exercise: ExerciseDefinition): String =
        if (exercise.id == "pull_up") {
            "● 팁\n\n풀업은 등, 어깨, 팔 근육을 동시에 강화하는 운동이에요. 그립의 넓이와 손바닥 방향에 따라 자극되는 근육의 비중이 달라져요. 처음부터 반복 수를 늘리기보다 매 반복의 시작과 끝 자세를 일정하게 유지하세요.\n\n● 설명\n\n- 준비\n\n1. 바를 어깨너비보다 약간 넓게 오버핸드 그립으로 잡으세요.\n2. 팔을 완전히 뻗은 상태에서 어깨를 귀에서 멀어지게 내리고, 복부와 둔근에 가볍게 힘을 주세요.\n3. 다리를 앞뒤로 흔들지 말고 발목을 겹쳐 몸통을 하나의 단단한 덩어리처럼 유지하세요.\n\n- 움직임\n\n1. 가슴을 바 쪽으로 끌어올리면서 팔꿈치를 아래·뒤 방향으로 당기세요.\n2. 턱만 내미는 대신 가슴이 올라가도록 움직이고, 어깨가 으쓱하지 않게 유지하세요.\n3. 위에서 잠깐 멈춘 뒤 팔꿈치를 천천히 펴며 시작 자세로 돌아가세요. 내려가는 구간까지 통제해야 1회가 완성돼요.\n\n- 호흡법\n\n몸을 끌어올릴 때 숨을 내쉬고, 내려오면서 숨을 들이마시세요. 반복 중 숨을 오래 참지 말고 일정한 호흡을 유지하세요.\n\n● 주의사항\n\n1. 어깨·팔꿈치·손목에 통증이 느껴지면 즉시 중단하세요.\n2. 반동으로 몸을 차거나 허리를 과하게 젖히지 마세요.\n3. 가동범위가 무너지면 반복 수를 줄이거나 어시스티드 머신·밴드로 난도를 낮추세요."
        } else {
            "● 팁\n\n${exercise.name}은 ${exercise.muscles} 근육을 사용하는 운동이에요. 무게를 올리는 것보다 몸통을 고정하고 매 반복의 시작·끝 자세를 일정하게 유지하는 것이 우선이에요. 목표 근육에 힘이 들어오는지 느끼면서 호흡과 속도를 일정하게 맞추세요.\n\n● 설명\n\n- 준비\n\n1. 기구와 그립을 편하게 잡고 발·골반·몸통을 안정시키세요.\n2. 어깨를 으쓱하지 말고 관절이 자연스럽게 움직이는 위치를 찾으세요.\n3. 가벼운 무게로 1세트를 진행해 통증과 가동범위를 확인하세요.\n\n- 움직임\n\n1. 목표 근육으로 힘을 보내며 1~2초에 걸쳐 들어 올리거나 당기세요.\n2. 수축 지점에서 반동 없이 잠깐 멈추고, 무게를 놓치지 않으며 천천히 돌아오세요.\n3. 몸통이 흔들리거나 관절이 먼저 움직이면 무게와 반복 수를 낮추고 궤적을 다시 맞추세요.\n4. 마지막 반복까지 같은 자세를 유지할 수 있을 때만 다음 세트의 무게를 올리세요.\n\n- 호흡법\n\n힘을 쓰는 구간에 숨을 내쉬고, 시작 위치로 돌아오며 들이마시세요. 무거울 때도 숨을 오래 참지 말고 복부 긴장과 호흡을 함께 유지하세요.\n\n● 주의사항\n\n1. 날카로운 통증, 저림 또는 관절 불편감이 느껴지면 즉시 중단하세요.\n2. 빠른 반동, 과한 무게, 허리 꺾기로 반복 수를 억지로 채우지 마세요.\n3. 자세가 무너지기 시작하면 세트를 끝내고 다음 세트는 무게나 범위를 조절하세요."
        }

    private fun showExerciseInfo(exercise: ExerciseDefinition, onReturn: () -> Unit) {
        stopTicker()
        currentScreen = Screen.EXERCISE_INFO
        returnFromExerciseInfo = onReturn

        val matching = routines.filter { it.exercises.any { item -> item.exercise.id == exercise.id } }
        val plannedSets = matching.sumOf { routine -> routine.exercises.filter { it.exercise.id == exercise.id }.sumOf { it.sets.size } }
        val used = routines.flatMap { it.exercises }.filter { it.exercise.id == exercise.id }
        val averageRest = used.map { it.restSeconds }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        val info = ExerciseInfoUi(
            name = exercise.name,
            muscles = exercise.muscles,
            description = exerciseDescription(exercise) + "\n\n" +
                dev.kyungmin.kcoach.core.ExerciseMeasurementContracts.explanation(exercise.id, exercise.name),
            myRecord = listOf(
                "● 나의 기록" to "이 운동이 포함된 루틴 ${matching.size}개\n계획된 총 세트 ${plannedSets}세트\n\n최근 루틴\n${matching.firstOrNull()?.name ?: "아직 기록 없음"}",
                "● 최근 목표" to (matching.firstOrNull()?.exercises?.firstOrNull { it.exercise.id == exercise.id }?.sets?.firstOrNull()?.let { set ->
                    "${set.weightKg?.let { cleanWeight(it) + "kg" } ?: "체중"} · ${set.targetReps}회"
                } ?: "기록이 쌓이면 표시돼요."),
            ),
            stats = listOf(
                "${used.size}회" to "루틴 포함",
                "${used.sumOf { it.sets.size }}세트" to "계획 세트",
                "${averageRest}초" to "평균 휴식",
                exercise.equipment to "기구",
            ),
            analysisNote = if (ExerciseTrackingProfiles.resolve(exercise.id, exercise.name).mode == TrackingMode.MANUAL_REQUIRED)
                "실제 횟수를 폰이나 워치에서 입력해 주세요. 손목 신호로 횟수·다리 자세를 판정하지 않아요."
                else "손목에서 관측한 움직임과 반복을 추정해요. 영상 확인과 별도 검증 전에는 자세의 정답으로 취급하지 않아요.",
        )
        showStandalone(ComposeView(this).apply {
            setContent {
                ExerciseInfoScreen(info, back = onReturn) { height -> exerciseAnimationView(exercise, height) }
            }
        })
    }

    private fun moveRoutineExercise(
        routine: WorkoutRoutine,
        from: Int,
        to: Int,
        active: ActiveWorkout?,
        announce: Boolean = true,
    ) {
        if (from !in routine.exercises.indices || to !in routine.exercises.indices) return
        val item = routine.exercises.removeAt(from)
        routine.exercises.add(to, item)
        active?.let {
            if (it.routine === routine) {
                it.exerciseIndex = adjustedIndexAfterMove(it.exerciseIndex, from, to)
            }
        }
        if (announce) Toast.makeText(this, "순서를 바꿨어요", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveExercise(routine: WorkoutRoutine, index: Int, active: ActiveWorkout?, onReturn: () -> Unit) {
        val exercise = routine.exercises.getOrNull(index)?.exercise ?: return
        showPopup { dismiss ->
            ConfirmDialog("${exercise.name} 삭제", "이 루틴에서 운동을 삭제할까요?", "취소", "삭제", dismiss) {
                routine.exercises.removeAt(index)
                active?.let {
                    if (it.routine === routine) {
                        val removedCurrent = it.exerciseIndex == index
                        if (it.exerciseIndex > index) it.exerciseIndex -= 1
                        else if (it.exerciseIndex >= routine.exercises.size) it.exerciseIndex = max(0, routine.exercises.lastIndex)
                        if (removedCurrent) it.setIndex = 0
                    }
                }
                if (routines.any { it === routine }) saveRoutineLibrary()
                Toast.makeText(this, "운동을 삭제했어요", Toast.LENGTH_SHORT).show()
                onReturn()
            }
        }
    }

    private fun showExerciseAlternatives(routine: WorkoutRoutine, index: Int, onReturn: () -> Unit) {
        val current = routine.exercises.getOrNull(index)?.exercise ?: return
        fun ExerciseDefinition.candidate() = SubstituteCandidate(
            id, muscles.split(", "), bodyCategory, equipment, pattern, favorite,
        )
        val byId = WorkoutData.catalog.associateBy { it.id }
        val alternatives = ExerciseSubstitutes
            .rank(current.candidate(), WorkoutData.catalog.map { it.candidate() })
            .mapNotNull { byId[it.id] }
        val labels = alternatives.map { "${it.name}\n${it.muscles} · ${it.equipment}" }
        showPopup { dismiss ->
            PickDialog("운동 대체\n${current.name}", labels, dismiss) { which ->
                routine.exercises[index] = routine.exercises[index].copy(exercise = alternatives[which])
                if (routines.any { it === routine }) saveRoutineLibrary()
                Toast.makeText(this, "${alternatives[which].name}(으)로 바꿨어요", Toast.LENGTH_SHORT).show()
                onReturn()
            }
        }
    }

    private fun showAddSheet() {
        showSheet { dismiss ->
            SheetOptions(
                "새 루틴",
                listOf(
                    SheetItem(KIcon.Plus, "나만의 루틴 추가") { dismiss(); showNewRoutineDialog() },
                    SheetItem(KIcon.Reload, "기록에서 불러오기") { dismiss(); showRecords() },
                    SheetItem(KIcon.Records, "운동 계획 추가") { dismiss(); showNewRoutineDialog() },
                ),
            )
        }
    }

    private fun customExercises() = WorkoutData.catalog.filter { it.id !in WorkoutData.builtInExerciseIds }

    private fun restoreRoutineLibrary() {
        routineLibraryStore.load().onSuccess { saved ->
            if (saved != null) {
                routines.clear()
                routines.addAll(saved.routines)
                WorkoutData.catalog.removeAll { it.id !in WorkoutData.builtInExerciseIds }
                WorkoutData.catalog.addAll(saved.customExercises)
                // Keep inline definitions usable too, without inventing a new ID.
                saved.routines.flatMap { it.exercises }.forEach { item ->
                    if (WorkoutData.catalog.none { it.id == item.exercise.id }) WorkoutData.catalog += item.exercise
                }
            }
        }.onFailure {
            Log.e(EVENT_TAG, "routine library load failed; file retained", it)
            Toast.makeText(this, "루틴을 불러오지 못했어요. 기존 저장 파일을 유지하고 덮어쓰지 않아요.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveRoutineLibrary(
        plans: List<WorkoutRoutine> = routines,
        customExercises: List<ExerciseDefinition> = customExercises(),
    ): Boolean = routineLibraryStore.save(RoutineLibrary(plans, customExercises)).onFailure {
        Log.e(EVENT_TAG, "routine library save failed; previous file retained", it)
        Toast.makeText(this, "루틴·운동을 저장하지 못했어요. 이전 저장 데이터는 유지돼요.", Toast.LENGTH_LONG).show()
    }.isSuccess

    private fun showNewRoutineDialog() {
        showPopup { dismiss ->
            InputDialog("나만의 루틴 추가", "루틴 이름", dismiss) { typed ->
                val routine = WorkoutRoutine(
                    id = "custom_${System.currentTimeMillis()}",
                    name = typed.ifBlank { "새 루틴" },
                    muscles = "운동을 추가해 주세요",
                    lastPerformed = "기록 없음",
                    exercises = mutableListOf(),
                )
                if (!saveRoutineLibrary(routines + routine)) return@InputDialog
                routines += routine
                selectedRoutine = routine
                showRoutine(routine)
            }
        }
    }

    private fun showCustomExerciseDialog(onSelect: (ExerciseDefinition) -> Unit, onReturn: () -> Unit) {
        showPopup { dismiss ->
            InputDialog("커스텀 운동 추가", "운동 이름", dismiss) { name ->
                if (name.isNotEmpty()) {
                    val custom = ExerciseDefinition(
                        id = "custom_${System.currentTimeMillis()}",
                        name = name,
                        muscles = "사용자 정의",
                        bodyCategory = "전체",
                        equipment = "커스텀",
                    )
                    if (!saveRoutineLibrary(customExercises = customExercises() + custom)) return@InputDialog
                    WorkoutData.catalog += custom
                    onSelect(custom)
                    onReturn()
                }
            }
        }
    }

    private fun showRoutineMenu(routine: WorkoutRoutine) {
        showSheet { dismiss ->
            SheetOptions(
                routine.name,
                listOf(
                    SheetItem(KIcon.Menu, "상세 보기", featured = true) { dismiss(); showRoutine(routine) },
                    SheetItem(KIcon.Plus, "운동 추가", featured = true) {
                        dismiss()
                        openLibrary(
                            onSelect = {
                                routine.exercises += RoutineExercise(it, mutableListOf(PlannedSet(null, 10)))
                                if (routines.any { saved -> saved === routine }) saveRoutineLibrary()
                            },
                            onReturn = { showRoutine(routine) },
                        )
                    },
                ),
            )
        }
    }

    private fun confirmStopWorkout() {
        val workout = activeWorkout
        val completed = workout?.routine?.exercises?.sumOf { it.sets.completedWorkSetCount() } ?: 0
        showPopup { dismiss ->
            if (completed == 0) {
                ConfirmDialog("운동을 종료할까요?", "완료한 세트가 없어서 저장할 기록이 없어요.", "계속 운동", "종료", dismiss) { endWorkout(save = false) }
            } else {
                StopWorkoutDialog("운동을 종료할까요?", "완료한 ${completed}세트를 기록으로 저장할 수 있어요.", dismiss,
                    onSave = { endWorkout(save = true) }, onDiscard = { endWorkout(save = false) })
            }
        }
    }

    /** Ends the running workout from inside the app; [save] keeps the finished sets, otherwise everything is dropped. */
    private fun endWorkout(save: Boolean) {
        val stoppedWorkout = activeWorkout
        if (stoppedWorkout != null) {
            if (save && !persistActiveWorkout(stoppedWorkout)) return
            if (WorkoutTermination(this).stop(saveCompleted = save).onFailure {
                    Log.e(EVENT_TAG, "workout stop failed; journal retained", it)
                }.isFailure) return
            PhoneWorkoutService.retryPending(this)
        }
        activeWorkout = null
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        pausedRestRemainingMs = 0L
        showHome()
    }

    private fun showScaffold(body: View, actions: View?, selected: RootTab) {
        val switchingTab = currentScreen == Screen.ROOT && root.childCount > 0
        root.removeAllViews()
        val column = vertical()
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions?.let { column.addView(it) }
        // Room under the content for the floating bar, which is drawn by the app-level tab bar view.
        column.addView(View(this), matchParams(86))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        navSelected.intValue = selected.ordinal
        navVisible.value = true
        if (switchingTab) {
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(Motion.UI_MS.toLong()).setInterpolator(uiInterpolator).start()
        }
    }

    private fun showDetail(body: View, actions: View?, animateFromRight: Boolean = false) {
        navVisible.value = false
        val frame = FrameLayout(this)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        actions?.let { frame.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        if (animateFromRight && root.childCount > 0) {
            if (detailTransitionRunning) {
                root.removeAllViews()
                root.addView(frame, FrameLayout.LayoutParams(-1, -1))
                detailTransitionRunning = false
                return
            }
            detailTransitionRunning = true
            val previous = root.getChildAt(root.childCount - 1)
            val widthPx = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            previous.animate().cancel()
            root.addView(frame, FrameLayout.LayoutParams(-1, -1))
            frame.translationX = widthPx.toFloat()
            frame.post {
                if (frame.parent !== root) return@post
                previous.animate()
                    .translationX(-widthPx.toFloat())
                    .setDuration(Motion.PANEL_MS.toLong())
                    .setInterpolator(panelInterpolator)
                    .start()
                frame.animate()
                    .translationX(0f)
                    .setDuration(Motion.PANEL_MS.toLong())
                    .setInterpolator(panelInterpolator)
                    .withEndAction {
                        if (previous.parent === root) root.removeView(previous)
                        frame.translationX = 0f
                        frame.alpha = 1f
                        detailTransitionRunning = false
                    }
                    .start()
            }
            return
        }
        detailTransitionRunning = false
        root.removeAllViews()
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showStandalone(body: View, bottomOverlay: View? = null) {
        navVisible.value = false
        root.removeAllViews()
        if (bottomOverlay == null) {
            root.addView(body, FrameLayout.LayoutParams(-1, -1))
            return
        }
        if (body is ScrollView) {
            body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, dp(62))
            body.clipToPadding = false
        }
        val frame = FrameLayout(this)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        frame.addView(bottomOverlay, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(8))
        })
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    private val panelInterpolator = PathInterpolator(0.785f, 0.135f, 0.15f, 0.86f)
    private val uiInterpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    private val navSelected = mutableIntStateOf(0)
    private val navVisible = mutableStateOf(false)

    /** One floating tab bar for the whole app; it lives above every screen and only shows on the tab screens. */
    private fun tabBarView(): View = ComposeView(this).apply {
        setContent {
            FloatingTabBar(navSelected.intValue, navVisible.value) { index ->
                when (RootTab.values()[index]) {
                    RootTab.HOME -> showHome()
                    RootTab.RECORDS -> showRecords()
                    RootTab.STATS -> showStats()
                    RootTab.CALIBRATION -> showCalibration()
                    RootTab.MENU -> showMenu()
                }
            }
        }
    }

    private fun progressBar(index: Int, total: Int): View = row().apply {
        repeat(total) { item ->
            addView(View(this@MainActivity).apply {
                background = rounded(if (item <= index) amberDark else surfaceRaised, 3)
            }, weightParams(1f, 5).apply {
                marginEnd = if (item == total - 1) 0 else dp(5)
            })
        }
    }

    private fun primaryButton(
        text: String,
        backgroundColor: Int,
        textColor: Int = white,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 18f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        backgroundTintList = null
        background = rounded(backgroundColor, 18)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun horizontalCard() = row().apply { background = rounded(surface, 22) }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun space(sizeDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun weightSpace() = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun Int.toDrawable() = android.graphics.drawable.ColorDrawable(this)

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    private fun fixedParams(widthDp: Int, heightDp: Int) =
        LinearLayout.LayoutParams(if (widthDp < 0) widthDp else dp(widthDp), if (heightDp < 0) heightDp else dp(heightDp))

    private fun matchParams(heightDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (heightDp < 0) heightDp else dp(heightDp))

    private fun weightParams(weight: Float, heightDp: Int) =
        LinearLayout.LayoutParams(0, if (heightDp <= 0) heightDp else dp(heightDp), weight)

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(54)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun cleanWeight(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private fun timeText(totalSeconds: Long): String =
        "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

    private fun stopTicker() {
        ticker?.let(handler::removeCallbacks)
        ticker = null
    }

    private fun sendWear(path: String, payload: String) {
        PhoneWorkoutService.send(this, path, payload)
    }

    private fun workoutSnapshot(workout: ActiveWorkout): WorkoutSnapshot = WorkoutSnapshot(
        sessionId = workout.sessionId,
        routineId = workout.routine.id,
        routineName = workout.routine.name,
        exerciseIndex = workout.exerciseIndex,
        setIndex = workout.setIndex,
        plannedSets = workout.routine.exercises.flatMapIndexed { exerciseIndex, exercise ->
            exercise.sets.mapIndexed { setIndex, set ->
                PlannedSetSnapshot(
                    exerciseIndex = exerciseIndex,
                    exerciseId = exercise.exercise.id,
                    setIndex = setIndex,
                    targetReps = set.targetReps,
                    weightKg = set.weightKg,
                    completed = set.completed,
                    setType = set.setType.storageValue,
                    exerciseName = exercise.exercise.name,
                    restSeconds = set.restSeconds ?: exercise.restSeconds,
                    exerciseRestSeconds = exercise.restSeconds,
                    restOverrideSeconds = set.restSeconds,
                    performedReps = set.performedReps,
                    repSource = set.repSource,
                    exerciseMuscles = exercise.exercise.muscles,
                    bodyCategory = exercise.exercise.bodyCategory,
                    equipment = exercise.exercise.equipment,
                    pattern = exercise.exercise.pattern,
                    family = exercise.exercise.family,
                    gripType = set.gripType,
                    rpe = set.rpe,
                    setId = set.setId,
                    attemptId = set.attemptId,
                    identityRequired = set.identityRequired,
                )
            }
        },
        performedReps = workout.performedRepsByExercise.toMap(),
        phase = workout.phase,
        restEndAtMs = activeRestEndAtMs,
        pausedRestRemainingMs = pausedRestRemainingMs,
        paused = workout.paused,
        startedAtMs = workout.startedAtMs,
        pausedSinceMs = workout.pausedSinceMs,
        totalPausedMs = workout.totalPausedMs,
        lastSequence = workout.lastSequence,
        lastCompletedSetKey = workout.lastCompletedSetKey,
        captureStates = workout.captureStates.toMap(),
        restRev = SessionStore(this).loadActive().getOrNull()?.takeIf { it.sessionId == workout.sessionId }?.restRev ?: 0,
        watchRestSeq = SessionStore(this).loadActive().getOrNull()?.takeIf { it.sessionId == workout.sessionId }?.watchRestSeq ?: 0,
    )

    private fun persistActiveWorkout(workout: ActiveWorkout): Boolean {
        if (previewWorkout === workout && activeWorkout !== workout) {
            return if (routines.any { it === workout.routine }) saveRoutineLibrary() else true
        }
        val previous = SessionStore(this).loadActive().getOrNull()?.takeIf { it.sessionId == workout.sessionId }
        val oldLayout = previous?.plannedSets?.map { Triple(it.setId.ifBlank { SetIdentity.migratedId(workout.sessionId, it.exerciseIndex, it.setIndex) }, it.exerciseId, it.exerciseIndex to it.setIndex) }
        val newLayout = workout.routine.exercises.flatMapIndexed { ei, item -> item.sets.mapIndexed { si, set -> Triple(set.setId, item.exercise.id, ei to si) } }
        if (oldLayout != null && oldLayout != newLayout) workout.routine.exercises.forEach { item -> item.sets.forEach { it.identityRequired = true } }
        workout.performedRepsByExercise.clear()
        workout.routine.exercises.groupBy { it.exercise.id }.forEach { (id, items) ->
            workout.performedRepsByExercise[id] = items.sumOf { item -> item.sets.filter { it.completed }.sumOf { it.performedReps ?: 0 } }
        }
        val snapshot = workoutSnapshot(workout)
        val saved = runCatching { sessionStore.saveActive(snapshot) }.getOrDefault(false)
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                marker = if (saved) KCoachEventMarker.SNAPSHOT_SAVED else KCoachEventMarker.EVENT_FAILED,
                sessionId = workout.sessionId,
                exerciseId = workout.routine.exercises.getOrNull(workout.exerciseIndex)?.exercise?.id.orEmpty(),
                setIndex = workout.setIndex,
                detail = if (saved) "" else "snapshot_save_failed",
            ),
        )
        if (!saved) {
            sessionStore.loadActive().getOrNull()?.takeIf { it.sessionId == workout.sessionId }?.let {
                applySnapshotToWorkout(workout, it)
                bumpWorkoutUi()
            }
            activeLiveStatus?.apply {
                text = "세션 저장에 실패했어요 · 워치 명령을 보내지 않았어요"
                setTextColor(amber)
            }
        }
        return saved
    }

    private fun restoreActiveWorkout(): ActiveWorkout? {
        if (WorkoutTermination(this).pending()) return null
        val snapshot = sessionStore.loadActive().getOrElse { error ->
            Log.e(EVENT_TAG, "active snapshot load failed", error)
            return null
        } ?: return null
        val routine = runCatching { restoreWorkoutRoutine(snapshot, routines.firstOrNull { it.id == snapshot.routineId }) }
            .getOrElse { Log.e(EVENT_TAG, "active routine recovery failed", it); return null }
        activeRestEndAtMs = snapshot.restEndAtMs
        pausedRestRemainingMs = snapshot.pausedRestRemainingMs
        if (snapshot.phase == WorkoutPhase.RESTING && snapshot.restEndAtMs > 0L) {
            val restSeconds = routine.exercises.getOrNull(snapshot.exerciseIndex)?.restSeconds ?: 0
            activeRestStartAtMs = snapshot.restEndAtMs - restSeconds * 1_000L
        }
        return ActiveWorkout(
            routine = routine,
            startedAtMs = snapshot.startedAtMs,
            sessionId = snapshot.sessionId,
            exerciseIndex = snapshot.exerciseIndex.coerceAtMost(routine.exercises.size),
            setIndex = snapshot.setIndex,
            performedRepsByExercise = snapshot.performedReps.toMutableMap(),
            paused = snapshot.paused,
            pausedSinceMs = snapshot.pausedSinceMs,
            totalPausedMs = snapshot.totalPausedMs,
            phase = snapshot.phase,
            lastSequence = snapshot.lastSequence,
            lastCompletedSetKey = snapshot.lastCompletedSetKey,
            captureStates = snapshot.captureStates.toMutableMap(),
            analyses = SetAnalysisStore(this).loadForSession(snapshot.sessionId).toMutableMap(),
        )
    }

    private fun applySnapshotToWorkout(workout: ActiveWorkout, snapshot: WorkoutSnapshot) {
        val currentLayout = workout.routine.exercises.flatMapIndexed { ei, item -> item.sets.mapIndexed { si, set ->
            Triple(set.setId, item.exercise.id, ei to si)
        } }
        val savedLayout = snapshot.plannedSets.map { Triple(it.setId.ifBlank {
            SetIdentity.migratedId(snapshot.sessionId, it.exerciseIndex, it.setIndex)
        }, it.exerciseId, it.exerciseIndex to it.setIndex) }
        if (currentLayout != savedLayout) {
            val restored = restoreWorkoutRoutine(snapshot, workout.routine)
            workout.routine.exercises.clear()
            workout.routine.exercises.addAll(restored.exercises)
            workout.setIndexByExercise.clear()
            workout.selectedSetIndexByExercise.clear()
            workout.scrollYByExercise.clear()
            workout.analyses.clear()
        }
        snapshot.plannedSets.forEach { savedSet ->
            val exercise = workout.routine.exercises.getOrNull(savedSet.exerciseIndex) ?: return@forEach
            if (exercise.exercise.id != savedSet.exerciseId) return@forEach
            if (savedSet.exerciseName.isNotBlank()) exercise.restSeconds = savedSet.exerciseRestSeconds
            exercise.sets.getOrNull(savedSet.setIndex)?.apply {
                targetReps = savedSet.targetReps
                weightKg = savedSet.weightKg
                completed = savedSet.completed
                performedReps = savedSet.performedReps
                repSource = savedSet.repSource
                gripType = savedSet.gripType
                rpe = savedSet.rpe
                attemptId = savedSet.attemptId.ifBlank { SetIdentity.migratedId(snapshot.sessionId, savedSet.exerciseIndex, savedSet.setIndex, true) }
                identityRequired = savedSet.identityRequired
                setType = SetType.fromStorage(savedSet.setType)
                if (savedSet.exerciseName.isNotBlank()) restSeconds = savedSet.restOverrideSeconds
            }
        }
        workout.exerciseIndex = snapshot.exerciseIndex.coerceAtMost(workout.routine.exercises.size)
        if (snapshot.restEndAtMs <= 0L && snapshot.pausedRestRemainingMs <= 0L || currentLayout != savedLayout) {
            workout.restingPageIndex = null
        }
        workout.setIndex = snapshot.setIndex
        workout.performedRepsByExercise.clear()
        workout.performedRepsByExercise.putAll(snapshot.performedReps)
        workout.paused = snapshot.paused
        workout.pausedSinceMs = snapshot.pausedSinceMs
        workout.totalPausedMs = snapshot.totalPausedMs
        workout.phase = snapshot.phase
        activeRestStartAtMs = if (snapshot.restEndAtMs > 0L) {
            val completedRest = snapshot.plannedSets.firstOrNull {
                SetIdentity.completionKey(snapshot.sessionId, it) == snapshot.lastCompletedSetKey
            }?.restSeconds ?: 90
            snapshot.restEndAtMs - completedRest * 1_000L
        } else 0L
        workout.lastSequence = snapshot.lastSequence
        workout.lastCompletedSetKey = snapshot.lastCompletedSetKey
        workout.captureStates.clear()
        workout.captureStates.putAll(snapshot.captureStates)
        activeRestEndAtMs = snapshot.restEndAtMs
        pausedRestRemainingMs = snapshot.pausedRestRemainingMs
    }

    companion object {
        private const val EVENT_TAG = "KCoachEvent"
        private const val RECORD_BANNER_MODEL_MS = 6_000L
        private const val SPLASH_MIN_MS = 300L
        private const val SPLASH_AFTER_UPDATE_MS = 1_200L
        private const val UPDATE_NOTICE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        private const val STATE_VISIBLE_RESULT = "visibleFinishedWorkoutResult"
        private const val STATE_ROOT_TAB = "visibleRootTab"
        private const val STATE_CALIBRATION_EXERCISE = "selectedCalibrationExercise"
        private const val STATE_HANDLED_RESULT_REQUESTS = "handledWorkoutResultRequests"
        private const val WATCH_PANEL_CONNECTION = "watch-panel-connection"
        private const val WATCH_PANEL_TELEMETRY = "watch-panel-telemetry"
        private const val CALIBRATION_SAVE_TIMEOUT_MS = 20_000L
    }
}
