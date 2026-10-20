package dev.kyungmin.kcoach.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageCorruptionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun corruptActive() {
        app.getSharedPreferences("workout-session", android.content.Context.MODE_PRIVATE).edit()
            .putString("activeSnapshot", "{not-json").putLong("revision", 1L).commit()
    }

    @Test fun aCorruptActiveSnapshotDoesNotThrowFromClearActive() {
        corruptActive()
        val store = SessionStore(app)
        assertNull(store.loadActive().getOrNull())
        assertFalse(store.clearActive("session"))
        assertTrue(store.clearActive())
        assertNull(store.loadActive().getOrNull())
    }

    @Test fun aCorruptFinishedRecordIsSkippedInsteadOfCrashingTheResultScreen() {
        app.getSharedPreferences("finished-workouts", android.content.Context.MODE_PRIVATE).edit()
            .putString("session:bad", "{not-json").commit()
        val store = FinishedWorkoutStore(app)
        assertTrue(store.recordHits("bad").isEmpty())
        assertFalse(store.usePounds("bad"))
    }

    @Test fun aCorruptActiveSnapshotMakesStopFailInsteadOfCrashingTheCaller() {
        corruptActive()
        assertTrue(WorkoutTermination(app).stop(saveCompleted = false).isFailure)
    }
}
