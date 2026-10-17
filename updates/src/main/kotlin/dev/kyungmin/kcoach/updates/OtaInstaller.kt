package dev.kyungmin.kcoach.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * The platform install session, split so that the tap only has to commit: [stage] verifies the downloaded file and
 * writes it into an uncommitted session as soon as the download is done, and [commit] finishes it (or, when nothing
 * was staged, does everything in one go as before).
 */
internal object OtaInstaller {
    /** Best effort: a failure only means the tap does the work itself. */
    fun stage(context: Context, store: OtaStore, state: OtaDeviceState, alreadyVerified: Boolean = false): Boolean = runCatching {
        if (isStaged(context, store, state.id)) return@runCatching true
        val apk = store.apk(state.id); require(apk.isFile)
        if (!alreadyVerified) require(sha256(apk) == state.sha256)
        discardStaged(context, store)
        store.setStaged(state.id, createSession(context, apk))
        true
    }.getOrElse { discardStaged(context, store); false }

    fun isStaged(context: Context, store: OtaStore, id: String): Boolean {
        val staged = store.staged() ?: return false
        return staged.first == id && context.packageManager.packageInstaller.getSessionInfo(staged.second) != null
    }

    fun discardStaged(context: Context, store: OtaStore) {
        store.staged()?.let { (_, session) -> runCatching { context.packageManager.packageInstaller.abandonSession(session) } }
        store.clearStaged()
    }

    /** Commits the staged session, or verifies, writes and commits when there is none. Returns the session id. */
    fun commit(context: Context, store: OtaStore, state: OtaDeviceState): Int {
        val installer = context.packageManager.packageInstaller
        var session = store.staged()?.takeIf { it.first == state.id && installer.getSessionInfo(it.second) != null }?.second
        if (session == null) {
            val apk = store.apk(state.id); require(apk.isFile) { "업데이트 파일이 없어 다시 확인해야 해요" }
            require(sha256(apk) == state.sha256) { "APK 파일 검증 실패" }
            session = createSession(context, apk)
        }
        store.clearStaged(); store.setSessionId(session)
        val callback = Intent(context, OtaInstallReceiver::class.java).setAction("dev.kyungmin.kcoach.OTA_RESULT").putExtra("id", state.id)
        val pending = PendingIntent.getBroadcast(context, session, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        installer.openSession(session).use { it.commit(pending.intentSender) }
        return session
    }

    /**
     * On Android 12 and later this asks for an update without the confirmation screen. The platform grants it only when
     * its own conditions hold (an update of this app by itself, with the update permission); otherwise it falls back to
     * the confirmation screen, which the receiver already handles.
     */
    private fun createSession(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val session = installer.createSession(params)
        try {
            installer.openSession(session).use { open ->
                apk.inputStream().use { input ->
                    open.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output); open.fsync(output) }
                }
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(session) }
            throw error
        }
        return session
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
