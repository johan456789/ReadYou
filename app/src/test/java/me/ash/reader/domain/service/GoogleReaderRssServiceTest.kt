package me.ash.reader.domain.service

import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun selectNewFeedsMissingIcons_returns_only_new_feeds_without_icons() {
        val selected =
            selectNewFeedsMissingIcons(
                remoteFeeds =
                    listOf(
                        testFeed(id = "1", icon = null),
                        testFeed(id = "2", icon = "https://example.com/icon.png"),
                        testFeed(id = "3", icon = ""),
                    ),
                existingFeedIds = setOf("3"),
            )

        assertEquals(listOf(testFeed(id = "1", icon = null)), selected)
    }

    @Test
    fun backfillIconsForFeeds_returns_only_feeds_with_successful_icon_lookups() = runBlocking {
        val result =
            backfillIconsForFeeds(
                feeds =
                    listOf(
                        testFeed(id = "1", url = "https://one.example/feed"),
                        testFeed(id = "2", url = "https://two.example/feed"),
                    ),
                queryIcon = { url ->
                    if (url == "https://one.example/feed") "https://one.example/favicon.ico" else null
                },
            )

        assertEquals(
            listOf(testFeed(id = "1", url = "https://one.example/feed", icon = "https://one.example/favicon.ico")),
            result,
        )
    }

    @Test
    fun persistRemoteSubscriptions_persists_backfilled_icons_after_insert() = runBlocking {
        val group = Group(id = "1\$Defaults", name = "Defaults", accountId = 1)
        val feed = testFeed(id = "1\$e2e-feed", groupId = group.id)
        val subscriptionStore = RecordingSubscriptionStore()

        persistRemoteSubscriptions(
            remoteGroups = listOf(group),
            remoteFeeds = listOf(feed),
            subscriptionStore = subscriptionStore,
            queryIcon = { "https://example.com/favicon.ico" },
        )

        val storedFeed = subscriptionStore.savedFeedsById[feed.id]
        assertNotNull("Expected persisted remote feed", storedFeed)
        assertFalse(
            "Expected favicon update to happen after feed insertion, but update ran before insert",
            subscriptionStore.updatedBeforeInsert,
        )
        assertEquals("https://example.com/favicon.ico", storedFeed?.icon)
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

    private fun testFeed(
        id: String,
        url: String = "https://example.com/feed",
        icon: String? = null,
        groupId: String = "1\$Defaults",
        accountId: Int = 1,
    ) = Feed(
        id = id,
        name = "E2E Feed",
        icon = icon,
        url = url,
        groupId = groupId,
        accountId = accountId,
    )

    private class RecordingSubscriptionStore(
        private val existingIds: Set<String> = emptySet(),
    ) : SubscriptionStore {
        val savedGroups = mutableListOf<Group>()
        val savedFeedsById = linkedMapOf<String, Feed>()
        var updatedBeforeInsert = false
            private set

        override suspend fun existingFeedIds(): Set<String> = existingIds

        override suspend fun insertOrUpdate(groups: List<Group>, feeds: List<Feed>) {
            savedGroups += groups
            feeds.forEach { savedFeedsById[it.id] = it }
        }

        override suspend fun updateFeeds(feeds: List<Feed>) {
            feeds.forEach {
                if (savedFeedsById.containsKey(it.id)) {
                    savedFeedsById[it.id] = it
                } else {
                    updatedBeforeInsert = true
                }
            }
        }
    }
}
