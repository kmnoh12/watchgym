package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.WearSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneViewingPolicyTest {
    private fun signals(
        screenOn: Boolean = true,
        unlocked: Boolean = true,
        charging: Boolean = false,
        inHand: Boolean = true,
        tilt: Double = 45.0,
        stillMs: Long = 1_200L,
    ) = PhoneViewingSignals(screenOn, unlocked, charging, inHand, tilt, stillMs)

    @Test fun theHeartbeatCarriesThePhoneViewingFlag() {
        assertTrue(WearSync.heartbeat("phone", true).endsWith("|1"))
        assertTrue(WearSync.heartbeat("phone", false).endsWith("|0"))
    }

    @Test fun aHeldStationaryPhoneAtAViewingAngleIsSuspicious() {
        assertTrue(PhoneViewingPolicy.isViewing(signals()))
    }

    @Test fun mountedFlatVerticalChargingLockedOrMovingPhonesAreNotSuspicious() {
        assertFalse(PhoneViewingPolicy.isViewing(signals(inHand = false)))
        assertFalse(PhoneViewingPolicy.isViewing(signals(tilt = 2.0)))
        assertFalse(PhoneViewingPolicy.isViewing(signals(tilt = 88.0)))
        assertFalse(PhoneViewingPolicy.isViewing(signals(charging = true)))
        assertFalse(PhoneViewingPolicy.isViewing(signals(screenOn = false)))
        assertFalse(PhoneViewingPolicy.isViewing(signals(unlocked = false)))
        assertFalse(PhoneViewingPolicy.isViewing(signals(stillMs = 300L)))
    }
}
