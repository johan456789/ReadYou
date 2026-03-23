package me.ash.reader.domain.service

import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.objenesis.ObjenesisStd
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

class GoogleReaderRssServiceTest {
    @Test
    fun fetchItemIdsAndContinueThrowsWhenInitialPageIsUnavailable() = runBlocking {
        var threw = false
        try {
            newUninitializedService().fetchItemIdsAndContinueForTest { null }
        } catch (_: Exception) {
            threw = true
        }
        if (!threw) fail("Expected initial snapshot fetch failure to throw")
    }

    @Test
    fun fetchItemIdsAndContinueThrowsWhenContinuationPageIsUnavailable() = runBlocking {
        var threw = false
        try {
            newUninitializedService().fetchItemIdsAndContinueForTest { continuationId ->
                when (continuationId) {
                    null ->
                        GoogleReaderDTO.ItemIds(
                            itemRefs = listOf(GoogleReaderDTO.Item(id = "first-page")),
                            continuation = "page-2",
                        )

                    "page-2" -> null
                    else -> error("Unexpected continuation: $continuationId")
                }
            }
        } catch (_: Exception) {
            threw = true
        }
        if (!threw) fail("Expected continuation fetch failure to throw")
    }

    @Test
    fun fetchItemIdsAndContinueReturnsAllIdsAcrossPages() = runBlocking {
        val ids =
            newUninitializedService().fetchItemIdsAndContinueForTest { continuationId ->
                when (continuationId) {
                    null ->
                        GoogleReaderDTO.ItemIds(
                            itemRefs = listOf(GoogleReaderDTO.Item(id = "first-page")),
                            continuation = "page-2",
                        )

                    "page-2" ->
                        GoogleReaderDTO.ItemIds(
                            itemRefs = listOf(GoogleReaderDTO.Item(id = "second-page")),
                            continuation = null,
                        )

                    else -> error("Unexpected continuation: $continuationId")
                }
            }

        assertEquals(listOf("first-page", "second-page"), ids)
    }

    private suspend fun GoogleReaderRssService.fetchItemIdsAndContinueForTest(
        getItemIdsFunc: suspend (String?) -> GoogleReaderDTO.ItemIds?,
    ): MutableList<String> {
        val method =
            GoogleReaderRssService::class.declaredFunctions.single {
                it.name == "fetchItemIdsAndContinue"
            }
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.callSuspend(this, getItemIdsFunc) as MutableList<String>
    }

    private fun newUninitializedService(): GoogleReaderRssService {
        return ObjenesisStd().newInstance(GoogleReaderRssService::class.java)
    }
}
