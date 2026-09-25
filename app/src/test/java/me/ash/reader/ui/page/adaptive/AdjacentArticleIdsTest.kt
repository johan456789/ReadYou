package me.ash.reader.ui.page.adaptive

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Test

class AdjacentArticleIdsTest {

    @Test
    fun `middle article resolves both neighbors`() {
        val items = articleItems(unreadArticle("a"), unreadArticle("b"), unreadArticle("c"))

        val result = adjacentArticleIds(items = items, articleId = "b")

        assertEquals(AdjacentArticleIds(previousId = "a", nextId = "c"), result)
    }

    @Test
    fun `first article has no previous`() {
        val items = articleItems(unreadArticle("a"), unreadArticle("b"))

        val result = adjacentArticleIds(items = items, articleId = "a")

        assertEquals(AdjacentArticleIds(previousId = null, nextId = "b"), result)
    }

    @Test
    fun `last article has no next`() {
        val items = articleItems(unreadArticle("a"), unreadArticle("b"))

        val result = adjacentArticleIds(items = items, articleId = "b")

        assertEquals(AdjacentArticleIds(previousId = "a", nextId = null), result)
    }

    @Test
    fun `date separators are skipped`() {
        val items =
            listOf(
                ArticleFlowItem.Article(unreadArticle("a")),
                ArticleFlowItem.Date(date = "today", showSpacer = false),
                ArticleFlowItem.Article(unreadArticle("b")),
            )

        val result = adjacentArticleIds(items = items, articleId = "a")

        assertEquals(AdjacentArticleIds(previousId = null, nextId = "b"), result)
    }

    @Test
    fun `unknown and null ids resolve to no neighbors`() {
        val items = articleItems(unreadArticle("a"), unreadArticle("b"))

        assertEquals(
            AdjacentArticleIds(previousId = null, nextId = null),
            adjacentArticleIds(items = items, articleId = "missing"),
        )
        assertEquals(
            AdjacentArticleIds(previousId = null, nextId = null),
            adjacentArticleIds(items = items, articleId = null),
        )
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
            feed =
                Feed(
                    id = "feed",
                    name = "Feed",
                    url = "https://example.com/feed",
                    groupId = "group",
                    accountId = 1,
                ),
        )
}
