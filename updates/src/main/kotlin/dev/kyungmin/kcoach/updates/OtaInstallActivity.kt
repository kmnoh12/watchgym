package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Only used when the install permission still has to be granted, or when the install is started from a notification.
 * It has no content and a transparent window, so nothing flashes; the usual tap commits without opening it at all.
 */
class OtaInstallActivity : Activity() {
    private lateinit var coordinator: OtaCoordinator
    private var id = ""
    private var permissionOpened = false
    private var preparing = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = OtaCoordinator.get(this)
        id = intent.getStringExtra("id").orEmpty()
        permissionOpened = savedInstanceState?.getBoolean("permissionOpened") == true
        if (coordinator.store.local()?.id != id) { finish(); return }
        current = WeakReference(this)
        if (!packageManager.canRequestPackageInstalls() && !permissionOpened) {
            permissionOpened = true
            coordinator.change(id) { it.copy(phase = OtaPhase.USER_ACTION_REQUIRED, message = "앱 설치 권한 확인 필요") }
            startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:$packageName")), 7310)
        }
    }
    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        if (::coordinator.isInitialized && packageManager.canRequestPackageInstalls() && !preparing) prepare()
    }
    @Deprecated("Permission confirmation returns through the platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7310 && !packageManager.canRequestPackageInstalls()) {
            coordinator.change(id) { it.copy(phase = OtaPhase.FAILED, message = "설치 권한이 허용되지 않았어요") }
            finish()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("permissionOpened", permissionOpened); super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (current.get() === this) current.clear(); if (::coordinator.isInitialized) coordinator.installerClosed(); super.onDestroy() }
    private fun prepare() {
        preparing = true
        executor.execute {
            runCatching {
                val state = requireNotNull(coordinator.store.local()?.takeIf { it.id == id })
                if (coordinator.store.stage() == OtaStage.NONE) coordinator.store.setStage(OtaStage.COMMIT)
                coordinator.change(id) { it.copy(phase = OtaPhase.INSTALLING, message = "운영체제 설치 확인 대기") }
                OtaInstaller.commit(this, coordinator.store, state)
                if (coordinator.store.stage() != OtaStage.OS) coordinator.store.setStage(OtaStage.RUN)
            }.onFailure { error ->
                coordinator.store.setSessionId(-1); coordinator.store.setStage(OtaStage.NONE)
                coordinator.change(id) { it.copy(phase = OtaPhase.FAILED, message = error.message.orEmpty().take(300)) }
                runOnUiThread { finish() }
            }
        }
    }
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private var current = WeakReference<OtaInstallActivity>(null)
        fun showConfirmation(id: String, intent: Intent): Boolean {
            val activity = current.get()?.takeUnless { it.id != id || it.isFinishing || it.isDestroyed } ?: return false
            activity.runOnUiThread { activity.startActivity(intent); activity.finish() }
            return true
        }
    }
}
