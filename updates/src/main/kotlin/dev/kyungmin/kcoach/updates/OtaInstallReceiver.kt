package dev.kyungmin.kcoach.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val coordinator = OtaCoordinator.get(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) { coordinator.afterReplaced(); coordinator.recover(); return }
        val state = coordinator.store.local()?.takeIf { it.id == intent.getStringExtra("id") } ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    coordinator.change(state.id) { it.copy(phase = OtaPhase.FAILED, message = "설치 확인 화면이 없어요") }; return
                }
                coordinator.store.setStage(OtaStage.OS)
                coordinator.change(state.id) { it.copy(phase = OtaPhase.USER_ACTION_REQUIRED, message = "운영체제 설치 확인 필요") }
                if (!coordinator.showConfirmation(state.id, confirmation))
                    OtaNotifications.offer(context, confirmation, "업데이트 설치를 확인해 주세요")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                coordinator.afterReplaced()
                val (code, _) = coordinator.installed()
                if (code >= state.targetCode) {
                    coordinator.change(state.id) { it.copy(phase = OtaPhase.INSTALLED, installedCode = code, message = "실제 설치 버전 확인") }
                    coordinator.store.setSessionId(-1); OtaNotifications.clear(context)
                } else coordinator.change(state.id) { it.copy(phase = OtaPhase.INSTALLING, message = "실제 설치 버전 응답 대기") }
            }
            else -> {
                coordinator.store.setSessionId(-1)
                coordinator.change(state.id) { it.copy(phase = OtaPhase.FAILED,
                    message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(300).ifBlank { "설치가 취소되거나 실패했어요" }) }
            }
        }
    }
}
