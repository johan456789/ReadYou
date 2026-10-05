package me.ash.reader.animation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidence for the `IndexOutOfBoundsException: index: 0, size: 0` crash in
 * `Transition.seekAnimations` (animation-core 1.11.0-alpha01).
 *
 * Two claims, one test each:
 *
 * 1. `seekAnimations` reads a `SnapshotStateList` as `size()` then `get(i)`.
 *    Those two reads can observe two different versions of the same list when
 *    another thread mutates it in between.
 * 2. Taking a nested snapshot on a background thread advances the global
 *    snapshot on that thread, so apply observers (such as
 *    `SeekableStateObserver`, which calls `seekToFraction`/`seekAnimations`)
 *    run on the background thread.
 *
 * Together: the animation observer runs on the text-prefetch thread while the
 * UI thread mutates the animation list.
 */
class SeekTransitionRaceTest {

    /**
     * The exact read pattern of `Transition.seekAnimations`:
     *
     * ```
     * for (i in 0 until list.size) list[i]
     * ```
     *
     * against a writer that empties the list.
     *
     * Left as a pure race this reproduces about one run in two on this
     * emulator: it hit in 10ms and 38ms on two runs and never hit in 20s on
     * two others. The reader therefore parks between the two reads. The park
     * stands in for the writer's core running while `seekAnimations` moves
     * from `size()` to `get(i)`; on a real device the threads run on separate
     * cores, here the gap makes that window explicit instead of betting on
     * the scheduler.
     *
     * Dropping the reader's `catch` makes the exception escape the thread; the
     * emulator then dies with `FATAL EXCEPTION: RaceReader` and the same
     * `IndexOutOfBoundsException: index: 0, size: 0` over
     * `SmallPersistentVector.get` -> `SnapshotStateList.get` as production.
     */
    @Test
    fun sizeThenGet_canSeeTwoDifferentVersionsOfTheSameList() {
        val list = SnapshotStateList<Int>()
        list.add(1)

        val stop = AtomicBoolean(false)
        val attempts = AtomicLong(0)
        val writerError = AtomicReference<Throwable?>(null)
        val readerError = AtomicReference<Throwable?>(null)

        val writer =
            Thread(
                {
                    try {
                        while (!stop.get()) {
                            list.clear()
                            list.add(1)
                        }
                    } catch (t: Throwable) {
                        writerError.set(t)
                    }
                },
                "RaceWriter",
            )

        val reader =
            Thread(
                {
                    try {
                        while (!stop.get() && readerError.get() == null) {
                            val size = list.size
                            if (size > 0) {
                                attempts.incrementAndGet()
                                LockSupport.parkNanos(1_000_000L)
                                @Suppress("UNUSED_VARIABLE")
                                val first = list[0]
                            }
                        }
                    } catch (t: Throwable) {
                        readerError.set(t)
                    }
                },
                "RaceReader",
            )

        writer.start()
        reader.start()

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && readerError.get() == null) {
            Thread.sleep(25)
        }
        stop.set(true)
        writer.join(TimeUnit.SECONDS.toMillis(2))
        reader.join(TimeUnit.SECONDS.toMillis(2))

        val error = readerError.get()
        if (error == null) {
            assertTrue(
                "no exception in 10s: attempts=${attempts.get()}, " +
                    "writerError=${writerError.get()}, writerAlive=${writer.isAlive}",
                false,
            )
        }
        assertTrue(
            "unexpected: $error",
            error is IndexOutOfBoundsException && (error.message ?: "").contains("index: 0"),
        )
    }

    /**
     * The frame sequence from the crash trace:
     *
     * ```
     * BasicText_androidKt$$ExternalSyntheticLambda1.run   (text prefetch thread)
     *   GlobalSnapshot.takeNestedMutableSnapshot
     *     SnapshotKt.advanceGlobalSnapshot
     *       SnapshotStateObserver -> seekToFraction -> seekAnimations
     * ```
     *
     * Here: a pending state change on one thread, then a nested snapshot taken
     * on a named background thread. The apply observer must fire on that
     * background thread.
     */
    @Test
    fun nestedSnapshotOnBackgroundThread_notifiesApplyObserversOnThatThread() {
        var observedState by mutableStateOf(0)
        val observerThread = AtomicReference<String?>(null)

        val handle =
            Snapshot.registerApplyObserver { _, _ ->
                observerThread.compareAndSet(null, Thread.currentThread().name)
            }

        try {
            // Pending change on this thread. Notifications are flushed later,
            // by whichever thread advances the global snapshot.
            observedState = 1

            val started = CountDownLatch(1)
            val executor =
                Executors.newSingleThreadExecutor { r -> Thread(r, "RyTextPrefetch") }
            try {
                executor.execute {
                    started.countDown()
                    // Same call BasicText prefetch makes (BasicText.android.kt).
                    Snapshot.takeMutableSnapshot().dispose()
                }
                assertTrue(
                    "background task did not run",
                    started.await(5, TimeUnit.SECONDS),
                )
            } finally {
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        } finally {
            handle.dispose()
        }

        assertTrue(
            "apply observers ran on ${observerThread.get()}, not on the text-prefetch " +
                "thread; the notification was flushed elsewhere first",
            observerThread.get()?.contains("RyTextPrefetch") == true,
        )
    }
}
