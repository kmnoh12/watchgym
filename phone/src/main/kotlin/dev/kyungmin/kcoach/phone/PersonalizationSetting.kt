package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.WearSync

/**
 * One switch for applying measurement calibration to rep counting. Saved profiles stay on the phone while it is off;
 * the watch only holds a profile while the switch is on, so turning it off makes every exercise use the generic profile.
 */
internal object PersonalizationSetting {
    private const val PREFS = "wear-sync"
    private const val KEY = "personalizationEnabled"
    private const val PROFILE_PREFIX = "personalProfile:"
    private const val TAG = "KCoachPersonalization"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit()

    /** Sends every saved profile when on, or removes each one from the watch when off. */
    fun pushToWatch(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = isEnabled(context)
        prefs.all.filterKeys { it.startsWith(PROFILE_PREFIX) }.forEach { (key, value) ->
            if (enabled && value is String) deliver(context, WearSync.PATH_PERSONAL_PROFILE, value)
            else deliver(context, WearSync.PATH_PERSONAL_PROFILE_DELETE, key.removePrefix(PROFILE_PREFIX))
        }
    }

    fun deliver(context: Context, path: String, payload: String) {
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context).sendMessage(node.id, path, payload.toByteArray())
                    .addOnFailureListener { Log.w(TAG, "profile message failed path=$path node=${node.id}", it) }
            }
        }.addOnFailureListener { Log.w(TAG, "profile node lookup failed path=$path", it) }
    }
}
