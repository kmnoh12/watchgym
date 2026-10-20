package dev.kyungmin.kcoach.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleServiceTest {
    @Test fun foregroundRetryPromotesAnExistingInstanceAfterIdleStopBeforeDestroy() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(shadowOf(service).isForegroundStopped)
        // Android can deliver a new foreground-start request to this instance before destruction.
        PhoneWorkoutService.send(app, dev.kyungmin.kcoach.core.WearSync.PATH_COMMAND,
            dev.kyungmin.kcoach.core.WearSync.command("STOP", "ended", "", 0, 0))
        val intent = shadowOf(app).nextStartedService
        service.onStartCommand(intent, 0, 2)
        assertFalse("Every foreground-start request must re-promote even when onCreate is not called again",
            shadowOf(service).isForegroundStopped)
        assertNotNull(PhoneCommandOutbox(app).pending())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue("The re-promoted idle retry must still stop after bounded delivery", shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    @Test fun idleStopTargetsTheNewestStartIdSoAQueuedForegroundStartIsNotDropped() {
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 3)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("Idle stop must carry the latest startId; a bare stopSelf() drops queued starts", 3, shadowOf(service).stopSelfResultId)
        controller.destroy()
    }

    @Test fun idlePhoneServiceStopsItsForegroundLoop() {
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue("No active workout must not keep a foreground service alive", shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
    @Test fun terminalCommandIsDurableBeforeTheServiceRuns() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        PhoneWorkoutService.send(app, dev.kyungmin.kcoach.core.WearSync.PATH_COMMAND,
            dev.kyungmin.kcoach.core.WearSync.command("STOP", "ended", "", 0, 0))
        assertNotNull("An interrupted or rejected service start must not lose STOP", PhoneCommandOutbox(app).pending())
    }

    @Test fun creationWithoutStartIdCannotStopAQueuedStart() {
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        controller.get().onStartCommand(null, 0, 7)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(7, shadowOf(controller.get()).stopSelfResultId)
        controller.destroy()
    }

    @Test fun staleTimeoutDoesNotDemoteANewerStart() {
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(null, 0, 2)
        service.onTimeout(1, 0)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
        service.onTimeout(2, 0)
        assertEquals(2, shadowOf(service).stopSelfResultId)
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
    }

    @Test fun taskRemovalStopsOnlyTheLastDeliveredStart() {
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.get().onStartCommand(null, 0, 9)
        controller.get().onTaskRemoved(null)
        assertEquals(9, shadowOf(controller.get()).stopSelfResultId)
        controller.destroy()
    }

    @Test fun idleOverviewDoesNotStartAForegroundService() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        WatchOverviewPublisher.publishNow(app)
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun activeSnapshot() = dev.kyungmin.kcoach.core.WorkoutSnapshot(
        sessionId = "running", routineId = "shoulders", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(dev.kyungmin.kcoach.core.PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false)),
        performedReps = emptyMap(), phase = dev.kyungmin.kcoach.core.WorkoutPhase.WORKING, restEndAtMs = 0,
        pausedRestRemainingMs = 0, paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0,
        lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
    @Test fun pausedWorkoutKeepsItsForegroundLoop() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SessionStore(app).saveActive(activeSnapshot().copy(paused = true, phase = dev.kyungmin.kcoach.core.WorkoutPhase.PAUSED))
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
    @Test fun disconnectedTerminalCommandSurvivesBoundedIdleRetryAndReconnect() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val pending = PhoneCommandOutbox(app).enqueue(dev.kyungmin.kcoach.core.WearSync.command("COMPLETE", "ended", "", 0, 0))
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(pending, PhoneCommandOutbox(app).pending())
        controller.destroy()
        PhoneWorkoutService.retryPending(app)
        assertEquals(PhoneWorkoutService.ACTION_RETRY, shadowOf(app).nextStartedService.getStringExtra(PhoneWorkoutService.EXTRA_ACTION))
    }
    @Test fun newerPendingStopIsNotOverwrittenByInitialOrRepeatedServiceSync() {
        val app = org.robolectric.RuntimeEnvironment.getApplication(); SessionStore(app).saveActive(activeSnapshot())
        PhoneWorkoutService.send(app, dev.kyungmin.kcoach.core.WearSync.PATH_COMMAND,
            dev.kyungmin.kcoach.core.WearSync.command("STOP", "running", "", 0, 0))
        val pending = PhoneCommandOutbox(app).pending()
        val intent = shadowOf(app).nextStartedService
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.get().onStartCommand(intent, 0, 1)
        assertEquals(pending, PhoneCommandOutbox(app).pending())
        controller.destroy()
    }

}
