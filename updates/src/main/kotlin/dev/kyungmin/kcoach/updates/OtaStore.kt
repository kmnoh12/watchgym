package dev.kyungmin.kcoach.updates

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The install stages after a tap, local to one device. */
object OtaStage {
    const val NONE = ""
    /** Waiting for the watch to take the install request before this phone is replaced. */
    const val PEER = "PEER"
    /** The staged session is being committed. */
    const val COMMIT = "COMMIT"
    /** The operating system is asking the user to confirm. */
    const val OS = "OS"
    /** Committed; the system is verifying and replacing the app. */
    const val RUN = "RUN"
}

/** "This app was replaced by [to]" for the first screen after an update. */
data class OtaNotice(val from: String, val to: String, val atMs: Long)

/** One OTA transaction per app; never shares or replaces the workout command outbox. */
class OtaStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("companion-ota", Context.MODE_PRIVATE)
    fun instance(): String = synchronized(lock) {
        prefs.getString("instance", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("instance", it).commit())
        }
    }
    fun local(): OtaDeviceState? = synchronized(lock) { prefs.getString("local", null)?.let(OtaDeviceState::decode) }
    fun peer(): OtaDeviceState? = synchronized(lock) { prefs.getString("peer", null)?.let(OtaDeviceState::decode) }
    fun command(): OtaCommand? = synchronized(lock) { prefs.getString("command", null)?.let(OtaCommand::decode) }
    fun nextSequence(): Long = synchronized(lock) {
        val value = Math.addExact(prefs.getLong("sequence", 0), 1)
        check(prefs.edit().putLong("sequence", value).commit()); value
    }
    fun begin(command: OtaCommand, state: OtaDeviceState) = synchronized(lock) {
        check(prefs.edit().putString("command", command.encode()).putString("local", state.encode())
            .remove("peer").remove("peerNode").remove("sessionId").remove("stage").remove("stagedFor").remove("stagedSession")
            .remove("installProgress").putBoolean("selfAfterPeer", false).commit())
    }
    fun saveCommand(command: OtaCommand) = synchronized(lock) {
        check(prefs.edit().putString("command", command.encode()).commit())
    }
    fun saveLocal(state: OtaDeviceState): Boolean = synchronized(lock) {
        if (local()?.id != state.id) return false
        check(prefs.edit().putString("local", state.encode()).commit()); true
    }
    fun acceptPeer(state: OtaDeviceState, node: String): Boolean = synchronized(lock) {
        val command = command() ?: return false
        if (state.id != command.id || state.commandSequence > command.sequence) return false
        val previous = peer()
        if (previous != null && (previous.instance != state.instance || state.revision <= previous.revision)) return false
        val pinnedNode = prefs.getString("peerNode", null)
        if (pinnedNode != null && pinnedNode != node) return false
        check(prefs.edit().putString("peer", state.encode()).putString("peerNode", node).commit()); true
    }
    fun receiveCommand(command: OtaCommand): Boolean = synchronized(lock) {
        val seen = JSONObject(prefs.getString("received", "{}")!!)
        val previous = seen.optJSONObject(command.senderInstance)
        if (previous != null) {
            if (command.sequence < previous.getLong("sequence")) return false
            if (command.sequence == previous.getLong("sequence")) {
                require(previous.getString("payload") == command.encode()) { "OTA sequence reused with different command" }
                return false
            }
        }
        require(seen.length() < 32 || seen.has(command.senderInstance))
        seen.put(command.senderInstance, JSONObject().put("sequence", command.sequence).put("payload", command.encode()))
        check(prefs.edit().putString("received", seen.toString()).commit()); true
    }
    /** Where this device is in the install after the tap (see [OtaStage]); never sent to the other device. */
    fun stage(): String = prefs.getString("stage", OtaStage.NONE)!!
    fun setStage(value: String) { check(prefs.edit().putString("stage", value).commit()) }
    /** The install session that already holds the verified file but has not been committed yet, with the id it belongs to. */
    fun staged(): Pair<String, Int>? = synchronized(lock) {
        val id = prefs.getString("stagedFor", null) ?: return null
        val session = prefs.getInt("stagedSession", -1)
        if (session < 0) null else id to session
    }
    fun setStaged(id: String, session: Int) { check(prefs.edit().putString("stagedFor", id).putInt("stagedSession", session).commit()) }
    fun clearStaged() { check(prefs.edit().remove("stagedFor").remove("stagedSession").commit()) }
    /** The platform's own progress for the committed session, 0..100. */
    fun installProgress(): Int = prefs.getInt("installProgress", 0)
    fun setInstallProgress(value: Int) { prefs.edit().putInt("installProgress", value.coerceIn(0, 100)).apply() }
    /** Written when the app was just replaced by an update; read once by the first screen that opens afterwards. */
    fun notice(): OtaNotice? = synchronized(lock) {
        val text = prefs.getString("notice", null) ?: return null
        runCatching { JSONObject(text).let { OtaNotice(it.getString("from"), it.getString("to"), it.getLong("atMs")) } }.getOrNull()
    }
    fun setNotice(notice: OtaNotice) {
        check(prefs.edit().putString("notice", JSONObject().put("from", notice.from).put("to", notice.to).put("atMs", notice.atMs).toString()).commit())
    }
    fun clearNotice() { prefs.edit().remove("notice").apply() }
    fun waitingForPeer(): Boolean = prefs.getBoolean("selfAfterPeer", false)
    fun waitForPeer(value: Boolean) { check(prefs.edit().putBoolean("selfAfterPeer", value).commit()) }
    fun sessionId(): Int = prefs.getInt("sessionId", -1)
    fun setSessionId(value: Int) { check(prefs.edit().putInt("sessionId", value).commit()) }
    fun apk(id: String): File {
        require(id.matches(OTA_ID))
        return File(context.cacheDir, "updates/$id/kcoach-update.apk")
    }
    companion object { private val lock = Any() }
}
