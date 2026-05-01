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
