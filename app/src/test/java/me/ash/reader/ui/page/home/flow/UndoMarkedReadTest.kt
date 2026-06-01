package me.ash.reader.ui.page.home.flow

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoMarkedReadTest {

    @Test
    fun `uses diff-map undo when commits are deferred`() {
        val items = listOf(unreadArticle("a"), unreadArticle("b"))
        var diffUndoCalled = false
        var repoUndoCalled = false

        undoMarkedRead(
            items = items,
            deferDbCommits = true,
            undoWithDiffMap = {
                diffUndoCalled = true
                assertEquals(2, it.size)
            },
            undoWithRepository = {
                repoUndoCalled = true
            },
        )

        assertTrue(diffUndoCalled)
        assertEquals(false, repoUndoCalled)
    }

    @Test
    fun `uses repository undo with deduped ids when commits are immediate`() {
        val duplicate = unreadArticle("a")
        val items = listOf(duplicate, duplicate, unreadArticle("b"))
        var diffUndoCalled = false
        var repoIds: Set<String> = emptySet()

        undoMarkedRead(
            items = items,
            deferDbCommits = false,
            undoWithDiffMap = {
                diffUndoCalled = true
            },
            undoWithRepository = { ids ->
                repoIds = ids
            },
        )

        assertEquals(false, diffUndoCalled)
        assertEquals(setOf("a", "b"), repoIds)
    }

    private fun unreadArticle(id: String): ArticleWithFeed =
        ArticleWithFeed(
            article =
                Article(
                    id = id,
                    date = Date(0L),
                    title = "Article",
                    rawDescription = "<p>Article</p>",
                    shortDescription = "Article",
                    link = "https://example.com/article/$id",
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
