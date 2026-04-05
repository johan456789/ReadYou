package me.ash.reader.ui.page.adaptive

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingUiStateTest {
    @Test
    fun withUnreadState_marksTheStoredArticleSnapshotAsRead() {
        val state = ReadingUiState(articleWithFeed = unreadArticle(), isUnread = true)

        val updated = state.withUnreadState(isUnread = false)

        assertFalse(updated.articleWithFeed!!.article.isUnread)
        assertFalse(updated.isUnread)
    }

    @Test
    fun withUnreadState_marksTheStoredArticleSnapshotAsUnread() {
        val state = ReadingUiState(articleWithFeed = readArticle(), isUnread = false)

        val updated = state.withUnreadState(isUnread = true)

        assertTrue(updated.articleWithFeed!!.article.isUnread)
        assertTrue(updated.isUnread)
    }

    private fun unreadArticle(): ArticleWithFeed =
        ArticleWithFeed(
            article =
                Article(
                    id = "article",
                    date = Date(0L),
                    title = "Article",
                    rawDescription = "<p>Article</p>",
                    shortDescription = "Article",
                    link = "https://example.com/article",
                    feedId = "feed",
                    accountId = 1,
                    isUnread = true,
                ),
            feed = sampleFeed(),
        )

    private fun readArticle(): ArticleWithFeed =
        unreadArticle().run { copy(article = article.copy(isUnread = false)) }

    private fun sampleFeed(): Feed =
        Feed(
            id = "feed",
            name = "Feed",
            url = "https://example.com/feed",
            groupId = "group",
            accountId = 1,
        )
}
