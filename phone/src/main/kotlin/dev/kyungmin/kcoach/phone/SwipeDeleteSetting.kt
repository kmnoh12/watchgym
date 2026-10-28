package dev.kyungmin.kcoach.phone

import android.content.Context

/** Whether a set row can be swiped open to delete it during a workout. On by default. */
internal object SwipeDeleteSetting {
    private const val PREFS = "ui-settings"
    private const val KEY = "swipeDeleteEnabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit()
}
