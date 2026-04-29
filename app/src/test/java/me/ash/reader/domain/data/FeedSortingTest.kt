package me.ash.reader.domain.data

import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedSortingTest {

    @Test
    fun sortFeedsAlphabetically_sortsFeeds_byNameCaseInsensitive() {
        val feeds = mutableListOf(
            testFeed(id = "1", name = "Zebra Feed"),
            testFeed(id = "2", name = "apple Feed"),
            testFeed(id = "3", name = "Banana Feed"),
        )

        val sorted = sortFeedsAlphabetically(feeds)

        assertEquals(
            listOf("apple Feed", "Banana Feed", "Zebra Feed"),
            sorted.map { it.name }
        )
    }

    @Test
    fun sortFeedsAlphabetically_preservesOriginalList() {
        val feeds = mutableListOf(
            testFeed(id = "1", name = "Zebra"),
            testFeed(id = "2", name = "Apple"),
        )
        val originalOrder = feeds.map { it.id }

        sortFeedsAlphabetically(feeds)

        assertEquals(originalOrder, feeds.map { it.id })
    }

    @Test
    fun sortFeedsBySortOrder_sortsFeeds_bySortOrderAscending() {
        val feeds = mutableListOf(
            testFeed(id = "1", name = "Feed C", sortOrder = 30),
            testFeed(id = "2", name = "Feed A", sortOrder = 10),
            testFeed(id = "3", name = "Feed B", sortOrder = 20),
        )

        val sorted = sortFeedsBySortOrder(feeds)

        assertEquals(
            listOf("Feed A", "Feed B", "Feed C"),
            sorted.map { it.name }
        )
    }

    @Test
    fun sortFeedsBySortOrder_fallsBackToAlphabetical_whenSortOrderEqual() {
        val feeds = mutableListOf(
            testFeed(id = "1", name = "Zebra", sortOrder = 0),
            testFeed(id = "2", name = "Apple", sortOrder = 0),
            testFeed(id = "3", name = "Banana", sortOrder = 0),
        )

        val sorted = sortFeedsBySortOrder(feeds)

        assertEquals(
            listOf("Apple", "Banana", "Zebra"),
            sorted.map { it.name }
        )
    }

    @Test
    fun sortFeedsBySortOrder_handlesNullSortOrder_byTreatingAsZero() {
        val feeds = mutableListOf(
            testFeed(id = "1", name = "Feed B", sortOrder = null),
            testFeed(id = "2", name = "Feed A", sortOrder = 10),
        )

        val sorted = sortFeedsBySortOrder(feeds)

        assertEquals(
            listOf("Feed B", "Feed A"),
            sorted.map { it.name }
        )
    }

    @Test
    fun sortGroupWithFeeds_sortsFeeds_alphabeticallyWhenNotFreshRSS() {
        val groupWithFeed = GroupWithFeed(
            group = testGroup(id = "g1", name = "Tech"),
            feeds = mutableListOf(
                testFeed(id = "1", name = "Zebra", sortOrder = 10),
                testFeed(id = "2", name = "Apple", sortOrder = 20),
            )
        )

        val sorted = sortGroupWithFeeds(groupWithFeed, useSortOrder = false)

        assertEquals(
            listOf("Apple", "Zebra"),
            sorted.feeds.map { it.name }
        )
    }

    @Test
    fun sortGroupWithFeeds_sortsFeeds_bySortOrderWhenFreshRSS() {
        val groupWithFeed = GroupWithFeed(
            group = testGroup(id = "g1", name = "Tech"),
            feeds = mutableListOf(
                testFeed(id = "1", name = "Zebra", sortOrder = 10),
                testFeed(id = "2", name = "Apple", sortOrder = 20),
            )
        )

        val sorted = sortGroupWithFeeds(groupWithFeed, useSortOrder = true)

        assertEquals(
            listOf("Zebra", "Apple"),
            sorted.feeds.map { it.name }
        )
    }

    @Test
    fun sortGroupWithFeedsList_sortsAllGroups() {
        val groups = listOf(
            GroupWithFeed(
                group = testGroup(id = "g1", name = "Tech"),
                feeds = mutableListOf(
                    testFeed(id = "1", name = "Zebra"),
                    testFeed(id = "2", name = "Apple"),
                )
            ),
            GroupWithFeed(
                group = testGroup(id = "g2", name = "News"),
                feeds = mutableListOf(
                    testFeed(id = "3", name = "CNN"),
                    testFeed(id = "4", name = "BBC"),
                )
            ),
        )

        val sorted = sortGroupWithFeedsList(groups, useSortOrder = false)

        assertEquals(listOf("Apple", "Zebra"), sorted[0].feeds.map { it.name })
        assertEquals(listOf("BBC", "CNN"), sorted[1].feeds.map { it.name })
    }

    private fun testFeed(
        id: String,
        name: String = "Test Feed",
        sortOrder: Long? = null,
        groupId: String = "1\$Defaults",
        accountId: Int = 1,
    ) = Feed(
        id = id,
        name = name,
        icon = null,
        url = "https://example.com/feed",
        groupId = groupId,
        accountId = accountId,
        sortOrder = sortOrder,
    )

    private fun testGroup(
        id: String,
        name: String = "Default",
        accountId: Int = 1,
    ) = Group(
        id = id,
        name = name,
        accountId = accountId,
    )
}
