package dev.kyungmin.kcoach.wear

import android.content.Context

/** Last phone-side "actively looking at the phone" report; stale reports are ignored. */
class PhoneViewingStore(context: Context) {
    private val prefs = context.getSharedPreferences("phone-viewing", Context.MODE_PRIVATE)

    fun update(viewing: Boolean, receiveElapsedNs: Long) {
        prefs.edit().putBoolean("viewing", viewing).putLong("atElapsedNs", receiveElapsedNs).apply()
    }

    fun isViewing(nowElapsedNs: Long, maxAgeNs: Long = 5_000_000_000L): Boolean {
        val at = prefs.getLong("atElapsedNs", 0L)
        return prefs.getBoolean("viewing", false) && at > 0L && nowElapsedNs - at in 0..maxAgeNs
    }

    fun clear() = prefs.edit().remove("viewing").remove("atElapsedNs").apply()
}
