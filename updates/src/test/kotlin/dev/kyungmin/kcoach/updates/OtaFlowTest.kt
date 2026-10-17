package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtaFlowTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val hash by lazy { java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private class Link : OtaLink {
        val commands = mutableListOf<OtaCommand>()
        override fun command(command: OtaCommand) { commands.add(command) }
        override fun state(state: OtaDeviceState) {}
        override fun recover(receive: (String, String, String) -> Unit) {}
    }
    private fun version(code: Long) = shadowOf(app.packageManager).installPackage(
        PackageInfo().apply { packageName = app.packageName; longVersionCode = code; versionName = "v$code" })
    private fun state(phase: OtaPhase, id: String = "id", progress: Int = 0, message: String = "") = OtaDeviceState(id, "inst", 1, 1, phase, 5, "v5",
        target(phase), if (target(phase) > 0) "v${target(phase)}" else "", if (target(phase) > 0) hash else "", progress, message)
    private fun target(phase: OtaPhase) = when (phase) {
        OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING, OtaPhase.DOWNLOADING -> 6L
        OtaPhase.UP_TO_DATE -> 5L
        else -> 0L
    }
    private fun readyPhone(): Pair<OtaCoordinator, Link> {
        version(5); val link = Link()
        val c = OtaCoordinator.initialize(app, OtaConfig(OtaRole.PHONE, "https://example.invalid/manifest"), link) {}
        c.checkBoth()
        val id = c.store.local()!!.id
        c.store.apk(id).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        c.change(id) { it.copy(phase = OtaPhase.READY, targetCode = 6, targetName = "v6", sha256 = hash, progress = 100) }
        return c to link
    }
    /** The platform shadow keeps this flag across tests; the older tests assume the default (not granted). */
    @After fun resetInstallPermission() { shadowOf(app.packageManager).setCanRequestPackageInstalls(false) }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }

    @Test fun theCardKeepsTwoRowsAndOneButtonWhateverTheState() {
        val ready = OtaPresentation.card(state(OtaPhase.READY), state(OtaPhase.READY), true, false, OtaStage.NONE, 0)
        assertEquals(OtaAction.INSTALL, ready.action); assertEquals("설치", ready.actionLabel)
        assertEquals("폰", ready.phone.device); assertEquals("워치", ready.watch!!.device)
        assertTrue(ready.headline.contains("v6"))
        val asking = OtaPresentation.card(state(OtaPhase.READY), state(OtaPhase.READY), true, true, OtaStage.PEER, 0)
        assertEquals(OtaAction.WORKING, asking.action); assertEquals("설치 중", asking.actionLabel)
        assertEquals("설치 요청 중", asking.watch!!.text)
        val running = OtaPresentation.card(state(OtaPhase.INSTALLING), state(OtaPhase.READY), true, false, OtaStage.RUN, 40)
        assertEquals(0.4f, running.phone.progress!!, 0.001f)
        val os = OtaPresentation.card(state(OtaPhase.USER_ACTION_REQUIRED), null, false, false, OtaStage.OS, 0)
        assertNull(os.watch); assertEquals("설치 확인", os.phone.text); assertEquals(OtaAction.WORKING, os.action)
        val downloading = OtaPresentation.card(state(OtaPhase.DOWNLOADING, progress = 30), null, false, false, OtaStage.NONE, 0)
        assertEquals("확인 중", downloading.actionLabel); assertEquals(0.3f, downloading.phone.progress!!, 0.001f)
        val failed = OtaPresentation.card(state(OtaPhase.FAILED, message = "x"), null, false, false, OtaStage.NONE, 0)
        assertEquals(OtaAction.RETRY, failed.action)
        val upToDate = OtaPresentation.card(state(OtaPhase.UP_TO_DATE), state(OtaPhase.UP_TO_DATE), true, false, OtaStage.NONE, 0)
        assertEquals(OtaAction.CHECK, upToDate.action); assertEquals("최신 버전이에요", upToDate.headline)
    }

    @Test fun theDownloadStagesTheSessionSoTheTapOnlyCommitsItWithNoScreenInBetween() {
        val (c, _) = readyPhone()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val id = c.store.local()!!.id
        assertTrue(OtaInstaller.stage(app, c.store, c.store.local()!!, alreadyVerified = true))
        val staged = c.store.staged()!!
        assertEquals(id, staged.first)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        waitFor { c.store.sessionId() >= 0 }
        assertEquals("The committed session is the one staged before the tap", staged.second, c.store.sessionId())
        assertNull("No extra screen opens", shadowOf(activity.get()).nextStartedActivity)
        assertNull(c.store.staged())
        assertEquals(OtaPhase.INSTALLING, c.store.local()!!.phase)
        assertTrue(c.store.stage() == OtaStage.RUN || c.store.stage() == OtaStage.OS)
        activity.pause().stop().destroy()
    }

    @Test fun withoutAStagedSessionTheTapStillVerifiesAndCommits() {
        val (c, _) = readyPhone()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        waitFor { c.store.sessionId() >= 0 }
        assertTrue(c.store.sessionId() >= 0)
        assertEquals(OtaPhase.INSTALLING, c.store.local()!!.phase)
        activity.pause().stop().destroy()
    }

    @Test fun aBrokenFileNeverStagesASession() {
        val (c, _) = readyPhone()
        val id = c.store.local()!!.id
        c.store.apk(id).writeBytes(byteArrayOf(9, 9))
        assertFalse(OtaInstaller.stage(app, c.store, c.store.local()!!))
        assertNull(c.store.staged())
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
    }

    @Test fun ifTheWatchDoesNotAnswerThePhoneInstallsAfterEightSecondsAndKeepsTheWatchRequest() {
        val (c, link) = readyPhone()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val id = c.store.local()!!.id
        val command = c.store.command()!!
        c.receive(OtaDataLayer.STATE_PATH, OtaDeviceState(id, "watch-instance", 1, command.sequence, OtaPhase.READY, 5, "w5", 6, "w6", hash, 100).encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        assertEquals(OtaOperation.INSTALL, link.commands.last().operation)
        assertEquals(OtaStage.PEER, c.store.stage()); assertTrue(c.store.waitingForPeer())
        idle(7_000); assertEquals("Still waiting at 7 s", OtaStage.PEER, c.store.stage())
        idle(1_500)
        waitFor { c.store.sessionId() >= 0 }
        assertFalse(c.store.waitingForPeer())
        assertEquals(OtaOperation.INSTALL, c.store.command()!!.operation)
        assertTrue(c.store.sessionId() >= 0)
        activity.pause().stop().destroy()
    }

    @Test fun failedWatchRequestReleasesThePhoneAndAllowsRetry() {
        val (c, _) = readyPhone()
        val id = c.store.local()!!.id
        c.receive(OtaDataLayer.STATE_PATH, OtaDeviceState(id, "watch-instance", 1, c.store.command()!!.sequence,
            OtaPhase.READY, 5, "w5", 6, "w6", hash, 100).encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        val sequence = c.store.command()!!.sequence
        c.receive(OtaDataLayer.STATE_PATH, OtaDeviceState(id, "watch-instance", 2, sequence,
            OtaPhase.FAILED, 5, "w5", message = "취소됨").encode(), "watch-node")
        assertFalse(c.store.waitingForPeer())
        assertEquals(OtaStage.NONE, c.store.stage())
        assertEquals(OtaAction.INSTALL, c.ui().card!!.action)
        idle(9_000)
        assertEquals(-1, c.store.sessionId())
        activity.pause().stop().destroy()
    }

    @Test fun installationStaysBusyUntilItFinishesOrFails() {
        val (c, _) = readyPhone()
        c.change(c.store.local()!!.id) { it.copy(phase = OtaPhase.INSTALLING) }
        c.store.setStage(OtaStage.RUN)
        assertTrue(c.ui().busy)
        assertFalse(c.ui().readyToInstall)
        val command = c.store.command()!!.id
        c.checkBoth()
        assertEquals(command, c.store.command()!!.id)
    }

    @Test fun recoveringTheInstalledVersionBeforeTheBroadcastStillSavesTheNotice() {
        val (c, _) = readyPhone()
        version(6)
        c.recover()
        assertEquals(OtaPhase.INSTALLED, c.store.local()!!.phase)
        assertEquals("v6", c.store.notice()?.to)
        c.store.clearNotice()
        c.afterReplaced()
        assertNull("A late broadcast must not show the banner twice", c.store.notice())
    }

    @Test fun readyPhoneCanInstallWhileTheWatchIsStillDownloading() {
        val card = OtaPresentation.card(state(OtaPhase.READY), state(OtaPhase.DOWNLOADING, progress = 20),
            true, false, OtaStage.NONE, 0)
        assertEquals(OtaAction.INSTALL, card.action)
    }

    @Test fun watchOnlyInstallRemainsBusyWhileItsAcknowledgementIsPending() {
        val (c, _) = readyPhone()
        val id = c.store.local()!!.id
        c.change(id) { it.copy(phase = OtaPhase.UP_TO_DATE, targetCode = 5) }
        c.receive(OtaDataLayer.STATE_PATH, OtaDeviceState(id, "watch-instance", 1, c.store.command()!!.sequence,
            OtaPhase.READY, 5, "w5", 6, "w6", hash, 100).encode(), "watch-node")
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        c.installBoth(activity.get())
        assertTrue(c.ui().busy)
        assertFalse(c.ui().readyToInstall)
        assertEquals(OtaAction.WORKING, c.ui().card!!.action)
        assertEquals("설치 요청 중", c.ui().card!!.watch!!.text)
        activity.pause().stop().destroy()
    }

    @Test fun afterTheAppIsReplacedTheFirstScreenGetsOneNoticeAndTheStageIsCleared() {
        val (c, _) = readyPhone()
        c.store.setStage(OtaStage.RUN)
        version(6)
        c.afterReplaced()
        val notice = c.store.notice()!!
        assertEquals("v5", notice.from); assertEquals("v6", notice.to)
        assertEquals(OtaStage.NONE, c.store.stage())
        c.store.clearNotice(); assertNull(c.store.notice())
    }
}
