package me.ash.reader.infrastructure.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundSyncControllerTest {
    @Before
    fun setUp() {
        ForegroundSyncController.resetForTest()
    }

    @Test
    fun `periodic sync is deferred only while app is foreground and reader is active`() {
        ForegroundSyncController.updateAppInForeground(true)
        assertFalse(ForegroundSyncController.shouldDeferPeriodicSync())

        ForegroundSyncController.updateReaderActive(true)
        assertTrue(ForegroundSyncController.shouldDeferPeriodicSync())

        ForegroundSyncController.updateAppInForeground(false)
        assertFalse(ForegroundSyncController.shouldDeferPeriodicSync())
    }

    @Test
    fun `missed periodic sync is recorded only while app is foreground and reader is active`() {
        assertFalse(ForegroundSyncController.tryRecordMissedPeriodicSync())

        ForegroundSyncController.updateAppInForeground(true)
        assertFalse(ForegroundSyncController.tryRecordMissedPeriodicSync())

        ForegroundSyncController.updateAppInForeground(true)
        ForegroundSyncController.updateReaderActive(true)
        assertTrue(ForegroundSyncController.tryRecordMissedPeriodicSync())
    }

    @Test
    fun `deferred periodic sync is consumed after leaving the reader`() {
        ForegroundSyncController.updateAppInForeground(true)
        ForegroundSyncController.updateReaderActive(true)
        assertTrue(ForegroundSyncController.tryRecordMissedPeriodicSync())

        assertFalse(ForegroundSyncController.consumeDeferredPeriodicSyncIfReady())

        ForegroundSyncController.updateReaderActive(false)
        assertTrue(ForegroundSyncController.consumeDeferredPeriodicSyncIfReady())
        assertFalse(ForegroundSyncController.consumeDeferredPeriodicSyncIfReady())
    }

    @Test
    fun `deferred periodic sync is consumed after app leaves foreground`() {
        ForegroundSyncController.updateAppInForeground(true)
        ForegroundSyncController.updateReaderActive(true)
        assertTrue(ForegroundSyncController.tryRecordMissedPeriodicSync())

        ForegroundSyncController.updateAppInForeground(false)

        assertTrue(ForegroundSyncController.consumeDeferredPeriodicSyncIfReady())
    }
}
