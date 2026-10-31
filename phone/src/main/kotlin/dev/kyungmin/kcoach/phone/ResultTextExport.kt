package dev.kyungmin.kcoach.phone

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ResultTextExport {
    fun save(context: Context, sessionId: String): Result<Unit> = runCatching {
        val store = FinishedWorkoutStore(context)
        val record = store.evidenceForSessions(setOf(sessionId)).single()
        val text = format(record, store.usePounds(sessionId))
        val title = record.title.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(24).ifBlank { "운동" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "워치짐-$title-${System.currentTimeMillis()}.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/워치짐")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create text file")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Cannot open text file")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) > 0)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    internal fun format(record: FinishedWorkoutStore.Evidence, pounds: Boolean): String = buildString {
        val done = record.snapshot.plannedSets.filter { it.completed }
        val unit = if (pounds) "lb" else "kg"
        fun weight(kg: Double): String {
            val value = kg * if (pounds) 2.20462 else 1.0
            return if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.1f", value)
        }
        appendLine(record.title)
        appendLine(Instant.ofEpochMilli(record.snapshot.startedAtMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")))
        appendLine("${record.minutes}분 · ${done.size}세트")
        done.groupBy { it.exerciseIndex }.values.forEach { sets ->
            appendLine()
            appendLine(sets.first().exerciseName.ifBlank { "운동" })
            sets.sortedBy { it.setIndex }.forEachIndexed { index, set ->
                val type = SetType.entries.firstOrNull { it.storageValue == set.setType }
                val label = if (set.setType == "NORMAL") "${index + 1}세트" else "${index + 1}세트 (${type?.displayName ?: set.setType})"
                val load = set.weightKg?.let { "${weight(it)} $unit" } ?: "중량 미입력"
                val reps = set.performedReps?.let { "${it}회" } ?: "횟수 미입력"
                appendLine("$label · $load × $reps${set.rpe?.takeIf { it.isNotBlank() }?.let { " · RPE $it" }.orEmpty()}")
            }
        }
    }
}
