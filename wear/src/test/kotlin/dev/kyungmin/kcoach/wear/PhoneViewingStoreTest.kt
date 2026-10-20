package dev.kyungmin.kcoach.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneViewingStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun aFreshViewingReportIsUsedAndAStaleOneIsIgnored() {
        val store = PhoneViewingStore(app)
        store.update(true, 1_000_000_000L)
        assertTrue(store.isViewing(2_000_000_000L))
        assertFalse(store.isViewing(7_000_000_000L))
        store.update(false, 8_000_000_000L)
        assertFalse(store.isViewing(8_100_000_000L))
    }
}
