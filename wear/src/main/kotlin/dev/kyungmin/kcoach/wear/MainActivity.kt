package dev.kyungmin.kcoach.wear

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.InputType
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.RestCountdownCalculator
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.ManualSetCommand
import dev.kyungmin.kcoach.core.ManualSetOperation
import dev.kyungmin.kcoach.core.ManualSetStatus
import dev.kyungmin.kcoach.core.WireCodec

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private lateinit var otaCoordinator: dev.kyungmin.kcoach.updates.OtaCoordinator
    private var unsubscribeUpdates: (() -> Unit)? = null
    private var updateStateText: TextView? = null
    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gwear", ignoreCase = true)

    private var paired = false
    private var running = false
    private var resting = false
    /** A rest the phone paused: the rest screen stays, with its time frozen at [pausedRestRemainingMs]. */
    private var restPaused = false
    private var pausedRestRemainingMs = 0L
    private var paused = false
    private var screenDimmed = false
    private var sessionId = ""
    private var exerciseId = ""
    private var setId = ""
    private var attemptId = ""
    private var manualRequired = false
    private var manualStatus = ""
    private var exerciseName = "운동 대기 중"
    private var setIndex = 0
    private var targetReps = 10
    private var weightKg: Double? = null
    private var restSeconds = 60
    private var restEndAtMs = 0L
    private var reps = 0
    private var heartRate = 0
    private var runtimePhase = "IDLE"
    private var personalizedTracking = false
    private var shadowCommandId = ""
    private var shadowCount = 0
    private var shadowEventIndex = -1
    private var shadowEmissionNs = -1L
    private var frameCommitPending = ""
    private lateinit var face: WatchWorkoutFace
    private lateinit var pager: WatchPager
    private lateinit var editPage: EditPageView
    private lateinit var auxPage: AuxPageView
    /** 0 = the set just finished, 1 = the next set; a rest opens on the next set, the one still to be done. */
    private var restEditSegment = 1
    private var lastWorkingReps = 0
    private var lastWorkingWeight: Double? = null
    private var wasResting = false
    private var phoneCheckDialog: AlertDialog? = null
    /** The question must only exist while the round display is awake; a dialog left up on a stopped activity is stale. */
    private var resumed = false
    /** 0 while the rest runs, 1 when it is paused; animated over 340 ms when the pause state changes (design 4-1). */
    private var restMorphTarget = 0f
    private var restMorphAnimator: android.animation.ValueAnimator? = null
    private var countingPausedForEdit = false
    private var rotaryRemainder = 0f
    private val homeStore by lazy { WatchHomeStore(this) }
    private var phoneResultStatus = ""
    private var phoneResultPending = false
    private var resultGeneration = 0
    private var displayedCompletionSession = ""
    internal var resultOpener: (Intent, (Boolean) -> Unit) -> Unit = { request, done -> WatchPhoneResult.open(this, request, done) }
    private val clockTicker = object : Runnable {
        override fun run() {
            if (::face.isInitialized) {
                val text = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                listOf(face.clock, setDone.clock, calibrationView.clock, resultView.clock).forEach { it.text = text }
            }
            handler.postDelayed(this, 30_000)
        }
    }
    private lateinit var shadowText: TextView

    private val runtimeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == WearWorkoutService.ACTION_STATE) applyRuntimeState(intent)
            else if (intent.action == WatchHomeStore.ACTION_OVERVIEW) updateUi()
            else if (intent.action == WearWorkoutService.ACTION_WORKOUT_CONTROL) {
                updateUi()
                val message = when (intent.getStringExtra("status")) {
                    "STALE", "NO_PLAN" -> "폰에서 루틴을 다시 선택해 주세요"
                    "BUSY" -> "폰에서 진행 중인 운동을 확인해 주세요"
                    "REJECTED" -> "요청한 세트가 변경됐어요"
                    else -> null
                }
                if (message != null) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private lateinit var restRing: RestProgressRing
    private val manualOutbox by lazy { ManualSetOutbox(this) }
    private val workoutControls by lazy { WatchWorkoutOutbox(this) }
    private var tempoEnabled = false
    private var tempoHint = ""
    private val restUiTicker = object : Runnable {
        override fun run() {
            if (!resting) return
            updateUi()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        otaCoordinator = dev.kyungmin.kcoach.updates.OtaCoordinator.get(this)
        setContentView(buildUi())
        unsubscribeUpdates = otaCoordinator.observe { state ->
            updateStateText?.text = state.message
            updateStateText?.visibility = if (otaCoordinator.store.local()?.phase == dev.kyungmin.kcoach.updates.OtaPhase.USER_ACTION_REQUIRED) View.VISIBLE else View.GONE
        }
        emulatorBridge.start()
        startService(Intent(this, WearCommandService::class.java))
        startService(Intent(this, WearWorkoutService::class.java))
        registerReceiver(runtimeStateReceiver, IntentFilter(WearWorkoutService.ACTION_STATE).apply {
            addAction(WearWorkoutService.ACTION_WORKOUT_CONTROL)
            addAction(WatchHomeStore.ACTION_OVERVIEW)
        }, android.content.Context.RECEIVER_NOT_EXPORTED)
        if (!isEmulator) requestPermissionsIfNeeded()
        updateUi()
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "QUERY"))
        applyCommandIntent(intent)
        sendWatchUiReady()
    }

    private fun sendWatchUiReady() {
        val payload = WearSync.watchUiReady(sessionId)
        Thread {
            runCatching {
                Tasks.await(Wearable.getNodeClient(this).connectedNodes).forEach { node ->
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, WearSync.PATH_WATCH_UI_READY, payload.toByteArray()))
                }
            }
            emulatorBridge.send(WearSync.PATH_WATCH_UI_READY, payload)
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyCommandIntent(intent)
    }

    private fun applyCommandIntent(intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        startService(Intent(this, WearWorkoutService::class.java).apply {
            putExtra(WearWorkoutService.EXTRA_COMMAND, command)
            putExtra("session", intent.getStringExtra("session").orEmpty())
            putExtra("exerciseId", intent.getStringExtra("exerciseId").orEmpty())
            putExtra("exerciseName", intent.getStringExtra("exerciseName").orEmpty())
            putExtra("setIndex", intent.getIntExtra("setIndex", 0))
            putExtra("targetReps", intent.getIntExtra("targetReps", 10))
            putExtra("weightKg", intent.getDoubleExtra("weightKg", Double.NaN))
            putExtra("restSeconds", intent.getIntExtra("restSeconds", 60))
            putExtra("restEndAtMs", intent.getLongExtra("restEndAtMs", 0L))
        })
    }

    private fun applyRuntimeState(intent: Intent) {
        paired = intent.getBooleanExtra("paired", paired)
        running = intent.getBooleanExtra("running", running)
        restPaused = intent.getBooleanExtra("restPaused", false)
        pausedRestRemainingMs = intent.getLongExtra("pausedRestRemainingMs", 0L)
        resting = intent.getBooleanExtra("resting", resting) || restPaused
        paused = intent.getBooleanExtra("paused", paused)
        sessionId = intent.getStringExtra("session").orEmpty()
        exerciseId = intent.getStringExtra("exerciseId").orEmpty()
        setId = intent.getStringExtra("setId").orEmpty()
        attemptId = intent.getStringExtra("attemptId").orEmpty()
        manualRequired = intent.getBooleanExtra("manualRequired", false)
        manualStatus = intent.getStringExtra("manualStatus").orEmpty()
        tempoEnabled = intent.getBooleanExtra("tempoEnabled", false)
        tempoHint = intent.getStringExtra("tempoHint").orEmpty()
        exerciseName = intent.getStringExtra("exerciseName").orEmpty().ifBlank { "운동 대기 중" }
        setIndex = intent.getIntExtra("setIndex", setIndex)
        targetReps = intent.getIntExtra("targetReps", targetReps).coerceAtLeast(1)
        weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
        restSeconds = intent.getIntExtra("restSeconds", restSeconds).coerceAtLeast(0)
        restEndAtMs = intent.getLongExtra("restEndAtMs", restEndAtMs)
        reps = intent.getIntExtra("reps", reps)
        heartRate = intent.getIntExtra("heartRate", heartRate)
        runtimePhase = intent.getStringExtra("phase").orEmpty().ifBlank { runtimePhase }
        personalizedTracking = intent.getBooleanExtra("personalized", personalizedTracking)
        shadowCommandId = intent.getStringExtra("patternShadowCommandId").orEmpty()
        shadowCount = intent.getIntExtra("patternShadowCount", 0)
        shadowEventIndex = intent.getIntExtra("patternShadowEventIndex", -1)
        shadowEmissionNs = intent.getLongExtra("patternShadowEmissionNs", -1L)
        preparationHoldMs = intent.getLongExtra("preparationHoldMs", -1L)
        preparationHoldTargetMs = intent.getLongExtra("preparationHoldTargetMs", -1L)
        phoneCameraRecording = intent.getBooleanExtra("phoneCameraRecording", false)
        calibrationConfirmedReps = intent.getIntExtra("calibrationConfirmedReps", -1).takeIf { it >= 0 }
        trackSetEnd(intent)
        if (running || resting || paused || sessionId.isNotBlank()) {
            homeStore.leaveCompletion()
            resultGeneration += 1; phoneResultPending = false; phoneResultStatus = ""
        }
        updateScreenPolicy()
        updateUi()
        handler.removeCallbacks(restUiTicker)
        if (resting) handler.post(restUiTicker)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // The screen may have been off while the service raised the phone-viewing question.
        // Ask for the live phase so the watch shows the question instead of a stale page.
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "QUERY"))
        updateScreenPolicy()
        otaCoordinator.attach(this)
        handler.removeCallbacks(clockTicker)
        handler.post(clockTicker)
        updateUi()
    }

    override fun onPause() {
        resumed = false
        phoneCheckDialog?.takeIf { it.isShowing }?.dismiss()
        phoneCheckDialog = null
        otaCoordinator.detach(this)
        handler.removeCallbacks(clockTicker)
        super.onPause()
    }

    override fun onStop() {
        // The host relay and timers are intentionally kept alive across the
        // screen-off/launcher transition. A real session must not stop merely
        // because the round display is no longer foregrounded.
        super.onStop()
    }

    override fun onDestroy() {
        resultGeneration += 1
        handler.removeCallbacks(clockTicker)
        unsubscribeUpdates?.invoke()
        handler.removeCallbacks(restUiTicker)
        runCatching { unregisterReceiver(runtimeStateReceiver) }
        emulatorBridge.stop()
        super.onDestroy()
    }

    private fun buildUi(): View {
        face = WatchWorkoutFace(this)
        face.action.setOnClickListener { openFinishedWorkout() }
        face.restSkip.onClick = { requestWorkoutControl() }
        face.restPause.onClick = { toggleRestPause() }
        face.jog.onStep = { delta -> adjustRest(delta) }
        face.hint.setOnClickListener { if (runtimePhase == "START_DIRECTION_WAITING") bypassStartDirection() else if (face.noticeAction != null) face.noticeAction?.invoke() }
        editPage = EditPageView(this).apply {
            onDelta = { field, delta -> editValue(field, delta) }
            onKeypad = { field -> keypadFor(field) }
            onFinish = { finishCurrentSet() }
            onSegment = { restEditSegment = it; updateUi() }
        }
        auxPage = AuxPageView(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(face, FrameLayout.LayoutParams(-1, -1)) }
        restRing = RestProgressRing().apply { visibility = View.GONE; isClickable = false }
        frame.addView(restRing, FrameLayout.LayoutParams(-1, -1))
        shadowText = label("", 11f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        frame.addView(shadowText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(30) })
        pager = WatchPager(this, frame, editPage, auxPage).apply {
            onPage = { page -> onPageChanged(page) }
        }
        // Full screens that take over the pages: calibration, the workout result, and on top of everything the
        // few seconds after a set ends.
        calibrationView = CalibrationView(this).apply { visibility = View.GONE; isClickable = true }
        resultView = WorkoutResultView(this).apply { visibility = View.GONE; isClickable = true; onOpen = { openFinishedWorkout() } }
        setDone = SetDoneView(this).apply { background = null }
        setDoneLayer = FrameLayout(this).apply {
            background = android.graphics.drawable.ColorDrawable(Color.BLACK)
            visibility = View.GONE; isClickable = true
            setOnClickListener { hideSetDone() }
            addView(setDone, FrameLayout.LayoutParams(-1, -1))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(pager, FrameLayout.LayoutParams(-1, -1))
            addView(calibrationView, FrameLayout.LayoutParams(-1, -1))
            addView(resultView, FrameLayout.LayoutParams(-1, -1))
            addView(setDoneLayer, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private lateinit var calibrationView: CalibrationView
    private lateinit var resultView: WorkoutResultView
    private lateinit var setDone: SetDoneView
    private lateinit var setDoneLayer: FrameLayout
    private val tally by lazy { WatchSessionTally.get(this) }
    /** The set being measured right now, kept so its end can be announced with the numbers it ended on. */
    private var workingSet: SetMoment? = null
    /** The finished set on screen (screens 3, 7, 8), or null. */
    private var shownSet: SetMoment? = null
    private var setDoneUntilMs = 0L
    private val setDoneEnd = Runnable { hideSetDone() }
    private var preparationHoldMs = -1L
    private var preparationHoldTargetMs = -1L
    private var phoneCameraRecording = false
    private var calibrationConfirmedReps: Int? = null
    /** Screen 11 stays at least 3.5 s even when the phone closes the calibration session right away. */
    private var calibrationDone: Pair<Int, Int?>? = null
    private var calibrationDoneSession = ""
    private var calibrationDoneUntilMs = 0L
    private val calibrationDoneEnd = Runnable { updateUi() }
    private var phoneResultColor = WatchWorkoutFace.MUTED
    /** The number of the set the watch last saw end, for the rest editor's "방금 1세트"; 0 when unknown. */
    private var lastFinishedSetNumber = 0

    /**
     * Notices the moment a measured set ends (the watch counted to the end, the user submitted a count, or the
     * phone moved to rest) and shows screen 3, or 7/8 when the phone reports a record for that set.
     */
    private fun trackSetEnd(intent: Intent) {
        val calibration = sessionId.startsWith("calibration-")
        val previous = workingSet
        if (running && !resting && !paused && !calibration && setId.isNotBlank()) {
            workingSet = SetMoment(sessionId, setId, exerciseId, exerciseName, setIndex + 1, displayedCount() ?: reps, weightKg)
            // The next set has started: the moment of the previous one is over.
            shownSet?.let { if (it.setId != setId || it.sessionId != sessionId) hideSetDone(immediate = true) }
            return
        }
        shownSet?.let { shown ->
            val record = SetRecord.from(intent, shown.setId)
            if (record != null && shown.record == null) {
                tally.noteRecord(shown.sessionId, shown.exerciseId)
                showSetDone(shown.copy(record = record), switching = true)
            }
        }
        if (paused) return
        workingSet = null
        if (previous == null || previous.sessionId != sessionId) return
        val finished = when {
            setId == previous.setId && !running && !resting -> previous.copy(reps = displayedCount() ?: reps, weightKg = weightKg)
            resting -> previous
            else -> return
        }
        lastFinishedSetNumber = finished.setNumber
        if ((finished.reps ?: 0) <= 0) return
        val record = SetRecord.from(intent, finished.setId)
        if (record != null) tally.noteRecord(finished.sessionId, finished.exerciseId)
        showSetDone(finished.copy(record = record), switching = false)
    }

    private fun showSetDone(moment: SetMoment, switching: Boolean) {
        val quiet = Moment.reducedMotion(this)
        // The window runs on the monotonic clock the handler uses, so a wall-clock change cannot stretch it.
        val now = android.os.SystemClock.uptimeMillis()
        shownSet = moment
        val clockText = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        setDoneLayer.animate().cancel(); setDone.animate().cancel()
        if (switching) {
            // A record reported a moment later replaces the plain check, with enough time left to read it.
            setDoneUntilMs = maxOf(setDoneUntilMs, now + 2_500L)
            if (quiet) setDone.bind(moment, clockText)
            else setDone.animate().alpha(0f).setDuration(100).withEndAction {
                setDone.bind(moment, clockText); setDone.playEntrance(); setDone.animate().alpha(1f).setDuration(150).start()
            }.start()
        } else {
            setDoneUntilMs = now + Moment.SET_DONE_MS
            setDone.bind(moment, clockText)
            pager.show(0)
            setDoneLayer.visibility = View.VISIBLE
            setDoneLayer.background.alpha = 255
            if (quiet) { setDoneLayer.alpha = 1f; setDone.alpha = 1f }
            else {
                // Dip to black, then the content: the counter underneath never shows through the new screen.
                setDoneLayer.alpha = 0f; setDone.alpha = 0f
                setDoneLayer.animate().alpha(1f).setDuration(120).withEndAction { setDone.animate().alpha(1f).setDuration(150).start() }.start()
            }
            if (setDone.look == SetDoneView.Look.DONE) setDone.check.play()
            else setDone.playEntrance()
            buzzOnce()
        }
        handler.removeCallbacks(setDoneEnd)
        handler.postDelayed(setDoneEnd, (setDoneUntilMs - now).coerceAtLeast(0L))
        coverPages()
    }

    private fun hideSetDone(immediate: Boolean = false) {
        handler.removeCallbacks(setDoneEnd)
        if (shownSet == null && setDoneLayer.visibility != View.VISIBLE) return
        shownSet = null
        setDoneLayer.animate().cancel(); setDone.animate().cancel()
        if (immediate || Moment.reducedMotion(this)) { setDoneLayer.visibility = View.GONE; coverPages(); return }
        // The content fades to black first, then the black lifts off the rest screen.
        setDone.animate().alpha(0f).setDuration(140).withEndAction {
            setDoneLayer.animate().alpha(0f).setDuration(160).withEndAction {
                if (shownSet == null) setDoneLayer.visibility = View.GONE
                coverPages()
            }.start()
        }.start()
    }

    /** Pages hidden under a full screen are hidden from accessibility too. */
    private fun coverPages() {
        val covered = setDoneLayer.visibility == View.VISIBLE || calibrationView.visibility == View.VISIBLE || resultView.visibility == View.VISIBLE
        pager.importantForAccessibility = if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        calibrationView.importantForAccessibility = if (setDoneLayer.visibility == View.VISIBLE) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        resultView.importantForAccessibility = calibrationView.importantForAccessibility
    }

    /** One short buzz when a set ends; no sound. */
    private fun buzzOnce() {
        val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(60L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Screens 9, 10 and 11 replace the counter while a calibration session runs and right after it ([now] is uptime). */
    private fun updateCalibration(now: Long, clockText: String) {
        val calibration = sessionId.startsWith("calibration-")
        if (calibration && running && !paused) {
            calibrationDone = null
            if (runtimePhase == "BASELINE") {
                val steps = holdSteps(preparationHoldTargetMs.takeIf { it > 0 } ?: 0L)
                val left = if (preparationHoldTargetMs > 0 && preparationHoldMs >= 0)
                    kotlin.math.ceil((preparationHoldTargetMs - preparationHoldMs).coerceAtLeast(0L) / 1000.0).toInt().coerceIn(1, steps) else null
                calibrationView.bindPrepare(clockText, left, steps, phoneCameraRecording)
            } else calibrationView.bindCount(clockText, displayedCount() ?: reps, targetReps, phoneCameraRecording)
            calibrationView.visibility = View.VISIBLE
        } else if (calibration && !running && !resting && !paused && (reps > 0 || runtimePhase == "CALIBRATION_RESULT")) {
            val data = reps to calibrationConfirmedReps
            val first = calibrationDone == null || calibrationDoneSession != sessionId
            if (first) { calibrationDoneSession = sessionId; calibrationDoneUntilMs = now + Moment.SET_DONE_MS }
            if (first || data != calibrationDone) calibrationView.bindDone(clockText, data.first, data.second, playCheck = first)
            calibrationDone = data
            calibrationView.visibility = View.VISIBLE
        } else if (calibrationDone != null && now < calibrationDoneUntilMs && !running && !resting && !paused) {
            // The session already closed; the result stays until it has been on screen for 3.5 s.
            calibrationView.clock.text = clockText
            calibrationView.visibility = View.VISIBLE
            handler.removeCallbacks(calibrationDoneEnd)
            handler.postDelayed(calibrationDoneEnd, calibrationDoneUntilMs - now + 20)
        } else {
            calibrationDone = null
            calibrationView.visibility = View.GONE
        }
    }

    /** Entering the editor stops counting so lifting the wrist to look at the watch is not counted as a rep. */
    private fun onPageChanged(page: Int) {
        val editing = page == -1 && running && !resting && !paused
        if (editing != countingPausedForEdit) {
            countingPausedForEdit = editing
            sendWorkoutCommand(if (editing) "COUNTING_PAUSE" else "COUNTING_RESUME")
        }
        updateUi()
    }

    private fun sendWorkoutCommand(command: String, configure: Intent.() -> Unit = {}) {
        if (sessionId.isBlank()) return
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, command)
            .putExtra("session", sessionId).putExtra("setId", setId).putExtra("attemptId", attemptId).apply(configure))
    }

    private fun bypassStartDirection() {
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "START_DIRECTION_BYPASS")
            .putExtra("session", sessionId).putExtra("setId", setId).putExtra("attemptId", attemptId))
    }

    /** Rest time in ten second steps, from the bar, its ends, or the bezel. */
    private fun adjustRest(delta: Int) {
        if (!resting || paused) return
        val now = System.currentTimeMillis()
        val applied = delta
        if (applied == 0) return
        sendWorkoutCommand("REST_ADJUST") { putExtra("deltaSeconds", applied) }
        updateUi()
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_SCROLL && event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) {
            // Clockwise reports a negative scroll; one detent is one ten-second step on the rest screen, nothing elsewhere.
            rotaryRemainder += -event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            val steps = rotaryRemainder.toInt()
            if (steps != 0) {
                rotaryRemainder -= steps
                if (resting && pager.page == 0) {
                    window.decorView.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) 18 /* ROTARY_SCROLL_TICK */ else android.view.HapticFeedbackConstants.CLOCK_TICK)
                    adjustRest(steps * 10)
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun editValue(field: EditPageView.Field, delta: Int) {
        if (resting) {
            val which = if (restEditSegment == 0) "PREVIOUS" else "NEXT"
            if (field == EditPageView.Field.REPS) {
                val current = if (restEditSegment == 0) lastWorkingReps else targetReps
                val next = (current + delta).coerceIn(0, 9_999)
                if (restEditSegment == 0) { lastWorkingReps = next; tally.editLast(reps = next) } else targetReps = next
                sendWorkoutCommand("EDIT_SET") { putExtra("which", which); putExtra("reps", next); putExtra("weightKg", Double.NaN) }
            } else {
                val current = (if (restEditSegment == 0) lastWorkingWeight else weightKg) ?: 0.0
                val next = (current + delta).coerceIn(0.0, 999.0)
                if (restEditSegment == 0) { lastWorkingWeight = next; tally.editLast(weightKg = next) } else weightKg = next
                sendWorkoutCommand("EDIT_SET") { putExtra("which", which); putExtra("reps", -1); putExtra("weightKg", next) }
            }
        } else if (field == EditPageView.Field.REPS) {
            val next = ((displayedCount() ?: reps) + delta).coerceIn(0, 9_999)
            getSharedPreferences("manual-count-drafts", MODE_PRIVATE).edit().putString(draftKey(), next.toString()).apply()
        } else {
            val next = ((weightKg ?: 0.0) + delta).coerceIn(0.0, 999.0)
            weightKg = next
            sendWorkoutCommand("EDIT_SET") { putExtra("which", "CURRENT"); putExtra("reps", -1); putExtra("weightKg", next) }
        }
        updateUi()
    }

    /** Tapping a value opens the watch's own number keypad for anyone the plus and minus steps do not suit. */
    private fun keypadFor(field: EditPageView.Field) {
        if (field == EditPageView.Field.REPS && !resting) { showManualCount(editOnly = true); return }
        val reps = field == EditPageView.Field.REPS
        val initial = when {
            reps && resting -> (if (restEditSegment == 0) lastWorkingReps else targetReps).toString()
            reps -> (displayedCount() ?: this.reps).toString()
            else -> (if (resting && restEditSegment == 0) lastWorkingWeight else weightKg)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()
        }
        val input = EditText(this).apply {
            inputType = if (reps) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(5)); setText(initial); setSelection(initial.length)
        }
        AlertDialog.Builder(this).setTitle(if (reps) "횟수" else "무게 (kg)").setView(input).setNegativeButton("취소", null)
            .setPositiveButton("적용") { _, _ ->
                val value = input.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val delta = if (reps) (value.toInt() - (initial.toIntOrNull() ?: 0)) else 0
                if (reps) editValue(field, delta)
                else {
                    val current = (if (resting && restEditSegment == 0) lastWorkingWeight else weightKg) ?: 0.0
                    // The step helper works in whole kilograms; the keypad can set any value, so apply the difference.
                    val difference = value - current
                    if (resting) {
                        val which = if (restEditSegment == 0) "PREVIOUS" else "NEXT"
                        if (restEditSegment == 0) { lastWorkingWeight = value; tally.editLast(weightKg = value) } else weightKg = value
                        sendWorkoutCommand("EDIT_SET") { putExtra("which", which); putExtra("reps", -1); putExtra("weightKg", value) }
                    } else {
                        weightKg = value
                        sendWorkoutCommand("EDIT_SET") { putExtra("which", "CURRENT"); putExtra("reps", -1); putExtra("weightKg", value) }
                    }
                    if (difference != 0.0) updateUi()
                }
            }.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** When the rest ends; a paused rest never gets closer, so its end keeps the same distance from now. */
    private fun restEnd(now: Long) = if (restPaused) now + pausedRestRemainingMs else restEndAtMs

    private fun requestPermissionsIfNeeded() {
        val permissions = listOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = permissions
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
    }

    private fun updateScreenPolicy() {
        // The open app must remain visible while waiting for the phone too.
        // This window flag only keeps the display awake while this activity is foregrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Dim only while a set is actively being measured; rest, pause and idle use the normal brightness.
        val dim = running && !resting && !paused
        if (dim != screenDimmed) {
            screenDimmed = dim
            window.attributes = window.attributes.also {
                it.screenBrightness = if (dim) WORKING_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    private fun updateUi() {
        if (!::face.isInitialized) return
        updateScreenPolicy()
        val now = System.currentTimeMillis()
        val clockText = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        face.clock.text = clockText
        val active = running || resting || paused || sessionId.isNotBlank() && setId.isNotBlank()
        val complete = !active && homeStore.showsCompletion()
        if (complete && displayedCompletionSession != homeStore.completion()?.sessionId) {
            displayedCompletionSession = homeStore.completion()?.sessionId.orEmpty()
            resultGeneration += 1; phoneResultPending = false; phoneResultStatus = ""
        }
        val count = displayedCount()
        val measuring = active && !resting
        if (measuring) { lastWorkingReps = count ?: reps; lastWorkingWeight = weightKg }
        if (resting && !wasResting) restEditSegment = 1
        wasResting = resting
        face.primary(if (resting) 2 else if (active) 0 else 1)
        face.homeLayout(!active && !complete)
        face.value.maxLines = if (complete) 2 else 1
        face.showDivider = measuring
        face.invalidate()
        listOf(face.target, face.heartRate, face.bpm).forEach { it.visibility = if (measuring) View.VISIBLE else View.GONE }
        face.title.visibility = if (resting) View.GONE else View.VISIBLE
        face.down.visibility = View.GONE
        // The finished workout has its own screen with its own button (screen 12).
        face.action.visibility = View.GONE
        resultView.visibility = if (complete) View.VISIBLE else View.GONE
        if (complete) resultView.bind(clockText, homeStore.completion()?.summary, phoneResultStatus, phoneResultColor, phoneResultPending)
        updateCalibration(android.os.SystemClock.uptimeMillis(), clockText)
        setDone.clock.text = clockText
        coverPages()
        face.hint.text = ""; face.hint.setTextColor(WatchWorkoutFace.MUTED); face.noticeAction = null
        face.size(face.value, if (active) 88f else 24f)
        face.value.setTextColor(WatchWorkoutFace.SAGE)
        listOf(face.jog, face.restPause, face.restSkip).forEach { it.visibility = View.GONE }
        // The rest buttons need the phone to apply them, also while the rest is paused.
        val restControls = resting && workoutControls.supportsControls() && setId.isNotBlank() && attemptId.isNotBlank()
        if (!resting) { restMorphTarget = 0f; restMorphAnimator?.cancel(); restRing.fade = 0f }
        if (active) {
            face.title.text = exerciseName
            if (paused) face.setSubtitle("일시정지", WatchWorkoutFace.MUTED)
            else face.setStatusLine("${setIndex + 1}세트", weightKg?.let { "${Moment.kg(it)}kg" }, if (manualRequired) "직접 기록" else "자동 측정",
                dot = if (!manualRequired && running) WatchWorkoutFace.SAGE else null)
            face.target.text = "/ ${targetReps}회"
            face.value.text = count?.toString() ?: "—"
            face.value.setTextColor(if (count != null && count > targetReps) WatchWorkoutFace.ORANGE else WatchWorkoutFace.SAGE)
            face.heart.show(if (resting) 0 else heartRate); face.heartRate.text = heartRate.takeIf { it > 0 }?.toString() ?: "—"
            // Past the target the line under the name says so in the over-target colour (the design's screen 2).
            if (running && !paused && count != null && count > targetReps) face.setSubtitle("목표 초과", WatchWorkoutFace.ORANGE, medium = true)
            if (manualRequired && count == null) face.hint.text = "위로 · 횟수 기록"
            if (running && runtimePhase in setOf("POSITIONING", "PREPARING", "BASELINE", "START_DIRECTION_WAITING")) {
                face.value.text = "준비"; face.size(face.value, 40f)
                face.value.setTextColor(WatchWorkoutFace.SAGE)
                face.hint.text = if (runtimePhase == "START_DIRECTION_WAITING") "손목 방향을 맞춰 주세요 · 눌러서 직접 시작" else "손목을 잠시 멈춰 주세요"
            } else if (!resting && runtimePhase == "READY") {
                // Between rest completion and the next SET the previous set's count is still in memory.
                // Never paint it as the next set's live count.
                face.value.text = "준비"; face.size(face.value, 40f)
                face.value.setTextColor(WatchWorkoutFace.SAGE)
                face.hint.text = "다음 세트를 준비해 주세요"
            }
            if (resting) {
                val left = RestCountdownCalculator.calculate(now, restEnd(now), restSeconds).remainingSeconds
                face.value.text = String.format("%d:%02d", left / 60, left % 60)
                face.size(face.value, 54f)
                face.target.visibility = View.GONE
                // Design 4a / 4-1: the line above the time exists only while the rest is paused (it fades in with the morph).
                face.setSubtitle("휴식 · 일시정지", WatchWorkoutFace.SOFT, medium = true)
                listOf(face.jog, face.restPause, face.restSkip).forEach { it.visibility = View.VISIBLE }
                listOf(face.restPause, face.restSkip).forEach { it.isEnabled = restControls; it.alpha = if (restControls) 1f else .4f }
                morphRest(restPaused)
                face.applyRestMorph()
            }
            if (paused && !restPaused) face.hint.text = "폰에서 계속할 수 있어요"
            face.layoutCount(); face.layoutMetrics()
        } else if (complete) {
            face.title.text = "운동 완료"; face.setSubtitle("수고했어요", WatchWorkoutFace.MUTED)
            face.value.text = if (phoneResultStatus.isBlank()) "기록을 확인해 보세요" else phoneResultStatus
            face.size(face.value, 14f); face.value.setTextColor(WatchWorkoutFace.WHITE)
            face.heart.show(0)
        } else {
            face.title.text = "이번 주 운동"; face.setSubtitle("", WatchWorkoutFace.MUTED)
            face.value.setTextColor(WatchWorkoutFace.BONE)
            face.value.text = homeStore.weekCount()?.let { "${it}회" } ?: "—"
            face.size(face.value, 40f)
            val recent = homeStore.overview()?.optJSONObject("recent")
            face.hint.text = recent?.let {
                val date = java.time.Instant.ofEpochMilli(it.getLong("endedAtMs")).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                "최근 · ${it.getString("routineName")}\n${date.monthValue}월 ${date.dayOfMonth}일"
            } ?: "최근 기록이 아직 없어요"
            // A missing overview is unknown, not an empty workout history.
            if (homeStore.overview() == null) face.hint.text = "폰에서 기록을 확인하세요"
            face.heart.show(0)
        }
        updatePhoneCheckDialog()
        // One status line under the numbers, most urgent first; every message here used to be its own control.
        val unresolved = manualOutbox.unresolved()
        val updateWaiting = otaCoordinator.store.local()?.phase == dev.kyungmin.kcoach.updates.OtaPhase.USER_ACTION_REQUIRED
        val phaseMessage = when (runtimePhase) {
            "CAPTURE_RECOVERY_REQUIRED" -> "센서 기록 복구가 필요해요"
            "MANUAL_PENDING" -> "폰에 기록 전달 중"
            "MANUAL_CONFLICT" -> "폰과 워치의 횟수를 확인해 주세요"
            else -> ""
        }
        if (active && !resting && phaseMessage.isNotEmpty()) { face.hint.text = phaseMessage; face.hint.setTextColor(WatchWorkoutFace.ORANGE) }
        else if (active && !paired) { face.hint.text = "폰 연결이 끊겼어요 · 기록은 보관돼요"; face.hint.setTextColor(WatchWorkoutFace.ORANGE) }
        else if (updateWaiting) { face.hint.text = "폰이 요청한 업데이트 계속"; face.hint.setTextColor(WatchWorkoutFace.SAGE); face.noticeAction = { otaCoordinator.confirmInstall(this) } }
        else if (unresolved.isNotEmpty()) {
            face.hint.text = "보관 중인 횟수 ${unresolved.size}건"; face.hint.setTextColor(WatchWorkoutFace.SAGE)
            face.noticeAction = {
                AlertDialog.Builder(this).setTitle("보관 중인 횟수").setMessage(manualOutbox.unresolved().joinToString("\n") {
                    "${it.command.setIndex + 1}세트 · ${it.command.performedReps}회 · " +
                        if (it.ack?.status == ManualSetStatus.CONFLICT) "폰 기록과 달라요" else "폰에서 확인해 주세요"
                }).setPositiveButton("닫기", null).show()
            }
        }
        face.hint.isClickable = face.noticeAction != null || runtimePhase == "START_DIRECTION_WAITING"
        shadowText.visibility = if (running && shadowCommandId.isNotBlank()) View.VISIBLE else View.GONE
        shadowText.text = "보정 비교 · ${shadowCount}회"; recordShadowFrameCommit()

        // Pages: the editor above and the heart-rate page below, while measuring and during a rest that is not paused.
        val editable = active && !paused && !sessionId.startsWith("calibration-") && setId.isNotBlank() && attemptId.isNotBlank() && runtimePhase != "CAPTURE_RECOVERY_REQUIRED"
        pager.topEnabled = editable
        pager.bottomEnabled = active && !paused && !sessionId.startsWith("calibration-")
        face.chevronUp.visibility = if (pager.topEnabled) View.VISIBLE else View.GONE
        // During a rest the arrow stays in the layout and the pause morph fades it out, so it can fade rather than vanish.
        face.chevronDown.visibility = if (pager.bottomEnabled || resting) View.VISIBLE else View.GONE
        if (pager.page == -1 && !pager.topEnabled || pager.page == 1 && !pager.bottomEnabled) pager.show(0)
        if (active) {
            val pending = manualStatus in setOf("PENDING", "STALE", "NO_SESSION", "REJECTED")
            if (resting) {
                editPage.applyMode(EditPageView.Mode.REST)
                val previous = restEditSegment == 0
                val repsShown = if (previous) lastWorkingReps else targetReps
                val weightShown = if (previous) lastWorkingWeight else weightKg
                val left = RestCountdownCalculator.calculate(now, restEnd(now), restSeconds).remainingSeconds
                editPage.bindRest("휴식 ${left / 60}:${"%02d".format(left % 60)}", clockText,
                    if (lastFinishedSetNumber > 0) "방금 ${lastFinishedSetNumber}세트" else "방금 세트", "다음 ${setIndex + 1}세트", restEditSegment,
                    repsShown.toString(), weightShown?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—")
            } else {
                editPage.applyMode(EditPageView.Mode.MEASURE)
                val finishText = when (manualStatus) {
                    "PENDING" -> "폰에 기록 전달 중"
                    "CONFLICT" -> "횟수 확인"
                    "STALE", "NO_SESSION", "REJECTED" -> "폰에서 세트를 확인하세요"
                    "APPLIED", "DUPLICATE" -> "기록 수정"
                    else -> if (count == null) "횟수 입력" else "${count}회로 세트 마치기"
                }
                editPage.bindMeasure(exerciseName, setIndex + 1, (count ?: reps).toString(),
                    weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—", finishText, !pending && !paused)
            }
            val started = sessionStartedAt()
            val elapsed = ((now - started) / 1_000L).coerceAtLeast(0L)
            auxPage.bind(heartRate, if (elapsed >= 3600) "%d:%02d:%02d".format(elapsed / 3600, elapsed % 3600 / 60, elapsed % 60) else "%d:%02d".format(elapsed / 60, elapsed % 60), setIndex, clockText)
        }
        if (!active) face.heart.show(0)
        if (resting && restEnd(now) > now) {
            restRing.setWindow(restEnd(now) - restSeconds.coerceAtLeast(1) * 1_000L, restEnd(now)); restRing.visibility = View.VISIBLE
        } else if (active && !resting && !paused && count != null && targetReps > 0) {
            restRing.setProgress(count.toFloat() / targetReps); restRing.visibility = View.VISIBLE
        } else restRing.visibility = View.GONE
        rollCountIfChanged(measuring)
        val presentation = if (active) "active" else if (complete) "complete" else "home"
        if (lastPresentation != presentation) {
            lastPresentation = presentation
            pager.show(0)
        }
    }

    /** When this session was first seen on the watch, kept across restarts so the workout clock does not reset. */
    private fun sessionStartedAt(): Long {
        val prefs = getSharedPreferences("session-clock", MODE_PRIVATE)
        val key = "start:$sessionId"
        val saved = prefs.getLong(key, 0L)
        if (saved > 0L) return saved
        val now = System.currentTimeMillis()
        prefs.edit().clear().putLong(key, now).apply()
        return now
    }

    private var lastPresentation = ""
    private var lastCountShown = ""

    /** The old number slides up and out while the new one rises in from below, quickly and without any bounce. */
    private fun updatePhoneCheckDialog() {
        val wanted = resumed && running && runtimePhase == "PHONE_CHECK"
        if (!wanted) {
            phoneCheckDialog?.takeIf { it.isShowing }?.dismiss()
            phoneCheckDialog = null
            return
        }
        if (phoneCheckDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return
        // The state broadcast can arrive while the round display is asleep or tearing down.
        // Adding a window then throws BadTokenException on the main thread and kills the whole
        // process, taking the live rest timer with it; the question is simply retried on resume.
        phoneCheckDialog = runCatching {
            AlertDialog.Builder(this)
                .setTitle("폰 보고 있나요?")
                .setMessage("폰을 보는 동안에는 이 세트를 시작하지 않아요.")
                .setCancelable(false)
                .setPositiveButton("운동 시작") { _, _ -> sendPhoneCheck("PHONE_CHECK_START") }
                .setNegativeButton("휴식") { _, _ -> sendPhoneCheck("PHONE_CHECK_REST") }
                .show()
        }.getOrNull()
        if (phoneCheckDialog?.isShowing != true) phoneCheckDialog = null
    }

    private fun sendPhoneCheck(command: String) {
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, command))
    }

    private fun rollCountIfChanged(measuring: Boolean) {
        val shown = face.value.text.toString()
        if (!measuring || shown.firstOrNull()?.isDigit() != true) { lastCountShown = ""; return }
        val old = lastCountShown
        lastCountShown = shown
        if (old.isEmpty() || old == shown || old.firstOrNull()?.isDigit() != true) return
        val travel = dp(60).toFloat()
        val interpolator = android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f)
        face.valueGhost.apply {
            text = old; setTextColor(face.value.currentTextColor); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, face.value.textSize)
            animate().cancel(); translationY = 0f; alpha = 1f
            animate().translationY(-travel).alpha(0f).setDuration(180).setInterpolator(interpolator).start()
        }
        face.value.apply {
            animate().cancel(); translationY = travel; alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(180).setInterpolator(interpolator).start()
        }
    }
    private fun draftKey() = "$sessionId:$setId:$attemptId"
    private fun displayedCount(): Int? = getSharedPreferences("manual-count-drafts", MODE_PRIVATE).getString(draftKey(), null)?.toIntOrNull()
        ?: if (manualRequired && manualStatus.isBlank()) null else reps

    private fun finishCurrentSet() {
        if (paused || resting || setId.isBlank() || attemptId.isBlank() || manualStatus in setOf("PENDING", "STALE", "NO_SESSION", "REJECTED")) return
        if (manualStatus == "CONFLICT" || displayedCount() == null) { showManualCount(); return }
        val count = requireNotNull(displayedCount())
        val existing = manualOutbox.latest(sessionId, setId, attemptId)
        val command = ManualSetCommand(java.util.UUID.randomUUID().toString(), sessionId, exerciseId, setIndex, setId, attemptId,
            count, existing?.ack?.currentRevision ?: 0L, if (existing?.ack != null) ManualSetOperation.EDIT else ManualSetOperation.COMPLETE)
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "MANUAL_SUBMIT")
            .putExtra("payload", WireCodec.encode(command.toEnvelope(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()))))
        manualStatus = "PENDING"; updateUi()
    }

    private fun openFinishedWorkout() {
        if (running || resting || paused || sessionId.isNotBlank() || phoneResultPending) return
        val completed = homeStore.completion() ?: return
        val generation = ++resultGeneration
        phoneResultPending = true; phoneResultStatus = "폰으로 요청하는 중"; phoneResultColor = WatchWorkoutFace.MUTED; updateUi()
        fun finish(success: Boolean) {
            if (isDestroyed || generation != resultGeneration) return
            resultGeneration += 1; phoneResultPending = false
            phoneResultStatus = if (success) "폰에서 확인해 주세요" else "연결을 확인하고 다시 눌러 주세요"
            phoneResultColor = if (success) WatchWorkoutFace.SAGE else WatchWorkoutFace.ORANGE
            updateUi()
        }
        handler.postDelayed({ finish(false) }, 15_000)
        runCatching { resultOpener(WatchPhoneResult.intent(completed.sessionId), ::finish) }.onFailure { finish(false) }
    }

    private fun recordShadowFrameCommit() {
        if (!running || shadowCommandId.isBlank() || shadowEventIndex < 0 || shadowEmissionNs < 0 ||
            !shadowText.isAttachedToWindow || !shadowText.isHardwareAccelerated || !window.decorView.hasWindowFocus() ||
            !shadowText.getGlobalVisibleRect(android.graphics.Rect())) return
        val identity = "$sessionId|$setId|$attemptId|$shadowCommandId|$shadowEventIndex|$shadowEmissionNs"
        if (frameCommitPending == identity) return
        frameCommitPending = identity
        val session = sessionId; val set = setId; val attempt = attemptId
        val command = shadowCommandId; val index = shadowEventIndex; val emission = shadowEmissionNs
        shadowText.viewTreeObserver.registerFrameCommitCallback {
            val submittedAt = android.os.SystemClock.elapsedRealtimeNanos()
            runOnUiThread {
                if (isFinishing || isDestroyed || sessionId != session || setId != set || attemptId != attempt ||
                    shadowCommandId != command || shadowEventIndex != index || shadowEmissionNs != emission ||
                    !shadowText.getGlobalVisibleRect(android.graphics.Rect())) return@runOnUiThread
                startService(Intent(this, WearWorkoutService::class.java)
                    .putExtra(WearWorkoutService.EXTRA_COMMAND, "PATTERN_FRAME_COMMIT")
                    .putExtra("session", session).putExtra("setId", set).putExtra("attemptId", attempt)
                    .putExtra("patternCommandId", command).putExtra("eventIndex", index)
                    .putExtra("sourceEmissionNs", emission).putExtra("feedbackAtNs", submittedAt))
            }
        }
        shadowText.invalidate()
    }

    private fun requestWorkoutControl() {
        if (!resting || workoutControls.pending().isNotEmpty() || !workoutControls.supportsControls() || setId.isBlank() || attemptId.isBlank()) return
        val request = dev.kyungmin.kcoach.core.WatchWorkoutControl(java.util.UUID.randomUUID().toString(),
            dev.kyungmin.kcoach.core.WatchWorkoutAction.NEXT_SET, sessionId, setId, attemptId)
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "WORKOUT_CONTROL_SUBMIT")
            .putExtra("payload", request.encode()))
    }

    /** "정지" / "계속" on the rest screen: asks the phone to pause or resume the rest, and the screen follows the state it reports. */
    private fun toggleRestPause() {
        if (!resting || !workoutControls.supportsControls()) return
        sendWorkoutCommand(if (restPaused) "REST_RESUME" else "REST_PAUSE")
    }

    /** Morphs the rest screen to or from the paused state in 340 ms (design 4-1); the ring fades with it. */
    private fun morphRest(toPaused: Boolean) {
        val target = if (toPaused) 1f else 0f
        if (restMorphTarget == target) return
        restMorphTarget = target
        restMorphAnimator?.cancel()
        if (Moment.reducedMotion(this) || !face.isAttachedToWindow) { face.restMorph = target; restRing.fade = target; return }
        restMorphAnimator = android.animation.ValueAnimator.ofFloat(face.restMorph, target).apply {
            duration = 340; interpolator = android.view.animation.PathInterpolator(.22f, .8f, .3f, 1f)
            addUpdateListener { val v = it.animatedValue as Float; face.restMorph = v; restRing.fade = v }
            start()
        }
    }

    private fun showManualCount(editOnly: Boolean = false) {
        if (manualStatus == "PENDING" || setId.isBlank() || attemptId.isBlank()) return
        val session = sessionId
        val exercise = exerciseId
        val index = setIndex
        val set = setId
        val attempt = attemptId
        val existing = manualOutbox.latest(session, set, attempt)
        val revision = existing?.ack?.currentRevision ?: 0L
        fun submit(count: Int) {
            if (sessionId != session || setId != set || attemptId != attempt || resting || paused) {
                Toast.makeText(this, "세트가 바뀌었어요. 현재 세트를 확인해 주세요", Toast.LENGTH_LONG).show()
                return
            }
            if (editOnly) {
                getSharedPreferences("manual-count-drafts", MODE_PRIVATE).edit().putString("$session:$set:$attempt", count.toString()).apply()
                updateUi()
                return
            }
            val command = ManualSetCommand(java.util.UUID.randomUUID().toString(), session, exercise, index, set, attempt,
                count, revision, if (existing?.ack != null) ManualSetOperation.EDIT else ManualSetOperation.COMPLETE)
            startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "MANUAL_SUBMIT")
                .putExtra("payload", WireCodec.encode(command.toEnvelope(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()))))
            manualStatus = "PENDING"; updateUi()
        }
        fun editor() {
            val draftPrefs = getSharedPreferences("manual-count-drafts", MODE_PRIVATE)
            val key = "$session:$set:$attempt"
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(4))
                hint = "횟수"
                setText(draftPrefs.getString(key, null) ?: existing?.command?.performedReps?.toString() ?: if (!manualRequired) reps.toString() else "")
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        draftPrefs.edit().putString(key, s.toString()).apply()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(input) }
            controls.addView(LinearLayout(this).apply {
                listOf("−" to -1, "+" to 1).forEach { (label, delta) ->
                    addView(this@MainActivity.label(label, 22f, WatchWorkoutFace.BONE, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(12, 8, 12, 8)
                        setOnClickListener { input.setText(((input.text.toString().toIntOrNull() ?: 0) + delta).coerceIn(0, 9_999).toString()) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
            val dialog = AlertDialog.Builder(this).setTitle("${index + 1}세트 · 목표 ${targetReps}회")
                .setView(controls).setNegativeButton("취소", null).setPositiveButton(if (editOnly) "적용" else "기록", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val count = input.text.toString().toIntOrNull()?.takeIf { it in 0..9_999 }
                    if (count == null) { input.error = "실제 횟수를 입력해 주세요"; return@setOnClickListener }
                    submit(count)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
        if (!editOnly && existing?.ack?.status == ManualSetStatus.CONFLICT) {
            val phoneCount = existing.ack.currentReps
            AlertDialog.Builder(this).setTitle("어떤 횟수를 기록할까요?")
                .setItems(arrayOf("폰 ${phoneCount ?: "—"}회 유지", "워치 ${existing.command.performedReps}회 적용", "직접 수정")) { _, which ->
                    when (which) { 0 -> phoneCount?.let(::submit); 1 -> submit(existing.command.performedReps); else -> editor() }
                }.show()
        } else editor()
    }

    private inner class RestProgressRing : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = WatchWorkoutFace.YELLOW
        }
        private var startAtMs = 0L
        private var endAtMs = 0L
        private var progress: Float? = null
        /** 0 while the rest runs, 1 when it is paused: the yellow arc drops to 35 % (design 4-1), the rim behind it stays. */
        var fade = 0f
            set(value) { if (field != value) { field = value; invalidate() } }

        fun setWindow(startAtMs: Long, endAtMs: Long) {
            this.startAtMs = startAtMs
            this.endAtMs = endAtMs
            progress = null
            invalidate()
        }

        /** Measuring: the rim fills toward the target; past the target a second, darker lap builds on top. */
        fun setProgress(fraction: Float) {
            if (progress == fraction) return
            val wasOver = (progress ?: 0f) > 1f
            progress = fraction
            val nowOver = fraction > 1f
            lapTo(if (nowOver) (fraction - 1f).coerceIn(0f, 1f) else 0f)
            if (wasOver != nowOver) sweepTo(if (nowOver) 1f else 0f)
            else if (!nowOver && sweep != 0f) { sweepAnimator?.cancel(); sweep = 0f }
            else if (nowOver && sweep != 1f && sweepAnimator?.isRunning != true) sweep = 1f
            invalidate()
        }

        /** 0 is a plain sage ring, 1 is the whole ring amber; it changes in the same 0.18 s as the number rolls. */
        private var sweep = 0f
        private var lapShown = 0f
        private var lapAnimator: android.animation.ValueAnimator? = null
        private fun lapTo(target: Float) {
            if (target == lapShown) return
            lapAnimator?.cancel()
            if (Moment.reducedMotion(context) || !isAttachedToWindow) { lapShown = target; return }
            lapAnimator = android.animation.ValueAnimator.ofFloat(lapShown, target).apply {
                duration = 180; interpolator = android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f)
                addUpdateListener { lapShown = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        private var sweepAnimator: android.animation.ValueAnimator? = null
        private fun sweepTo(target: Float) {
            sweepAnimator?.cancel()
            if (Moment.reducedMotion(context) || !isAttachedToWindow) { sweep = target; return }
            sweepAnimator = android.animation.ValueAnimator.ofFloat(sweep, target).apply {
                duration = 180; interpolator = android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f)
                addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDetachedFromWindow() { sweepAnimator?.cancel(); lapAnimator?.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val total = endAtMs - startAtMs
            val remaining = (endAtMs - now).coerceAtLeast(0L)
            val measured = progress
            if (measured == null && (total <= 0L || remaining <= 0L)) return
            val fraction = if (measured != null) measured.coerceIn(0f, 1f) else (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            // A real round Wear display is normally square. Some emulator profiles
            // expose a tall canvas, so use the top square display region instead of
            // centering the ring in the entire scrollable-height surface.
            val canvasWidth = width.toFloat()
            val canvasHeight = height.toFloat()
            val roundDiameter = if (canvasHeight > canvasWidth * 1.15f) {
                canvasWidth
            } else {
                minOf(canvasWidth, canvasHeight)
            }
            val roundTop = if (canvasHeight > canvasWidth * 1.15f) 0f else {
                (canvasHeight - roundDiameter) / 2f
            }
            // The same 432 grid as the reference and moment screens: r=209, stroke=7.
            val scale = roundDiameter / 432f
            paint.strokeWidth = 7f * scale
            val inset = 7f * scale
            val left = (canvasWidth - roundDiameter) / 2f + inset
            val top = roundTop + inset
            val right = (canvasWidth + roundDiameter) / 2f - inset
            val bottom = roundTop + roundDiameter - inset
            // -90 degrees is 12 o'clock: the ring advances clockwise from the
            // top-center of the circular watch face.
            val oval = RectF(left, top, right, bottom)
            if (measured != null) {
                paint.color = WatchWorkoutFace.TRACK; canvas.drawArc(oval, -90f, 360f, false, paint)
                // Sage fills up to a full ring at the target. Past it the whole ring turns amber, quickly and
                // smoothly, together with the number, and a deeper amber lap grows from 12 o'clock at the same time.
                paint.color = WatchWorkoutFace.SAGE
                if (fraction > 0.003f) canvas.drawArc(oval, -90f, 360f * fraction, false, paint)
                if (sweep > 0.003f) {
                    paint.color = WatchWorkoutFace.ORANGE; paint.alpha = (255 * sweep).toInt()
                    canvas.drawArc(oval, -90f, 360f, false, paint); paint.alpha = 255
                }
                if (lapShown > 0.003f) { paint.color = WatchWorkoutFace.DEEP_AMBER; canvas.drawArc(oval, -90f, 360f * lapShown, false, paint) }
                paint.color = WatchWorkoutFace.YELLOW
                return
            }
            // The unlit rim stays visible behind the shrinking rest time, as in the design.
            paint.color = WatchWorkoutFace.TRACK; canvas.drawArc(oval, -90f, 360f, false, paint)
            paint.color = WatchWorkoutFace.YELLOW
            paint.alpha = (255 * (1f - .65f * fade)).toInt()
            canvas.drawArc(oval, -90f, 360f * fraction, false, paint)
            paint.alpha = 255
            postInvalidateOnAnimation()
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != null) setStroke(strokeDp, strokeColor)
        }
}

private const val WORKING_BRIGHTNESS = 0.1f
