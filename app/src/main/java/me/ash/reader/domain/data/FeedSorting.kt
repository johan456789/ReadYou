package me.ash.reader.domain.data

import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.GroupWithFeed

fun sortFeedsAlphabetically(feeds: List<Feed>): List<Feed> {
    return feeds.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

fun sortFeedsBySortOrder(feeds: List<Feed>): List<Feed> {
    return feeds.sortedWith(
        compareBy<Feed> { it.sortOrder ?: 0 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
}

fun sortGroupWithFeeds(groupWithFeed: GroupWithFeed, useSortOrder: Boolean): GroupWithFeed {
    val sortedFeeds = if (useSortOrder) {
        sortFeedsBySortOrder(groupWithFeed.feeds)
    } else {
        sortFeedsAlphabetically(groupWithFeed.feeds)
    }
    return groupWithFeed.copy(feeds = sortedFeeds.toMutableList())
}

fun sortGroupWithFeedsList(
    groups: List<GroupWithFeed>,
    useSortOrder: Boolean
): List<GroupWithFeed> {
    return groups.map { sortGroupWithFeeds(it, useSortOrder) }
}
