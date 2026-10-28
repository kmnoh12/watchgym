package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundWorkoutTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(
        sessionId = "background-session", routineId = "shoulders", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false), PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap(),
    )
    private fun deliver(listener: PhoneWearListenerService, payload: String) {
        PhoneWearListenerService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(listener, WearSync.PATH_LIVE, payload)
    }
    private fun completion() = WearSync.liveState("background-session", "external_rotation", "외회전", 0, 10, 10, 5.0, 110, "SET_COMPLETE", false)

    @Test fun watchCompletionPersistsAndStartsRestWithoutAnActivity() {
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot()))
        val listener = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        deliver(listener, completion())
        val saved = store.loadActive().getOrThrow()!!
        assertTrue("Background receiver must commit the completed set", saved.plannedSets.first().completed)
        assertEquals(10, saved.performedReps["external_rotation"])
        assertEquals(WorkoutPhase.RESTING, saved.phase)
        val command = shadowOf(app).nextStartedService
        assertNotNull("Background completion must dispatch rest to the watch", command)
        assertEquals("REST", WearSync.parse(command.getStringExtra(PhoneWorkoutService.EXTRA_PAYLOAD)!!)[1])
    }

    @Test fun repeatedCompletionDoesNotCountTwiceOrRestartRest() {
        val store = SessionStore(app); store.saveActive(snapshot())
        val listener = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        val payload = completion()
        deliver(listener, payload)
        val first = store.loadActive().getOrThrow()!!
        deliver(listener, payload)
        val repeated = store.loadActive().getOrThrow()!!
        assertTrue(repeated.plannedSets.first().completed)
        assertEquals(10, repeated.performedReps["external_rotation"])
        assertEquals(first.restEndAtMs, repeated.restEndAtMs)
    }

    private fun controller(commands: MutableList<String>, store: SessionStore = SessionStore(app)) =
        PhoneSessionController(store) { path, payload -> assertEquals(WearSync.PATH_COMMAND, path); commands += payload }

    @Test fun restExpiresIntoTheNextSetWithoutAnActivity() {
        val store = SessionStore(app)
        store.saveActive(snapshot().copy(plannedSets = snapshot().plannedSets.map { it.copy(exerciseName = "외회전", restSeconds = 45) }))
        val commands = mutableListOf<String>()
        controller(commands).receiveCompletion(completion(), 1_000L).getOrThrow()
        assertEquals("45", WearSync.parse(commands.single())[8])
        assertNull(controller(commands).advanceRest(45_999).getOrThrow())
        val next = controller(commands).advanceRest(46_000).getOrThrow()!!
        assertEquals(WorkoutPhase.WORKING, next.phase)
        assertEquals(1, next.setIndex)
        assertEquals(listOf("REST", "SET"), commands.map { WearSync.parse(it)[1] })
        assertEquals("외회전", WearSync.parse(commands.last())[4])
        assertEquals("1", WearSync.parse(commands.last())[5])
        assertNull(controller(commands).advanceRest(47_000).getOrThrow())
        assertEquals(2, commands.size)
    }

    @Test fun nextExerciseAndFinalCompletionAreSavedBeforeWatchCommands() {
        val store = SessionStore(app)
        val plan = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false, exerciseName = "외회전", restSeconds = 0),
            PlannedSetSnapshot(1, "lat_pull_down", 0, 8, 40.0, false, exerciseName = "랫풀다운", restSeconds = 0),
        )
        store.saveActive(snapshot().copy(plannedSets = plan))
        val commands = mutableListOf<String>()
        val c = controller(commands)
        c.receiveCompletion(completion(), 1_000).getOrThrow()
        assertEquals(1, c.advanceRest(1_000).getOrThrow()!!.exerciseIndex)
        assertEquals("lat_pull_down", WearSync.parse(commands.last())[3])
        val second = WearSync.parse(WearSync.liveState("background-session", "lat_pull_down", "랫풀다운", 0, 8, 8, 40.0, 100, "SET_COMPLETE", false)).toMutableList()
        second[11] = (WearSync.parse(completion())[11].toLong() + 1).toString()
        c.receiveCompletion(second.joinToString("|"), 2_000).getOrThrow()
        assertEquals(2, c.advanceRest(2_000).getOrThrow()!!.exerciseIndex)
        assertEquals("COMPLETE", WearSync.parse(commands.last())[1])
        assertTrue(store.loadActive().getOrThrow()!!.plannedSets.all { it.completed })
    }

    @Test fun staleActivityCannotOverwriteTheBackgroundCompletion() {
        val activityStore = SessionStore(app)
        activityStore.saveActive(snapshot())
        val oldScreen = activityStore.loadActive().getOrThrow()!!
        controller(mutableListOf()).receiveCompletion(completion(), 1_000).getOrThrow()
        assertFalse(activityStore.saveActive(oldScreen))
        assertTrue(activityStore.loadActive().getOrThrow()!!.plannedSets.first().completed)
        assertTrue(activityStore.saveActive(activityStore.loadActive().getOrThrow()!!))
    }

    @Test fun pausedAndOtherSessionEventsCannotAdvanceTheWorkout() {
        val store = SessionStore(app); store.saveActive(snapshot())
        val commands = mutableListOf<String>(); val c = controller(commands)
        val wrong = completion().replace("background-session", "other-session")
        assertTrue(c.receiveCompletion(wrong, 1_000).getOrThrow() is SetCompletionResult.Rejected)
        assertTrue(commands.isEmpty())
        c.receiveCompletion(completion(), 1_000).getOrThrow()
        val resting = store.loadActive().getOrThrow()!!
        store.saveActive(resting.copy(paused = true, phase = WorkoutPhase.PAUSED))
        assertNull(c.advanceRest(1_000_000).getOrThrow())
        assertEquals(1, commands.size)
    }

    @Test fun restoredActivitySeesTheCompletedSetAndNextIndex() {
        val store = SessionStore(app); store.saveActive(snapshot())
        controller(mutableListOf()).receiveCompletion(completion(), 1_000).getOrThrow()
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, SessionStore(app))
        val active = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)
        val routine = active.javaClass.getDeclaredField("routine").apply { isAccessible = true }.get(active) as WorkoutRoutine
        assertTrue(routine.exercises.first().sets.first().completed)
        assertEquals(1, active.javaClass.getDeclaredField("setIndex").apply { isAccessible = true }.get(active))
    }

    @Test fun stoppedActivityRefreshesBackgroundCompletionOnStart() {
        val store = SessionStore(app); store.saveActive(snapshot())
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("sessionStore").set(activity, store)
        val active = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)
        field("activeWorkout").set(activity, active)
        // The stats tab avoids rendering here; this test exercises the real receiver lifecycle.
        val tab = field("currentTab")
        tab.set(activity, tab.type.enumConstants!!.first { (it as Enum<*>).name == "STATS" })
        MainActivity::class.java.getDeclaredMethod("onStart").apply { isAccessible = true }.invoke(activity)
        MainActivity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(activity)
        deliver(Robolectric.buildService(PhoneWearListenerService::class.java).get(), completion())
        val routine = active.javaClass.getDeclaredField("routine").apply { isAccessible = true }.get(active) as WorkoutRoutine
        assertFalse(routine.exercises.first().sets.first().completed)
        MainActivity::class.java.getDeclaredMethod("onStart").apply { isAccessible = true }.invoke(activity)
        assertTrue(routine.exercises.first().sets.first().completed)
        assertEquals(1, active.javaClass.getDeclaredField("setIndex").apply { isAccessible = true }.get(active))
        MainActivity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(activity)
    }

    @Test fun serviceTimerRestoresAndAdvancesPersistedRest() {
        val store = SessionStore(app); store.saveActive(snapshot())
        val commands = mutableListOf<String>()
        controller(commands).receiveCompletion(completion(), 1_000).getOrThrow()
        commands.clear()
        val service = Robolectric.buildService(PhoneWorkoutService::class.java).get()
        // Replace only the network sender; run the actual Service timer and durable store.
        PhoneWorkoutService::class.java.getDeclaredField("sessionController\$delegate").apply { isAccessible = true }
            .set(service, lazy { controller(commands) })
        PhoneWorkoutService::class.java.getDeclaredMethod("startRestTicker").apply { isAccessible = true }.invoke(service)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("SET", WearSync.parse(commands.single())[1])
        assertEquals(WorkoutPhase.WORKING, store.loadActive().getOrThrow()!!.phase)
        service.onDestroy()
    }

    @Test fun simultaneousReceiverInstancesCommitCompletionOnlyOnce() {
        val store = SessionStore(app); store.saveActive(snapshot())
        val commands = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val payload = completion()
            val tasks = List(2) {
                executor.submit<SetCompletionResult> {
                    start.await()
                    PhoneSessionController(SessionStore(app)) { _, command -> commands.add(command) }
                        .receiveCompletion(payload, 1_000).getOrThrow()!!
                }
            }
            start.countDown()
            val results = tasks.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is SetCompletionResult.Applied })
            assertEquals(1, commands.size)
            assertEquals(10, store.loadActive().getOrThrow()!!.performedReps["external_rotation"])
        } finally { executor.shutdownNow() }
    }

    @Test fun corruptedPreviousSessionDoesNotBlockStartingANewSession() {
        app.getSharedPreferences("workout-session", Context.MODE_PRIVATE).edit().putString("activeSnapshot", "invalid").commit()
        val store = SessionStore(app)
        assertTrue(store.loadActive().isFailure)
        assertTrue(store.saveActive(snapshot()))
        assertEquals("background-session", store.loadActive().getOrThrow()!!.sessionId)
    }

    @Test fun recoveryKeepsDefaultAndPerSetRestSeparate() {
        val store = SessionStore(app)
        store.saveActive(snapshot().copy(plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, true, exerciseName = "외회전", restSeconds = 30, exerciseRestSeconds = 75, restOverrideSeconds = 30),
            PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false, exerciseName = "외회전", restSeconds = 75, exerciseRestSeconds = 75),
        )))
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, store)
        val active = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)
        val routine = active.javaClass.getDeclaredField("routine").apply { isAccessible = true }.get(active) as WorkoutRoutine
        val exercise = routine.exercises.first()
        assertEquals(75, exercise.restSeconds)
        assertEquals(30, exercise.sets.first().restSeconds)
        assertNull(exercise.sets[1].restSeconds)
    }

    @Test fun alreadyCompletedExercisesAreSkippedInBothStateAndWatchCommand() {
        val store = SessionStore(app)
        store.saveActive(snapshot().copy(plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false, restSeconds = 0),
            PlannedSetSnapshot(1, "flat_bench", 0, 10, 40.0, true),
            PlannedSetSnapshot(2, "lat_pull_down", 0, 10, 40.0, false),
        )))
        val commands = mutableListOf<String>(); val c = controller(commands)
        c.receiveCompletion(completion(), 1_000).getOrThrow()
        val next = c.advanceRest(1_000).getOrThrow()!!
        assertEquals(2, next.exerciseIndex)
        assertEquals("lat_pull_down", WearSync.parse(commands.last())[3])
    }
}
