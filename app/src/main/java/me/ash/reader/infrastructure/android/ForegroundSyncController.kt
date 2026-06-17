package me.ash.reader.infrastructure.android

import timber.log.Timber

/**
 * Tracks whether a periodic sync tick was missed while the user was actively reading.
 */
object ForegroundSyncController {
    private var appInForeground = false
    private var readerActive = false

    private var missedPeriodicSyncPending = false

    @Synchronized
    fun updateAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        Timber.tag(TAG).d("updateAppInForeground($inForeground)")
    }

    @Synchronized
    fun updateReaderActive(active: Boolean) {
        readerActive = active
        Timber.tag(TAG).d("updateReaderActive($active)")
    }

    @Synchronized fun shouldDeferPeriodicSync(): Boolean = appInForeground && readerActive

    @Synchronized
    fun tryRecordMissedPeriodicSync(): Boolean {
        if (!shouldDeferPeriodicSync()) return false

        missedPeriodicSyncPending = true
        Timber.tag(TAG).d("tryRecordMissedPeriodicSync(): missed tick recorded")
        return true
    }

    @Synchronized
    fun consumeDeferredPeriodicSyncIfReady(): Boolean =
        if (!shouldDeferPeriodicSync() && missedPeriodicSyncPending) {
            missedPeriodicSyncPending = false
            true
        } else {
            false
        }

    @Synchronized
    internal fun resetForTest() {
        appInForeground = false
        readerActive = false
        missedPeriodicSyncPending = false
    }

    private const val TAG = "ForegroundSync"
}
