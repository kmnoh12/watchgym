package dev.kyungmin.kcoach.wear

import android.app.Application
import dev.kyungmin.kcoach.updates.*

class KCoachApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalExitDiagnostics.install(this)
        // A crash from the previous run is only visible on the watch; forward it before anything else.
        Thread({ runCatching { CrashReportRelay.relay(this) } }, "crash-report-relay").start()
        OtaCoordinator.initialize(this, OtaConfig(OtaRole.WATCH, BuildConfig.UPDATE_MANIFEST_URL))
    }
}
