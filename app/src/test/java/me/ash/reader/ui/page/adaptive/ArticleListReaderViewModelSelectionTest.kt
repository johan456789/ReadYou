package me.ash.reader.ui.page.adaptive

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleListReaderViewModelSelectionTest {

    @Test
    fun `selectArticlesToMark marks articles above target when timestamps are tied`() {
        val tiedDate = Date(1_780_292_811_000L)
        val first = unreadArticle(id = "first", date = tiedDate)
        val second = unreadArticle(id = "second", date = tiedDate)
        val target = unreadArticle(id = "target", date = tiedDate)
        val below = unreadArticle(id = "below", date = tiedDate)

        val selected = selectArticlesToMark(
            items = articleItems(first, second, target, below),
            targetArticleId = "target",
            markAbove = true,
        )

        assertEquals(listOf("first", "second"), selected.map { it.article.id })
    }

    @Test
    fun `selectArticlesToMark marks articles below target when timestamps are tied`() {
        val tiedDate = Date(1_780_292_811_000L)
        val above = unreadArticle(id = "above", date = tiedDate)
        val target = unreadArticle(id = "target", date = tiedDate)
        val firstBelow = unreadArticle(id = "first-below", date = tiedDate)
        val secondBelow = unreadArticle(id = "second-below", date = tiedDate)

        val selected = selectArticlesToMark(
            items = articleItems(above, target, firstBelow, secondBelow),
            targetArticleId = "target",
            markAbove = false,
        )

        assertEquals(listOf("first-below", "second-below"), selected.map { it.article.id })
    }

    @Test
    fun `selectArticlesToMark excludes read items and duplicate ids`() {
        val date = Date(1_780_292_811_000L)
        val unread = unreadArticle(id = "unread", date = date)
        val read = readArticle(id = "read", date = date)
        val duplicate = unreadArticle(id = "unread", date = date)
        val target = unreadArticle(id = "target", date = date)

        val selected = selectArticlesToMark(
            items = articleItems(unread, read, duplicate, target),
            targetArticleId = "target",
            markAbove = true,
        )

        assertEquals(listOf("unread"), selected.map { it.article.id })
    }

    private fun articleItems(vararg articles: ArticleWithFeed): List<ArticleFlowItem> =
        articles.map { ArticleFlowItem.Article(it) }

    private fun unreadArticle(id: String, date: Date): ArticleWithFeed =
        ArticleWithFeed(
            article =
                Article(
                    id = id,
                    date = date,
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

    private fun readArticle(id: String, date: Date): ArticleWithFeed =
        unreadArticle(id = id, date = date).run {
            copy(article = article.copy(isUnread = false))
        }

    private fun sampleFeed(): Feed =
        Feed(
            id = "feed",
            name = "Feed",
            url = "https://example.com/feed",
            groupId = "group",
            accountId = 1,
        )
}
