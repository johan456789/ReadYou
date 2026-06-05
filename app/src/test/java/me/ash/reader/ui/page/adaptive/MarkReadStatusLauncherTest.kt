package me.ash.reader.ui.page.adaptive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkReadStatusLauncherTest {

    @Test
    fun `mark-read work survives caller scope cancellation`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        val latch = CountDownLatch(1)
        var markedIds: Set<String> = emptySet()

        callerScope.launch {
            launchMarkReadStatus(
                applicationScope = applicationScope,
                ioDispatcher = Dispatchers.Default,
                markReadStatus = {
                    delay(100)
                    setOf("article")
                },
                onMarked = {
                    markedIds = it
                    latch.countDown()
                },
            )
            started.countDown()
            awaitCancellation()
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        callerScope.cancel()

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(setOf("article"), markedIds)

        applicationScope.cancel()
    }
}
