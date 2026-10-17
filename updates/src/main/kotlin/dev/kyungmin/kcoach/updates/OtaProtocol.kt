package dev.kyungmin.kcoach.updates

import org.json.JSONObject

/** The two answers of "install" that mean nothing was started. */
object OtaMessages {
    const val CHECK_FIRST = "업데이트를 먼저 확인해 주세요"
    const val NOTHING_TO_INSTALL = "설치할 새 버전이 없어요"
}

enum class OtaRole { PHONE, WATCH }
enum class OtaPhase { QUEUED, CHECKING, DOWNLOADING, READY, UP_TO_DATE, USER_ACTION_REQUIRED, INSTALLING, INSTALLED, FAILED }
enum class OtaOperation { CHECK, INSTALL, STATUS }

data class OtaCommand(val id: String, val senderInstance: String, val sequence: Long,
    val operation: OtaOperation, val targetInstance: String = "", val versionCode: Long = 0L, val sha256: String = "") {
    init {
        require(id.matches(OTA_ID) && senderInstance.matches(OTA_ID) && sequence > 0)
        require(targetInstance.isEmpty() || targetInstance.matches(OTA_ID))
        if (operation == OtaOperation.INSTALL) require(targetInstance.isNotEmpty() && versionCode > 0 && sha256.matches(OTA_HASH))
    }
    fun encode() = JSONObject().put("format", 1).put("id", id).put("sender", senderInstance).put("sequence", sequence)
        .put("operation", operation.name).put("target", targetInstance).put("versionCode", versionCode).put("sha256", sha256).toString()
    companion object {
        fun decode(text: String): OtaCommand {
            require(text.length <= 4096)
            val j = JSONObject(text); require(j.getInt("format") == 1)
            return OtaCommand(j.getString("id"), j.getString("sender"), j.getLong("sequence"),
                OtaOperation.valueOf(j.getString("operation")), j.optString("target"), j.optLong("versionCode"), j.optString("sha256"))
        }
    }
}

data class OtaDeviceState(val id: String, val instance: String, val revision: Long, val commandSequence: Long,
    val phase: OtaPhase, val installedCode: Long, val installedName: String, val targetCode: Long = 0,
    val targetName: String = "", val sha256: String = "", val progress: Int = 0, val message: String = "") {
    init {
        require(id.matches(OTA_ID) && instance.matches(OTA_ID) && revision > 0 && commandSequence > 0)
        require(installedCode >= 0 && targetCode >= 0 && progress in 0..100)
        require(installedName.length <= 100 && targetName.length <= 100 && message.length <= 500)
        require(sha256.isEmpty() || sha256.matches(OTA_HASH))
        if (phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING, OtaPhase.INSTALLED)) {
            require(targetCode > 0 && sha256.matches(OTA_HASH))
        }
        if (phase == OtaPhase.INSTALLED) require(installedCode >= targetCode)
        if (phase == OtaPhase.UP_TO_DATE) require(targetCode > 0 && installedCode >= targetCode)
    }
    fun encode() = JSONObject().put("format", 1).put("id", id).put("instance", instance).put("revision", revision)
        .put("commandSequence", commandSequence).put("phase", phase.name).put("installedCode", installedCode)
        .put("installedName", installedName).put("targetCode", targetCode).put("targetName", targetName)
        .put("sha256", sha256).put("progress", progress).put("message", message).toString()
    companion object {
        fun decode(text: String): OtaDeviceState {
            require(text.length <= 8192)
            val j = JSONObject(text); require(j.getInt("format") == 1)
            return OtaDeviceState(j.getString("id"), j.getString("instance"), j.getLong("revision"), j.getLong("commandSequence"),
                OtaPhase.valueOf(j.getString("phase")), j.getLong("installedCode"), j.getString("installedName"),
                j.optLong("targetCode"), j.optString("targetName"), j.optString("sha256"), j.optInt("progress"), j.optString("message"))
        }
    }
}

internal val OTA_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
internal val OTA_HASH = Regex("[a-f0-9]{64}")

data class OtaUi(val message: String, val busy: Boolean, val readyToInstall: Boolean, val card: OtaCard? = null)

enum class OtaRowKind { IDLE, WORKING, READY, DONE, FAIL }
/** One device's line of the update card: "폰" or "워치", its status, and a 0..1 [progress] when one is known. */
data class OtaRow(val device: String, val text: String, val kind: OtaRowKind, val progress: Float? = null)
enum class OtaAction { CHECK, INSTALL, WORKING, RETRY }
/**
 * What the menu's update card shows. The card always has the same two rows and one button, so nothing moves while the
 * state changes; [watch] is null only when no watch is expected.
 */
data class OtaCard(val headline: String, val phone: OtaRow, val watch: OtaRow?, val action: OtaAction, val actionLabel: String)

object OtaPresentation {
    private fun phaseRow(device: String, s: OtaDeviceState?): OtaRow = when (s?.phase) {
        null -> OtaRow(device, "응답 대기", OtaRowKind.IDLE)
        OtaPhase.QUEUED -> OtaRow(device, "대기 중", OtaRowKind.WORKING)
        OtaPhase.CHECKING -> OtaRow(device, "확인 중", OtaRowKind.WORKING)
        OtaPhase.DOWNLOADING -> OtaRow(device, "다운로드 ${s.progress}%", OtaRowKind.WORKING, s.progress / 100f)
        OtaPhase.READY -> OtaRow(device, "${s.targetName} 설치 준비됨", OtaRowKind.READY)
        OtaPhase.UP_TO_DATE -> OtaRow(device, "최신 · ${s.installedName}", OtaRowKind.DONE)
        OtaPhase.USER_ACTION_REQUIRED -> OtaRow(device, "${s.targetName} 설치 준비됨", OtaRowKind.READY)
        OtaPhase.INSTALLING -> OtaRow(device, "설치 중", OtaRowKind.WORKING)
        OtaPhase.INSTALLED -> OtaRow(device, "설치됨 · ${s.installedName}", OtaRowKind.DONE)
        OtaPhase.FAILED -> OtaRow(device, "실패 · ${s.message.take(40)}", OtaRowKind.FAIL)
    }

    /** The card for the menu. [stage] and [installProgress] are this device's own, set by the tap ([OtaStage]). */
    fun card(local: OtaDeviceState?, peer: OtaDeviceState?, peerExpected: Boolean, waitingForPeer: Boolean,
        stage: String, installProgress: Int): OtaCard {
        val phone = when (stage) {
            OtaStage.PEER -> OtaRow("폰", "워치 요청 뒤 설치", OtaRowKind.WORKING)
            OtaStage.COMMIT -> OtaRow("폰", "설치 시작", OtaRowKind.WORKING)
            OtaStage.OS -> OtaRow("폰", "설치 확인", OtaRowKind.WORKING)
            OtaStage.RUN -> OtaRow("폰", "설치 중", OtaRowKind.WORKING, installProgress.takeIf { it > 0 }?.div(100f))
            else -> phaseRow("폰", local)
        }
        val watch = if (!peerExpected) null else when {
            waitingForPeer -> OtaRow("워치", "설치 요청 중", OtaRowKind.WORKING)
            peer?.phase == OtaPhase.USER_ACTION_REQUIRED -> OtaRow("워치", "워치에서 설치 확인", OtaRowKind.WORKING)
            else -> phaseRow("워치", peer)
        }
        val rows = listOfNotNull(phone, watch)
        val working = rows.any { it.kind == OtaRowKind.WORKING }
        val installing = waitingForPeer || stage != OtaStage.NONE || rows.any { it.kind == OtaRowKind.WORKING && it.text.startsWith("설치") || it.text.startsWith("워치에서") }
        val ready = rows.any { it.kind == OtaRowKind.READY }
        val failed = rows.any { it.kind == OtaRowKind.FAIL }
        val target = (local?.targetName?.takeIf { it.isNotBlank() } ?: peer?.targetName).orEmpty()
        val (action, label) = when {
            working && installing -> OtaAction.WORKING to "설치 중"
            ready -> OtaAction.INSTALL to "설치"
            working -> OtaAction.WORKING to "확인 중"
            failed -> OtaAction.RETRY to "다시 시도"
            else -> OtaAction.CHECK to "업데이트 확인"
        }
        val headline = when {
            working && installing -> "업데이트하는 중"
            ready -> if (target.isNotEmpty()) "$target 업데이트가 준비됐어요" else "업데이트가 준비됐어요"
            working -> "업데이트 확인 중"
            failed -> "업데이트하지 못했어요"
            rows.all { it.kind == OtaRowKind.DONE } && rows.any { it.text.startsWith("설치됨") } -> "업데이트를 마쳤어요"
            rows.all { it.kind == OtaRowKind.DONE } -> "최신 버전이에요"
            else -> "업데이트를 확인해 보세요"
        }
        return OtaCard(headline, phone, watch, action, label)
    }

    fun summarize(local: OtaDeviceState?, peer: OtaDeviceState?, peerExpected: Boolean, awaitingAcceptance: Boolean): OtaUi {
        fun label(s: OtaDeviceState?) = when (s?.phase) {
            null -> "응답 대기 · 요청 보관 중"
            OtaPhase.READY -> "${s.targetName} 설치 준비됨"
            OtaPhase.UP_TO_DATE -> "최신 (${s.installedName})"
            OtaPhase.INSTALLED -> "설치 확인 (${s.installedName})"
            OtaPhase.USER_ACTION_REQUIRED -> "설치 확인 필요"
            OtaPhase.INSTALLING -> "설치 진행 · 버전 확인 대기"
            OtaPhase.DOWNLOADING -> "다운로드 ${s.progress}%"
            OtaPhase.QUEUED -> "네트워크·작업 시작 대기"
            OtaPhase.CHECKING -> "최신 버전 확인 중"
            OtaPhase.FAILED -> "실패 · ${s.message}"
        }
        val message = if (peerExpected) "폰: ${label(local)}\n워치: ${label(peer)}" else label(local)
        val working = local?.phase in setOf(OtaPhase.QUEUED, OtaPhase.CHECKING, OtaPhase.DOWNLOADING) ||
            peer?.phase in setOf(OtaPhase.QUEUED, OtaPhase.CHECKING, OtaPhase.DOWNLOADING)
        val ready = !awaitingAcceptance && (local?.phase == OtaPhase.READY || peer?.phase == OtaPhase.READY ||
            local?.phase == OtaPhase.USER_ACTION_REQUIRED || peer?.phase == OtaPhase.USER_ACTION_REQUIRED)
        return OtaUi(if (awaitingAcceptance) "$message\n워치 설치 요청 접수 대기" else message, awaitingAcceptance || (working && !ready), ready)
    }
}
