package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import androidx.work.*
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors

data class OtaConfig(val role: OtaRole, val manifestUrl: String)

/** App-scoped and backed by preferences/WorkManager/DataItems, rather than an Activity-owned thread. */
class OtaCoordinator private constructor(private val context: Context, val config: OtaConfig, private val link: OtaLink,
    private val schedule: ((String) -> Unit)?) {
    val store = OtaStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(OtaUi) -> Unit>()
    private var foreground = WeakReference<Activity>(null)
    private var openingInstaller = false
    private val installExecutor = Executors.newSingleThreadExecutor()
    /** The platform reports how far the committed session is; the card's bar follows it. */
    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {}
        override fun onBadgingChanged(sessionId: Int) {}
        override fun onActiveChanged(sessionId: Int, active: Boolean) {}
        override fun onFinished(sessionId: Int, success: Boolean) {}
        override fun onProgressChanged(sessionId: Int, progress: Float) {
            if (sessionId != store.sessionId() || store.stage() == OtaStage.NONE) return
            store.setInstallProgress((progress * 100).toInt()); emit()
        }
    }
    init { runCatching { context.packageManager.packageInstaller.registerSessionCallback(sessionCallback, main) } }
    /** If the watch does not take the request within this time, the phone installs anyway; the request stays stored and is re-sent. */
    private val peerTimeout = Runnable {
        if (store.waitingForPeer() && store.stage() == OtaStage.PEER) {
            store.waitForPeer(false); store.setStage(OtaStage.COMMIT); openInstaller(); emit()
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            recover()
            synchronized(observers) { if (observers.isNotEmpty()) main.postDelayed(this, 5_000L) }
        }
    }
    fun installed(): Pair<Long, String> {
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode to info.versionName.orEmpty()
    }
    fun ui(): OtaUi {
        if (store.local() == null) return OtaUi(if (config.role == OtaRole.WATCH) "업데이트는 폰 앱에서 확인해 주세요" else "폰·워치 업데이트를 확인할 수 있어요", false, false)
        val peerExpected = config.role == OtaRole.PHONE && store.command() != null
        val command = store.command()
        val awaitingPeer = store.waitingForPeer() || (command?.operation == OtaOperation.INSTALL &&
            (store.peer()?.commandSequence ?: 0L) < command.sequence)
        val summary = OtaPresentation.summarize(store.local(), store.peer(), peerExpected, awaitingPeer)
        val card = OtaPresentation.card(store.local(), store.peer(), peerExpected, awaitingPeer,
            store.stage(), store.installProgress())
        val installing = store.stage() != OtaStage.NONE || store.local()?.phase == OtaPhase.INSTALLING ||
            store.peer()?.phase == OtaPhase.INSTALLING
        return summary.copy(busy = summary.busy || installing, readyToInstall = summary.readyToInstall && !installing, card = card)
    }
    fun observe(observer: (OtaUi) -> Unit): () -> Unit {
        synchronized(observers) { observers.add(observer) }
        main.post { observer(ui()) }
        main.removeCallbacks(poll); main.postDelayed(poll, 5_000L)
        return { synchronized(observers) { observers.remove(observer); if (observers.isEmpty()) main.removeCallbacks(poll) } }
    }
    fun attach(activity: Activity) {
        foreground = WeakReference(activity)
        recover()
        if (store.local()?.phase == OtaPhase.USER_ACTION_REQUIRED && store.sessionId() < 0 && config.role == OtaRole.WATCH) openInstaller()
    }
    fun detach(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
    private fun emit() = main.post { synchronized(observers) { observers.toList() }.forEach { it(ui()) } }
    fun checkBoth() {
        require(config.role == OtaRole.PHONE)
        if (ui().busy) return
        val id = UUID.randomUUID().toString()
        val command = OtaCommand(id, store.instance(), store.nextSequence(), OtaOperation.CHECK)
        val (code, name) = installed()
        OtaInstaller.discardStaged(context, store)
        store.begin(command, OtaDeviceState(id, store.instance(), 1, command.sequence, OtaPhase.QUEUED, code, name))
        link.command(command); enqueue(id); emit()
    }
    fun installBoth(activity: Activity): String? {
        require(config.role == OtaRole.PHONE)
        foreground = WeakReference(activity)
        val peer = store.peer()
        val selfReady = store.local()?.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED)
        if (peer?.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED)) {
            val old = store.command() ?: return OtaMessages.CHECK_FIRST
            val command = OtaCommand(old.id, store.instance(), store.nextSequence(), OtaOperation.INSTALL,
                peer!!.instance, peer.targetCode, peer.sha256)
            store.saveCommand(command); store.waitForPeer(selfReady)
            store.setStage(if (selfReady) OtaStage.PEER else OtaStage.NONE)
            link.command(command); emit()
            if (selfReady) { main.removeCallbacks(peerTimeout); main.postDelayed(peerTimeout, PEER_WAIT_MS) }
            return "워치 설치 요청을 보관했어요. 워치의 설치 확인을 진행해 주세요"
        }
        if (selfReady) { store.setStage(OtaStage.COMMIT); openInstaller(); emit(); return "폰 설치를 시작해요" }
        return OtaMessages.NOTHING_TO_INSTALL
    }
    fun receive(path: String, text: String, node: String) {
        if (path == OtaDataLayer.COMMAND_PATH && config.role == OtaRole.WATCH) {
            val command = OtaCommand.decode(text)
            if (command.targetInstance.isNotEmpty() && command.targetInstance != store.instance()) return
            if (!store.receiveCommand(command)) {
                store.local()?.let(link::state)
                return
            }
            when (command.operation) {
                OtaOperation.CHECK -> {
                    val (code, name) = installed()
                    OtaInstaller.discardStaged(context, store)
                    store.begin(command, OtaDeviceState(command.id, store.instance(), 1, command.sequence, OtaPhase.QUEUED, code, name))
                    link.state(requireNotNull(store.local())); enqueue(command.id)
                }
                OtaOperation.INSTALL -> {
                    val current = store.local() ?: return
                    require(current.id == command.id && current.targetCode == command.versionCode && current.sha256 == command.sha256)
                    if (current.phase == OtaPhase.INSTALLED) { link.state(current); return }
                    require(current.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING))
                    store.saveCommand(command)
                    change(command.id) { it.copy(commandSequence = command.sequence, phase = OtaPhase.USER_ACTION_REQUIRED,
                        message = "폰에서 설치 요청됨 · 워치에서 설치 확인") }
                    main.post { openInstaller(allowExistingSession = true) }
                }
                OtaOperation.STATUS -> recover()
            }
            emit()
        } else if (path == OtaDataLayer.STATE_PATH && config.role == OtaRole.PHONE) {
            val state = OtaDeviceState.decode(text)
            if (!store.acceptPeer(state, node)) return
            val command = store.command()
            if (store.waitingForPeer() && command?.operation == OtaOperation.INSTALL && state.commandSequence == command.sequence &&
                state.phase in setOf(OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING, OtaPhase.INSTALLED)) {
                main.removeCallbacks(peerTimeout)
                store.waitForPeer(false); store.setStage(OtaStage.COMMIT); main.post { openInstaller() }
            }
            if (store.waitingForPeer() && command?.sequence == state.commandSequence && state.phase == OtaPhase.FAILED) {
                main.removeCallbacks(peerTimeout)
                store.waitForPeer(false); store.setStage(OtaStage.NONE)
            }
            emit()
        }
    }
    fun change(id: String, mutate: (OtaDeviceState) -> OtaDeviceState): OtaDeviceState? = synchronized(this) {
        val previous = store.local()?.takeIf { it.id == id } ?: return null
        val (code, name) = installed()
        val next = mutate(previous).copy(revision = previous.revision + 1, installedCode = code, installedName = name)
        if (!store.saveLocal(next)) return null
        // The tap's stages end with the transaction; they are never carried into the next check.
        if (next.phase in setOf(OtaPhase.INSTALLED, OtaPhase.FAILED, OtaPhase.UP_TO_DATE)) { store.setStage(OtaStage.NONE); store.setInstallProgress(0) }
        if (config.role == OtaRole.WATCH) link.state(next)
        emit(); next
    }
    fun recover() {
        val local = store.local()
        if (local != null) {
            val (code, _) = installed()
            if (local.targetCode > 0 && code >= local.targetCode && local.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING)) {
                afterReplaced()
                change(local.id) { it.copy(phase = OtaPhase.INSTALLED, installedCode = code, message = "실제 설치 버전 확인") }
                store.waitForPeer(false)
            }
            if (local.phase in setOf(OtaPhase.QUEUED, OtaPhase.CHECKING, OtaPhase.DOWNLOADING)) enqueue(local.id)
            if (config.role == OtaRole.WATCH) store.local()?.let(link::state)
        }
        if (config.role == OtaRole.PHONE) store.command()?.let(link::command)
        link.recover(::receive)
    }
    private fun enqueue(id: String) {
        if (schedule != null) { schedule.invoke(id); return }
        val request = OneTimeWorkRequestBuilder<OtaDownloadWorker>().setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(context).enqueueUniqueWork("kcoach-ota-$id", ExistingWorkPolicy.KEEP, request)
    }
    fun confirmInstall(activity: Activity) { foreground = WeakReference(activity); openInstaller(allowExistingSession = true) }
    internal fun installerClosed() { openingInstaller = false }
    internal fun openInstaller(allowExistingSession: Boolean = false) {
        val state = store.local() ?: return
        if (openingInstaller || state.phase !in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED) || (!allowExistingSession && store.sessionId() >= 0)) return
        // Fast path: with the install permission already granted, commit the staged session at once, with no screen in between.
        if (context.packageManager.canRequestPackageInstalls()) { commitNow(state); return }
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (activity == null) { OtaNotifications.offerInstall(context, state); return }
        openingInstaller = true
        runCatching { activity.startActivity(Intent(activity, OtaInstallActivity::class.java).putExtra("id", state.id)) }
            .onFailure { openingInstaller = false; change(state.id) { s -> s.copy(phase = OtaPhase.FAILED, message = "설치 화면을 열 수 없어요") } }
    }
    private fun commitNow(state: OtaDeviceState) {
        openingInstaller = true
        installExecutor.execute {
            try {
                if (store.stage() == OtaStage.NONE) store.setStage(OtaStage.COMMIT)
                change(state.id) { it.copy(phase = OtaPhase.INSTALLING, message = "운영체제 설치 확인 대기") }
                store.setStage(OtaStage.RUN)
                OtaInstaller.commit(context, store, state)
                emit()
            } catch (error: Throwable) {
                store.setSessionId(-1); store.setStage(OtaStage.NONE)
                change(state.id) { it.copy(phase = OtaPhase.FAILED, message = error.message.orEmpty().take(300).ifBlank { "설치를 시작하지 못했어요" }) }
            } finally { openingInstaller = false }
        }
    }

    /** Shows the system's confirmation screen over whatever is open; false when there is nowhere to show it. */
    internal fun showConfirmation(id: String, confirmation: Intent): Boolean {
        if (OtaInstallActivity.showConfirmation(id, confirmation)) return true
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        return runCatching { activity.runOnUiThread { activity.startActivity(confirmation) } }.isSuccess
    }

    /** The app was just replaced by an update: leave a note for the first screen to show once. */
    internal fun afterReplaced() {
        val local = store.local() ?: return
        val (code, name) = installed()
        if (local.targetCode > 0 && code >= local.targetCode &&
            local.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING)) {
            store.setNotice(OtaNotice(local.installedName, name, System.currentTimeMillis()))
        }
        store.setStage(OtaStage.NONE); store.setInstallProgress(0)
        OtaNotifications.clear(context)
    }

    companion object {
        private const val PEER_WAIT_MS = 8_000L
        private val instances = WeakHashMap<Context, OtaCoordinator>()
        fun initialize(context: Context, config: OtaConfig, link: OtaLink = OtaDataLayer(context.applicationContext),
            schedule: ((String) -> Unit)? = null): OtaCoordinator = synchronized(instances) {
            val app = context.applicationContext
            check(app.getSharedPreferences("companion-ota-config", Context.MODE_PRIVATE).edit()
                .putString("role", config.role.name).putString("manifest", config.manifestUrl).commit())
            instances[app] ?: OtaCoordinator(app, config, link, schedule).also { instances[app] = it }
        }
        fun get(context: Context): OtaCoordinator = synchronized(instances) {
            val app = context.applicationContext
            instances[app] ?: run {
                // WorkManager can recover persisted jobs during provider startup, before Application.onCreate.
                val prefs = app.getSharedPreferences("companion-ota-config", Context.MODE_PRIVATE)
                initialize(app, OtaConfig(OtaRole.valueOf(requireNotNull(prefs.getString("role", null))),
                    requireNotNull(prefs.getString("manifest", null))))
            }
        }
    }
}
