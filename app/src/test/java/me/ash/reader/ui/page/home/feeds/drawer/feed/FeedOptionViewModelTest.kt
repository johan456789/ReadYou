package me.ash.reader.ui.page.home.feeds.drawer.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FeedOptionViewModelTest {

    @Test
    fun `delete reports failure instead of invoking success when unsubscribe throws`() = runBlocking {
        val feed = sampleFeed()
        val repository = mock<AbstractRssRepository>()
        val rssService = mock<RssService>()
        val failure = IllegalStateException("offline")

        whenever(rssService.get()).thenReturn(repository)
        whenever(rssService.flow()).thenReturn(MutableStateFlow(repository))
        whenever(repository.pullGroups()).thenReturn(flowOf(mutableListOf()))
        whenever(repository.findFeedById(feed.id)).thenReturn(feed)
        whenever(repository.deleteFeed(any(), any())).thenThrow(failure)

        val viewModel =
            FeedOptionViewModel(
                rssService = rssService,
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                rssHelper = mock<RssHelper>(),
                feedDao = mock<FeedDao>(),
            )

        viewModel.fetchFeed(feed.id)

        var successCalled = false
        var reportedFailure: Throwable? = null

        viewModel.delete(
            onSuccess = { successCalled = true },
            onFailure = { reportedFailure = it },
        )

        withTimeout(1_000) {
            while (reportedFailure == null) {
                kotlinx.coroutines.yield()
            }
        }

        assertFalse(successCalled)
        assertSame(failure, reportedFailure)
    }

    private fun sampleFeed(): Feed =
        Feed(
            id = "account\$feed",
            name = "Feed",
            url = "https://example.com/feed",
            groupId = "group",
            accountId = 1,
        )
}
