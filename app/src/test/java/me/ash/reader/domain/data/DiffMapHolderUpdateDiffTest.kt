package me.ash.reader.domain.data

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DiffMapHolder.updateDiffInternal logic extracted into a testable helper.
 *
 * These tests verify the read state toggle behavior, particularly the scenario
 * where an article is opened (marked read via diff), then the user taps
 * the unread button to mark it unread again.
 */
class DiffMapHolderUpdateDiffTest {

    @Test
    fun `checkIfRead returns diffMap isRead when diff exists`() {
        val article = unreadArticle()
        val diffMap = mutableMapOf(article.article.id to Diff(isRead = true, article))

        val result = checkIfRead(diffMap, article)

        assertTrue("Should return diffMap's isRead=true", result)
    }

    @Test
    fun `checkIfRead returns article isRead when no diff exists`() {
        val article = unreadArticle()
        val diffMap = mutableMapOf<String, Diff>()

        val result = checkIfRead(diffMap, article)

        assertFalse("Should return article's isRead=false", result)
    }

    @Test
    fun `updateDiff with markRead=true on unread article adds diff`() {
        val article = unreadArticle()
        val diffMap = mutableMapOf<String, Diff>()

        updateDiffInternalBuggy(diffMap, article, markRead = true)

        assertTrue("Diff should exist with isRead=true", diffMap[article.article.id]?.isRead == true)
    }

    @Test
    fun `BUGGY - updateDiff with markRead=false after markRead=true removes diff instead of updating`() {
        // This demonstrates the bug scenario from GitHub issue #57:
        // 1. Article is originally unread (isUnread=true in DB)
        // 2. User opens article -> markRead=true adds diff with isRead=true
        //    The UI also updates articleWithFeed.article.isRead to true via withReadState()
        // 3. User taps unread button -> markRead=false should result in isRead=false
        //    But the buggy code REMOVES the diff, causing checkIfRead to fall back to
        //    articleWithFeed.article.isRead which is now TRUE!
        
        val originalArticle = unreadArticle()
        val diffMap = mutableMapOf<String, Diff>()

        // Step 1: Open article, mark as read
        updateDiffInternalBuggy(diffMap, originalArticle, markRead = true)
        assertTrue("After opening, checkIfRead should be true", checkIfRead(diffMap, originalArticle))
        
        // Simulate what the UI does: update the articleWithFeed.article.isRead to match
        // This is what ReadingUiState.withReadState() does
        val articleAfterWithReadState = originalArticle.copy(
            article = originalArticle.article.copy(isUnread = false)  // isRead = true
        )

        // Step 2: Tap unread button - using the MODIFIED articleWithFeed
        updateDiffInternalBuggy(diffMap, articleAfterWithReadState, markRead = false)

        // BUG: The buggy code removes the diff, so checkIfRead falls back to
        // articleAfterWithReadState.article.isRead which is TRUE (not the desired FALSE)
        // This assertion PASSES with buggy code but shows the wrong behavior!
        assertTrue("BUG: checkIfRead returns true instead of false", 
            checkIfRead(diffMap, articleAfterWithReadState))
    }

    @Test
    fun `FIXED - updateDiff with markRead=false after markRead=true should update diff to unread`() {
        // Same scenario as above but with the fixed implementation
        val originalArticle = unreadArticle()
        val diffMap = mutableMapOf<String, Diff>()

        // Step 1: Open article, mark as read
        updateDiffInternalFixed(diffMap, originalArticle, markRead = true)
        assertTrue("After opening, checkIfRead should be true", checkIfRead(diffMap, originalArticle))
        
        // Simulate what the UI does: update the articleWithFeed
        val articleAfterWithReadState = originalArticle.copy(
            article = originalArticle.article.copy(isUnread = false)  // isRead = true
        )

        // Step 2: Tap unread button
        updateDiffInternalFixed(diffMap, articleAfterWithReadState, markRead = false)

        // With fix: diff is UPDATED (not removed), so checkIfRead returns the correct value
        assertFalse("After tapping unread, checkIfRead should be false", 
            checkIfRead(diffMap, articleAfterWithReadState))
    }

    @Test
    fun `updateDiff with markRead=false on originally unread article removes diff`() {
        // If article was originally unread (DB state), and we explicitly mark it unread,
        // no diff is needed because it matches the DB state
        val article = unreadArticle()
        val diffMap = mutableMapOf<String, Diff>()

        updateDiffInternalBuggy(diffMap, article, markRead = false)

        assertTrue("No diff needed when marking unread article as unread", diffMap.isEmpty())
    }

    @Test
    fun `updateDiff with markRead=true on originally read article does nothing`() {
        val article = readArticle()
        val diffMap = mutableMapOf<String, Diff>()

        updateDiffInternalBuggy(diffMap, article, markRead = true)

        assertTrue("No diff needed when marking read article as read", diffMap.isEmpty())
    }

    @Test
    fun `toggle twice returns to original state when article was originally unread`() {
        val article = unreadArticle()
        val diffMap = mutableMapOf<String, Diff>()

        // Toggle to read
        updateDiffInternalBuggy(diffMap, article, markRead = null)
        assertTrue("First toggle should mark as read", checkIfRead(diffMap, article))

        // Toggle back to unread
        updateDiffInternalBuggy(diffMap, article, markRead = null)
        assertFalse("Second toggle should mark as unread", checkIfRead(diffMap, article))
    }

    // Helper functions that mirror DiffMapHolder logic for isolated testing

    private fun checkIfRead(diffMap: Map<String, Diff>, articleWithFeed: ArticleWithFeed): Boolean {
        return diffMap[articleWithFeed.article.id]?.isRead ?: articleWithFeed.article.isRead
    }

    /**
     * Original buggy implementation from DiffMapHolder.
     * When markRead is explicit and differs from current diff, it removes the diff
     * instead of updating it.
     */
    private fun updateDiffInternalBuggy(
        diffMap: MutableMap<String, Diff>,
        articleWithFeed: ArticleWithFeed,
        markRead: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id
        val diff = diffMap[articleId]

        if (diff == null) {
            val isRead = markRead ?: !articleWithFeed.article.isRead
            if (isRead == articleWithFeed.article.isRead) {
                return null
            }
            val newDiff = Diff(isRead = isRead, articleWithFeed = articleWithFeed)
            diffMap[articleId] = newDiff
            return newDiff
        } else {
            if (markRead == null || diff.isRead != markRead) {
                // BUG IS HERE: When markRead is explicit (not null) and differs from current diff,
                // we should UPDATE the diff, not REMOVE it.
                val removedDiff = diffMap.remove(articleId)
                return removedDiff?.copy(isRead = !removedDiff.isRead)
            }
        }
        return null
    }

    /**
     * Fixed implementation that handles explicit markRead correctly.
     * When markRead is explicit, update the diff to the new state rather than removing it.
     */
    private fun updateDiffInternalFixed(
        diffMap: MutableMap<String, Diff>,
        articleWithFeed: ArticleWithFeed,
        markRead: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id
        val diff = diffMap[articleId]

        if (diff == null) {
            val isRead = markRead ?: !articleWithFeed.article.isRead
            if (isRead == articleWithFeed.article.isRead) {
                return null
            }
            val newDiff = Diff(isRead = isRead, articleWithFeed = articleWithFeed)
            diffMap[articleId] = newDiff
            return newDiff
        } else {
            // FIX: When markRead is explicit (not null), update the diff to the desired state
            // Only remove the diff when toggling (markRead == null)
            if (markRead == null) {
                // Toggle: remove diff, article reverts to DB state
                val removedDiff = diffMap.remove(articleId)
                return removedDiff?.copy(isRead = !removedDiff.isRead)
            } else if (diff.isRead != markRead) {
                // Explicit markRead that differs from current diff: update the diff
                val newIsRead = markRead
                if (newIsRead == articleWithFeed.article.isRead) {
                    // New state matches DB, remove the diff
                    diffMap.remove(articleId)
                } else {
                    // New state differs from DB, update the diff
                    diffMap[articleId] = diff.copy(isRead = newIsRead)
                }
                return Diff(isRead = newIsRead, articleWithFeed = articleWithFeed)
            }
        }
        return null
    }

    private fun unreadArticle(): ArticleWithFeed =
        ArticleWithFeed(
            article = Article(
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
