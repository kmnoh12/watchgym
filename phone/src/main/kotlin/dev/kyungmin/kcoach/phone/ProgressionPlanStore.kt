package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Persists application provenance, not success counters. Targets always derive from finished records. */
internal class ProgressionPlanStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("progression-plans", Context.MODE_PRIVATE)

    fun prefill(routine: WorkoutRoutine): Result<Unit> = synchronized(lock) { runCatching {
        val settings = ProgressionSettingsStore(context).load().getOrThrow()
        if (!settings.enabled) return@runCatching
        val history = ExerciseHistoryStore(context).load().all
        val edit = prefs.edit()
        val changes = mutableListOf<() -> Unit>()
        routine.exercises.forEachIndexed { index, item ->
            val key = "plan:${routine.id}:$index:${item.exercise.id}"
            val saved = prefs.getString(key, null)?.let(::JSONObject)
            val signature = item.sets.joinToString("|") { "${it.setId}:${it.setType}:${it.gripType}" }
            val source = history.filter { it.routineId == routine.id && it.exerciseIndex == index && it.exerciseId == item.exercise.id && it.equipment == item.exercise.equipment }
                .maxWithOrNull(compareBy<ExerciseLog> { it.epochDay }.thenBy { it.startedAtMs })
            // Include corrected evidence so a late manual correction is recalculated once.
            val sessionSource = source?.let { "${it.sessionId}:${it.epochDay}:${it.startedAtMs}" }.orEmpty()
            val evidenceToken = source?.let { ExerciseHistoryCodec.encode(listOf(it)) }.orEmpty()
            val sameLayout = saved?.optString("layout") == signature
            val oldTargets = if (sameLayout) saved?.optJSONArray("targets") else null
            val oldUpper = if (sameLayout) saved?.optJSONArray("upper") else null
            val sets = item.sets.mapIndexed { i, set ->
                val previous = oldTargets?.optJSONObject(i)
                val editedReps = previous != null && previous.getInt("reps") != set.targetReps
                val upper = if (editedReps) maxOf(ProgressionDefaults.upper(item.exercise.pattern) ?: 10, set.targetReps)
                    else oldUpper?.optInt(i)?.takeIf { it > 0 } ?: maxOf(ProgressionDefaults.upper(item.exercise.pattern) ?: 10, set.targetReps)
                ProgressionSetTarget("${set.setId}:${set.setType}:${set.gripType}", set.weightKg, set.targetReps, set.setType == SetType.WARMUP, upper)
            }
            val plan = ProgressionPlan(ProgressionKey(routine.id, item.exercise.id, index, item.exercise.equipment), sets, item.exercise.pattern, load(item))
            val increment = ProgressionIncrementStore(context).candidate(item.exercise.id, item.exercise.equipment,
                history.filter { it.exerciseId == item.exercise.id && it.equipment == item.exercise.equipment }.flatMap { it.sets }.mapNotNull { it.weightKg }).getOrThrow()
            val token = "$evidenceToken/${settings.speed.name}/${increment.kg}/${increment.needsConfirmation}/${increment.observedSteps}/${increment.confirmed}"
            val output = if (saved?.optString("source") == token || (saved != null && !sameLayout)) null
                else ProgressionEngine().nextTargets(plan, history, settings, increment)
            val manual = sets.mapIndexed { i, target ->
                val old = oldTargets?.optJSONObject(i)
                val edited = old != null && (target.weightKg != old.nullableDouble("weight") || target.targetReps != old.getInt("reps"))
                edited || (saved?.optString("sessionSource") == sessionSource && saved?.optJSONArray("manual")?.optBoolean(i) == true)
            }
            val next = sets.mapIndexed { i, target ->
                val old = oldTargets?.optJSONObject(i)
                val unchanged = old == null || (target.weightKg == old.nullableDouble("weight") && target.targetReps == old.getInt("reps"))
                if (unchanged && !manual[i]) output?.getOrNull(i) ?: target else target // Explicit user edits win.
            }
            val record = JSONObject().put("layout", signature).put("source", token).put("evidence", evidenceToken).put("sessionSource", sessionSource).put("manual", JSONArray(manual))
                .put("upper", JSONArray(next.map { it.upperReps }))
                .put("targets", JSONArray(next.map { JSONObject().put("weight", it.weightKg ?: JSONObject.NULL).put("reps", it.targetReps) }))
            edit.putString(key, record.toString())
            changes += { next.forEachIndexed { i, target -> item.sets[i].weightKg = target.weightKg; item.sets[i].targetReps = target.targetReps } }
        }
        check(edit.commit())
        changes.forEach { it() }
    } }

    /** Called before a session/offer is persisted; snapshot IDs may differ from routine IDs. */
    fun register(sessionId: String, routine: WorkoutRoutine, sessionSets: List<String>): Result<Unit> = synchronized(lock) { runCatching {
        val bodyWeight = UserProfileStore(context).deviceProfile()?.profile?.weightKg
        var offset = 0
        val rows = routine.exercises.flatMapIndexed { index, item ->
            val saved = prefs.getString("plan:${routine.id}:$index:${item.exercise.id}", null)?.let(::JSONObject)
                ?.takeIf { it.optString("layout") == item.sets.joinToString("|") { set -> "${set.setId}:${set.setType}:${set.gripType}" } }
            item.sets.mapIndexed { i, set -> JSONObject().put("exerciseIndex", index).put("setIndex", i)
                .put("sessionSetId", sessionSets[offset++]).put("bodyWeight", bodyWeight ?: JSONObject.NULL)
                .put("position", "${set.setId}:${set.setType}:${set.gripType}")
                .put("upper", saved?.optJSONArray("upper")?.optInt(i)?.takeIf { it > 0 }
                    ?: maxOf(ProgressionDefaults.upper(item.exercise.pattern) ?: 10, set.targetReps)) }
        }
        check(prefs.edit().putString("session:$sessionId", JSONArray(rows).toString()).commit())
    } }

    fun logs(snapshot: WorkoutSnapshot, day: Long, analyses: Map<String, SetAnalysisUi> = emptyMap()): List<ExerciseLog> = synchronized(lock) {
        val metadata = prefs.getString("session:${snapshot.sessionId}", null)?.let(::JSONArray)
        snapshot.plannedSets.groupBy { it.exerciseIndex }.map { (index, sets) ->
            ExerciseLog(sets.first().exerciseId, day, sets.sortedBy { it.setIndex }.map { set ->
                val row = metadata?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }
                    .firstOrNull { it.optString("sessionSetId") == set.setId } }
                val analysis = analyses[SetIdentity.key(set.exerciseId, set.setIndex, set.setId, set.attemptId)]
                // Matches the analyzer's existing adoption threshold; explicit manual input always wins.
                val eligible = set.repSource == SetRepSource.MANUAL || analysis?.repConfidence?.let { it.isFinite() && it >= 0.72 } != false
                LoggedSet(set.weightKg, set.performedReps ?: 0, set.targetReps, set.setType == "WARMUP", set.completed,
                    set.performedReps != null, row?.getString("position")?.substringBefore(":")?.let { "$it:${set.setType}:${set.gripType}" }.orEmpty(),
                    maxOf(row?.optInt("upper") ?: 0, ProgressionDefaults.upper(set.pattern) ?: 10, set.targetReps),
                    row?.let { if (it.isNull("bodyWeight")) null else it.getDouble("bodyWeight") }, load(set.exerciseId, set.exerciseName, set.pattern, set.equipment, sets.map { it.weightKg }), eligible)
            }, snapshot.sessionId, snapshot.startedAtMs, snapshot.routineId, index, sets.first().equipment)
        }
    }

    private fun load(item: RoutineExercise) = load(item.exercise.id, item.exercise.name, item.exercise.pattern, item.exercise.equipment, item.sets.map { it.weightKg })
    private fun load(id: String, name: String, pattern: String, equipment: String, weights: List<Double?>): ProgressionLoad = when {
        pattern in setOf("plank", "time", "duration") -> ProgressionLoad.TIME
        id.contains("assisted") || name.contains("어시스트") || name.contains("어시스티드") -> ProgressionLoad.ASSISTANCE
        id == "weighted_dip" || id == "weighted_pull_up" -> ProgressionLoad.WEIGHTED_BODYWEIGHT
        equipment == "맨몸" && weights.all { (it ?: 0.0) == 0.0 } -> ProgressionLoad.BODYWEIGHT
        equipment == "맨몸" -> ProgressionLoad.WEIGHTED_BODYWEIGHT
        else -> ProgressionLoad.EXTERNAL
    }
    private fun JSONObject.nullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
    companion object { private val lock = Any() }
}
