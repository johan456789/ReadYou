package me.ash.reader.infrastructure.android

import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Tracks whether periodic sync should be deferred while the user is actively reading.
 */
object ForegroundSyncController {
    @Volatile private var appInForeground = false
    @Volatile private var readerActive = false

    private val deferredPeriodicSyncPending = AtomicBoolean(false)

    fun updateAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        Timber.tag(TAG).d("updateAppInForeground($inForeground)")
    }

    fun updateReaderActive(active: Boolean) {
        readerActive = active
        Timber.tag(TAG).d("updateReaderActive($active)")
    }

    fun shouldDeferPeriodicSync(): Boolean = appInForeground && readerActive

    fun markDeferredPeriodicSyncPending() {
        deferredPeriodicSyncPending.set(true)
        Timber.tag(TAG).d("markDeferredPeriodicSyncPending()")
    }

    fun consumeDeferredPeriodicSyncIfReady(): Boolean =
        if (!shouldDeferPeriodicSync()) deferredPeriodicSyncPending.getAndSet(false) else false

    internal fun resetForTest() {
        appInForeground = false
        readerActive = false
        deferredPeriodicSyncPending.set(false)
    }

    private const val TAG = "ForegroundSync"
}
