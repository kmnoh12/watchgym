package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtaInstallActivityTest {
    private fun prepare(): OtaCoordinator {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = app.packageName; longVersionCode = 5; versionName = "five"
        })
        val link = object : OtaLink {
            override fun command(command: OtaCommand) {}
            override fun state(state: OtaDeviceState) {}
            override fun recover(receive: (String, String, String) -> Unit) {}
        }
        val c = OtaCoordinator.initialize(app, OtaConfig(OtaRole.WATCH, "https://example.invalid/manifest"), link) {}
        c.store.begin(OtaCommand("request", "phone", 1, OtaOperation.CHECK),
            OtaDeviceState("request", c.store.instance(), 1, 1, OtaPhase.READY, 5, "five", 6, "six", "a".repeat(64)))
        c.store.apk("request").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        return c
    }
    private fun launch() = Robolectric.buildActivity(OtaInstallActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), OtaInstallActivity::class.java).putExtra("id", "request"))

    @Test fun newInstallerRestoresPermissionWaitAndVerifiesTheFileAfterPermissionReturns() {
        val c = prepare(); val first = launch().setup()
        val settings = shadowOf(first.get()).nextStartedActivityForResult
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.intent.action)
        assertEquals(OtaPhase.USER_ACTION_REQUIRED, c.store.local()!!.phase)
        val bundle = Bundle(); first.saveInstanceState(bundle).pause().stop().destroy()
        val restored = launch().create(bundle).start().resume().visible()
        assertNull(shadowOf(restored.get()).nextStartedActivityForResult)
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(true)
        restored.pause().resume()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (c.store.local()!!.phase != OtaPhase.FAILED && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertEquals(OtaPhase.FAILED, c.store.local()!!.phase)
        assertEquals("APK 파일 검증 실패", c.store.local()!!.message)
        assertTrue(RuntimeEnvironment.getApplication().packageManager.packageInstaller.allSessions.isEmpty())
        assertEquals(5L, c.store.local()!!.installedCode)
        restored.pause().stop().destroy()
    }
    @Test fun committedPermissionFlowClosesEvenWhenTheSystemRejectsThePackage() {
        val c = prepare()
        val bytes = c.store.apk("request").readBytes()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        c.change("request") { it.copy(sha256 = hash) }
        val controller = launch().setup()
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(true)
        controller.pause().resume()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!controller.get().isFinishing && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        // Robolectric sends the commit callback without an OS status for this fake APK.
        // Reaching the receiver's rejection proves commit ran; the transparent window must still close.
        assertEquals(OtaPhase.FAILED, c.store.local()!!.phase)
        assertEquals("설치가 취소되거나 실패했어요", c.store.local()!!.message)
        assertEquals("A fast OS result must not be overwritten by RUN after commit returns", OtaStage.NONE, c.store.stage())
        assertTrue("The permission window must not intercept touches after the install starts", controller.get().isFinishing)
        controller.pause().stop().destroy()
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(false)
    }

    @Test fun deniedPermissionBecomesARecoverableFailureInsteadOfAnInstallerOrSuccess() {
        val c = prepare(); val controller = launch().setup()
        val settings = shadowOf(controller.get()).nextStartedActivityForResult
        shadowOf(controller.get()).receiveResult(settings.intent, Activity.RESULT_CANCELED, null)
        assertEquals(OtaPhase.FAILED, c.store.local()!!.phase)
        assertEquals(5L, c.store.local()!!.installedCode)
        assertEquals(-1, c.store.sessionId())
        controller.pause().stop().destroy()
    }
}
