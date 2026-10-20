package dev.kyungmin.kcoach.phone

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hands a rendered result screen to the share sheet or the phone's gallery as a PNG. */
internal object ResultImageExport {
    private fun fileName(title: String): String {
        val safe = title.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(24).ifBlank { "운동" }
        return "워치짐-$safe-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.KOREA).format(Date())}"
    }

    fun share(context: Context, bitmap: Bitmap, title: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, fileName(title) + ".png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "운동 결과 공유")
        if (context is Activity) context.startActivity(chooser)
        else context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun save(context: Context, bitmap: Bitmap, title: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(title) + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/워치짐")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("image insert failed")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("image stream missing")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)
}
