package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

internal data class RoutineLibrary(
    val routines: List<WorkoutRoutine>,
    val customExercises: List<ExerciseDefinition>,
)

/** One transaction for definitions and plans; a failed read never permits a default overwrite. */
internal class RoutineLibraryStore(
    file: File,
    private val write: (FileOutputStream, ByteArray) -> Unit = { stream, bytes -> stream.write(bytes) },
) {
    constructor(context: Context) : this(File(context.filesDir, "routine-library.json"))
    private val file = AtomicFile(file)
    private var readable = false

    fun load(): Result<RoutineLibrary?> = synchronized(lock) {
        runCatching {
            val bytes = try {
                file.openRead().use { it.readBytes() }
            } catch (error: FileNotFoundException) {
                // Missing is a first run only if neither the base nor recovery file exists.
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw error
                null
            }
            bytes?.let { decode(it.toString(Charsets.UTF_8)) }
        }.also { readable = it.isSuccess }
    }

    fun save(library: RoutineLibrary): Result<Unit> = synchronized(lock) { runCatching {
        check(readable) { "Routine library must be loaded successfully before saving" }
        // Also guard against a file that became unreadable since the initial load.
        load().getOrThrow()
        val bytes = encode(library).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            write(stream, bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    } }

    private fun encode(library: RoutineLibrary): String = JSONObject().apply {
        put("version", 1)
        put("customExercises", JSONArray(library.customExercises.map(::encodeExercise)))
        put("routines", JSONArray(library.routines.map { routine -> JSONObject().apply {
            put("id", routine.id); put("name", routine.name); put("muscles", routine.muscles)
            put("lastPerformed", routine.lastPerformed)
            put("lastPerformedEpochDay", routine.lastPerformedEpochDay ?: JSONObject.NULL)
            put("exercises", JSONArray(routine.exercises.map { item -> JSONObject().apply {
                put("exercise", encodeExercise(item.exercise)); put("restSeconds", item.restSeconds)
                put("sets", JSONArray(item.sets.map { set -> JSONObject().apply {
                    put("weightKg", set.weightKg ?: JSONObject.NULL); put("targetReps", set.targetReps)
                    put("setType", set.setType.storageValue); put("gripType", set.gripType)
                    put("rpe", set.rpe ?: JSONObject.NULL); put("restSeconds", set.restSeconds ?: JSONObject.NULL)
                    put("setId", set.setId); put("attemptId", set.attemptId); put("identityRequired", set.identityRequired)
                    // Routine plans do not carry session completion or measured results.
                } }))
            } }))
        } }))
    }.toString().also { decode(it) }

    private fun decode(text: String): RoutineLibrary {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "Unsupported routine library version" }
        val custom = root.getJSONArray("customExercises").objects().map(::decodeExercise)
        require(custom.map { it.id }.distinct().size == custom.size)
        require(custom.none { it.id in WorkoutData.builtInExerciseIds })
        val routines = root.getJSONArray("routines").objects().map { routine ->
            WorkoutRoutine(
                routine.requiredString("id"), routine.requiredString("name"), routine.getString("muscles"),
                routine.getString("lastPerformed"),
                routine.getJSONArray("exercises").objects().map { item ->
                    RoutineExercise(decodeExercise(item.getJSONObject("exercise")),
                        item.getJSONArray("sets").objects().map { set ->
                            val type = set.getString("setType")
                            require(SetType.entries.any { it.storageValue == type })
                            PlannedSet(
                                weightKg = if (set.isNull("weightKg")) null else set.getDouble("weightKg").also { require(it.isFinite()) },
                                targetReps = set.getInt("targetReps"), setType = SetType.fromStorage(type),
                                gripType = set.getString("gripType"), rpe = if (set.isNull("rpe")) null else set.getString("rpe"),
                                restSeconds = if (set.isNull("restSeconds")) null else set.getInt("restSeconds"),
                                setId = set.requiredString("setId"), attemptId = set.requiredString("attemptId"),
                                identityRequired = set.getBoolean("identityRequired"),
                            )
                        }.toMutableList(), item.getInt("restSeconds")).also {
                            if (it.restSeconds == 90) it.restSeconds = ExerciseDefaults.restSeconds(it.exercise)
                        }
                }.toMutableList(),
                if (routine.isNull("lastPerformedEpochDay")) null else routine.getLong("lastPerformedEpochDay"),
            )
        }
        require(routines.map { it.id }.distinct().size == routines.size)
        return RoutineLibrary(routines, custom)
    }

    private fun encodeExercise(exercise: ExerciseDefinition) = JSONObject().apply {
        put("id", exercise.id); put("name", exercise.name); put("muscles", exercise.muscles)
        put("bodyCategory", exercise.bodyCategory); put("equipment", exercise.equipment)
        put("favorite", exercise.favorite); put("pattern", exercise.pattern); put("family", exercise.family)
    }

    private fun decodeExercise(value: JSONObject) = ExerciseDefinition(
        value.requiredString("id"), value.requiredString("name"), value.getString("muscles"),
        value.getString("bodyCategory"), value.getString("equipment"), value.getBoolean("favorite"),
        value.getString("pattern"), value.getString("family"),
    ).let { if (it.id in setOf("weighted_dip", "weighted_pull_up")) it.copy(equipment = "기타") else it }

    private fun JSONObject.requiredString(key: String) = getString(key).also { require(it.isNotBlank()) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private companion object { val lock = Any() }
}
