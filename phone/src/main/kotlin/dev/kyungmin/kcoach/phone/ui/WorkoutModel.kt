package dev.kyungmin.kcoach.phone.ui

/** Read-only snapshot of the active workout that the Compose screen renders. */
data class WorkoutUiModel(
    val routineName: String,
    val isPreview: Boolean,
    val paused: Boolean,
    val startedAtMs: Long,
    val totalPausedMs: Long,
    val pausedSinceMs: Long,
    val exerciseIndex: Int,
    val exercises: List<ExerciseUi>,
    val usePounds: Boolean,
    val restStartAtMs: Long,
    val restEndAtMs: Long,
    val pausedRestRemainingMs: Long,
    val nextSetText: String?,
    /** The just-completed set whose rest is running (the execution cursor may already point ahead). */
    val restingSet: Pair<Int, Int>? = null,
    /** A record the just-finished set broke; the screen shows it briefly. */
    val recordBanner: RecordBannerUi? = null,
    /** Swipe a set row left to reveal its delete button. */
    val swipeDelete: Boolean = true,
)

data class ExerciseUi(
    val id: String,
    val name: String,
    val muscles: String,
    val weighted: Boolean,
    val restSeconds: Int,
    val targetReps: Int,
    val performedReps: Int,
    val completedWorkSets: Int,
    val workSetCount: Int,
    val currentSetIndex: Int,
    val sets: List<SetUi>,
    /** Done and planned volume in the displayed unit; 0 target means bodyweight only. */
    val volumeDone: Int,
    val volumeTarget: Int,
    val history: HistoryUi?,
)

/** What last time looked like and what to try next, for one exercise. */
data class HistoryUi(
    val lastLine: String,
    val lastVolume: Int,
    val suggestion: String?,
    val reason: String?,
    val applicable: Boolean,
)

data class SetUi(
    val uid: Int,
    val label: String,
    val weightText: String,
    val reps: Int?,
    val completed: Boolean,
    val warmup: Boolean,
    val grip: String,
    val restSeconds: Int,
    val customRest: Boolean,
    /** Wrist-sensor remark once the watch analysis for this set has arrived. */
    val insight: String? = null,
    /** The same set last time, e.g. "5×25"; shown small under the set number. */
    val previous: String? = null,
)

enum class SetField { WEIGHT, REPS }

interface WorkoutActions {
    fun onBack()
    fun onMenu()
    fun onExerciseInfo(exerciseIndex: Int)
    fun onPageSettled(exerciseIndex: Int)
    fun onEditValue(exerciseIndex: Int, setIndex: Int, field: SetField, value: String, applyAll: Boolean)
    fun onEditFinished()
    fun onToggleSet(exerciseIndex: Int, setIndex: Int)
    fun onSetMenu(exerciseIndex: Int, setIndex: Int)
    fun onAddSet(exerciseIndex: Int)
    fun onRemoveSet(exerciseIndex: Int, setIndex: Int)
    fun onUndoRemoveSet()
    fun onSetRest(exerciseIndex: Int, setIndex: Int)
    fun onAllSetsDone(exerciseIndex: Int)
    fun onAdjustRest(deltaSeconds: Int)
    fun onRestPreset(seconds: Int)
    fun onSkipRest()
    fun onTogglePause()
    fun onStop()
    fun onToggleUnit()
    fun onApplySuggestion(exerciseIndex: Int)
}
