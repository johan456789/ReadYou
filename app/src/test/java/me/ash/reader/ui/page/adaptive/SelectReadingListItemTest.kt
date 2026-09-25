package me.ash.reader.ui.page.adaptive

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectReadingListItemTest {

    @Test
    fun `returns the requested article by id`() {
        val items = articleItems(unreadArticle("a"), unreadArticle("b"), unreadArticle("c"))

        val result = selectReadingListItem(items = items, articleId = "b")

        assertEquals("b", result?.article?.id)
    }

    @Test
    fun `returns the requested article after the list shifted`() {
        // Regression test for the stale-index bug: the list shifted after the
        // reader captured its position (here "a" disappeared), yet lookup by
        // id must still resolve "b" instead of whatever sits at the old index.
        val shifted = articleItems(unreadArticle("b"), unreadArticle("c"))

        val result = selectReadingListItem(items = shifted, articleId = "b")

        assertEquals("b", result?.article?.id)
    }

    @Test
    fun `unknown id returns null so caller can fall back to database`() {
        val items = articleItems(unreadArticle("a"), unreadArticle("b"))

        val result = selectReadingListItem(items = items, articleId = "missing")

        assertNull(result)
    }

    private fun articleItems(vararg articles: ArticleWithFeed): List<ArticleFlowItem> =
        articles.map { ArticleFlowItem.Article(it) }

    private fun unreadArticle(id: String): ArticleWithFeed =
        ArticleWithFeed(
            article =
                Article(
                    id = id,
                    date = Date(1_780_292_811_000L),
                    title = id,
                    rawDescription = "<p>$id</p>",
                    shortDescription = id,
                    link = "https://example.com/$id",
                    feedId = "feed",
                    accountId = 1,
                    isUnread = true,
                ),
            feed = sampleFeed(),
        )

    private fun sampleFeed(): Feed =
        Feed(
            id = "feed",
            name = "Feed",
            url = "https://example.com/feed",
            groupId = "group",
            accountId = 1,
        )
}
