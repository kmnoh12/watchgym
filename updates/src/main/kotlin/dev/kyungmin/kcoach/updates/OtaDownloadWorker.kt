package dev.kyungmin.kcoach.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class OtaDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = OtaNotifications.foreground(applicationContext)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val coordinator = OtaCoordinator.get(applicationContext)
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        if (coordinator.store.local()?.id != id) return@withContext Result.success()
        try {
            setForeground(getForegroundInfo())
            coordinator.change(id) { it.copy(phase = OtaPhase.CHECKING, message = "최신 버전 확인 중") }
            val manifestConnection = connect(coordinator.config.manifestUrl)
            val json = try { manifestConnection.inputStream.bufferedReader().use {
                val text = it.readText(); require(text.length <= 16_384); JSONObject(text)
            } } finally { manifestConnection.disconnect() }
            val target = json.getLong("versionCode"); require(target > 0)
            val name = json.getString("versionName"); require(name.length in 1..100)
            val hash = json.getString("sha256").lowercase(); require(hash.matches(OTA_HASH))
            val location = json.getString("apkUrl")
            val source = URI(coordinator.config.manifestUrl); val asset = URI(location)
            require(asset.scheme == "https" && source.host.equals(asset.host, true) && source.port == asset.port)
            val expectedAsset = if (coordinator.config.role == OtaRole.PHONE) "kcoach-phone.apk" else "kcoach-watch.apk"
            require(asset.path.endsWith("/$expectedAsset")) { "기기에 맞지 않는 업데이트 주소예요" }
            val (installed, _) = coordinator.installed()
            if (installed >= target) {
                coordinator.change(id) { it.copy(phase = OtaPhase.UP_TO_DATE, targetCode = target, targetName = name, sha256 = hash, message = "최신 버전 확인") }
                return@withContext Result.success()
            }
            coordinator.change(id) { it.copy(phase = OtaPhase.DOWNLOADING, targetCode = target, targetName = name, sha256 = hash, progress = 0, message = "다운로드 중") }
            val apk = coordinator.store.apk(id); apk.parentFile!!.mkdirs()
            val part = java.io.File(apk.parentFile, "download.part")
            val connection = connect(location)
            try {
                val total = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var received = 0L; var reported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        if (coordinator.store.local()?.id != id) return@withContext Result.success()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count); received += count
                        require(received <= 250L * 1024 * 1024) { "업데이트 파일이 너무 커요" }
                        val progress = if (total > 0) ((received * 100 / total).coerceIn(0, 99)).toInt() else 0
                        if (progress / 5 != reported) {
                            reported = progress / 5
                            coordinator.change(id) { it.copy(progress = progress) }
                        }
                    }
                } }
                require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "APK 파일 검증 실패" }
                @Suppress("DEPRECATION") val archive = applicationContext.packageManager.getPackageArchiveInfo(part.path, android.content.pm.PackageManager.GET_META_DATA)
                require(archive?.packageName == applicationContext.packageName && archive.longVersionCode == target &&
                    archive.applicationInfo?.metaData?.getString("dev.kyungmin.kcoach.OTA_ROLE") == coordinator.config.role.name) { "앱 종류·버전이 맞지 않아요" }
                check(part.renameTo(apk)) { "업데이트 파일 저장 실패" }
            } finally { connection.disconnect() }
            // Write the verified file into an install session now, so that the tap only has to commit it.
            coordinator.change(id) { it.copy(progress = 100, message = "설치 준비 중") }
            coordinator.store.local()?.takeIf { it.id == id }?.let { OtaInstaller.stage(applicationContext, coordinator.store, it.copy(phase = OtaPhase.READY), alreadyVerified = true) }
            coordinator.change(id) { it.copy(phase = OtaPhase.READY, progress = 100, message = "다운로드·파일 검증 완료") }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled // WorkManager retains/reschedules the interrupted work; no fake failure/success.
        } catch (error: Exception) {
            coordinator.change(id) { it.copy(phase = OtaPhase.FAILED, message = error.message.orEmpty().take(300)) }
            Result.failure()
        }
    }
    private fun connect(location: String): HttpURLConnection {
        require(URI(location).scheme == "https")
        return (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true
            connect(); check(responseCode == 200) { "업데이트 서버 응답 $responseCode" }
        }
    }
}
