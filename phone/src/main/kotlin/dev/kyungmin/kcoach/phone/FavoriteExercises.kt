package dev.kyungmin.kcoach.phone

import android.content.Context

/**
 * The user's favourite exercises. The first read seeds the set from the catalog's own favourites;
 * from then on only the user's taps change it.
 */
internal class FavoriteExercises(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): Set<String> {
        if (!prefs.getBoolean(SEEDED, false)) {
            val seed = WorkoutData.catalog.filter { it.favorite }.map { it.id }.toSet()
            prefs.edit().putStringSet(IDS, seed).putBoolean(SEEDED, true).apply()
            return seed
        }
        return prefs.getStringSet(IDS, emptySet()).orEmpty().toSet()
    }

    /** Flips one exercise and returns the new set. */
    fun toggle(id: String): Set<String> {
        val next = all().let { if (id in it) it - id else it + id }
        prefs.edit().putStringSet(IDS, next).apply()
        return next
    }

    private companion object {
        const val PREFS = "exercise-favorites"
        const val IDS = "ids"
        const val SEEDED = "seeded"
    }
}
