package me.ash.reader.domain.data

import android.content.Context
import java.nio.file.Files
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.PendingReadStateOp
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.PendingReadStateOpDao
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DiffMapHolderUpdateDiffTest {

    @Test
    fun `checkIfRead returns diff value when diff exists`() {
        val holder = createHolder()
        val article = unreadArticle()

        invokeUpdateDiffInternal(holder, article, markRead = true)

        assertTrue(holder.checkIfRead(article))
    }

    @Test
    fun `explicit mark unread after open keeps unread state even if UI article was changed to read`() {
        val holder = createHolder()
        val originalArticle = unreadArticle()

        invokeUpdateDiffInternal(holder, originalArticle, markRead = true)
        assertTrue(holder.checkIfRead(originalArticle))

        val articleAfterWithReadState = originalArticle.copy(
            article = originalArticle.article.copy(isUnread = false)
        )
        invokeUpdateDiffInternal(holder, articleAfterWithReadState, markRead = false)

        assertFalse(holder.checkIfRead(articleAfterWithReadState))
    }

    @Test
    fun `explicit mark returning to baseline removes stale diff`() {
        val holder = createHolder()
        val originallyReadArticle = readArticle()

        invokeUpdateDiffInternal(holder, originallyReadArticle, markRead = false)
        assertFalse(holder.checkIfRead(originallyReadArticle))

        invokeUpdateDiffInternal(holder, originallyReadArticle, markRead = true)

        assertTrue(holder.checkIfRead(originallyReadArticle))
        assertFalse(holder.diffMap.containsKey(originallyReadArticle.article.id))
    }

    @Test
    fun `toggle twice returns to original state`() {
        val holder = createHolder()
        val article = unreadArticle()

        invokeUpdateDiffInternal(holder, article, markRead = null)
        assertTrue(holder.checkIfRead(article))

        invokeUpdateDiffInternal(holder, article, markRead = null)
        assertFalse(holder.checkIfRead(article))
    }

    @Test
    fun `toggle uses current diff state not stale article state`() {
        // This test reproduces the bug from issue #59:
        // 1. Article in DB is originally unread (isUnread=true)
        // 2. User opens article -> auto-marked as read via diffMap
        // 3. User taps "mark as unread" button in reading page
        //    - Reading page passes articleWithFeed with MUTATED isUnread=false (from withReadState)
        //    - This creates a diff with isRead=false
        // 4. User goes back to flow list
        // 5. User swipes to toggle
        //    - Paging list passes articleWithFeed with ORIGINAL isUnread=true (stale DB state)
        //    - Toggle should use diffMap's state (isRead=false) and toggle to read
        //    - BUG: Current implementation removes the diff, falling back to stale article.isRead=false
        val holder = createHolder()
        val originalUnreadArticle = unreadArticle()

        // Step 2: User opens article, it gets marked as read
        invokeUpdateDiffInternal(holder, originalUnreadArticle, markRead = true)
        assertTrue(holder.checkIfRead(originalUnreadArticle))

        // Step 3: User taps "mark as unread" button in reading page
        // The reading page's articleWithFeed has been mutated by withReadState(true)
        // so it now has article.isRead = true (article.isUnread = false)
        val readingPageArticle = originalUnreadArticle.copy(
            article = originalUnreadArticle.article.copy(isUnread = false)
        )
        invokeUpdateDiffInternal(holder, readingPageArticle, markRead = false)
        assertFalse(holder.checkIfRead(originalUnreadArticle))

        // Step 4-5: User goes back to flow list. 
        // The paging data still has the stale article with isUnread = true (original DB state),
        // but diffMap has isRead = false

        // Step 6: User swipes to toggle. The paging list passes the stale article.
        // Toggle should see that diffMap says "unread" and toggle to "read"
        invokeUpdateDiffInternal(holder, originalUnreadArticle, markRead = null)

        // The article should now be read (toggled from the diffMap's unread state)
        assertTrue("After toggle, article should be read (toggled from diffMap unread state)",
            holder.checkIfRead(originalUnreadArticle))
    }

    private fun createHolder(): DiffMapHolder {
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(Files.createTempDirectory("diff-map-holder-test").toFile())

        val localAccountFlow = MutableStateFlow(
            Account(
                id = 1,
                name = "Local",
                type = AccountType.Local,
            )
        )
        val accountService = mock<AccountService>()
        whenever(accountService.currentAccountFlow).thenReturn(localAccountFlow)

        val pendingReadStateOpDao = mock<PendingReadStateOpDao>()
        runBlocking {
            whenever(pendingReadStateOpDao.queryByAccountId(eq(1))).thenReturn(emptyList<PendingReadStateOp>())
        }

        val rssRepository = mock<AbstractRssRepository>()
        val rssService = mock<RssService>()
        whenever(rssService.get()).thenReturn(rssRepository)

        return DiffMapHolder(
            context = context,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            accountService = accountService,
            rssService = rssService,
            pendingReadStateOpDao = pendingReadStateOpDao,
        ).apply {
            deferDbCommits = true
        }
    }

    private fun invokeUpdateDiffInternal(
        holder: DiffMapHolder,
        articleWithFeed: ArticleWithFeed,
        markRead: Boolean?,
    ): Diff? {
        val method = DiffMapHolder::class.java.getDeclaredMethod(
            "updateDiffInternal",
            ArticleWithFeed::class.java,
            Boolean::class.javaObjectType,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(holder, articleWithFeed, markRead) as Diff?
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
