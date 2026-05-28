package me.ash.reader.domain.data

import android.content.Context
import java.nio.file.Files
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
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

    @Test
    fun `updateDiff persists pending op before marking local commit`() {
        val pendingReadStateOpDao = pendingReadStateOpDao()
        val holder = createHolder(
            pendingReadStateOpDao = pendingReadStateOpDao,
            deferDbCommits = false,
        )
        assertFalse(holder.shouldSyncWithRemote)

        holder.updateDiff(unreadArticle(), markRead = true)

        runBlocking {
            val opsCaptor = argumentCaptor<List<PendingReadStateOp>>()
            inOrder(pendingReadStateOpDao).apply {
                verify(pendingReadStateOpDao).upsertAll(opsCaptor.capture())
                verify(pendingReadStateOpDao).markLocalCommitted(setOf("article"), isUnread = false)
            }
            assertTrue(opsCaptor.firstValue.single().remoteSynced)
        }
    }

    @Test
    fun `flushDeferredDiffs waits for pending op persistence`() {
        val pendingReadStateOpDao = pendingReadStateOpDao()
        val holder = createHolder(
            pendingReadStateOpDao = pendingReadStateOpDao,
            deferDbCommits = true,
        )

        holder.updateDiff(unreadArticle(), markRead = true)
        holder.flushDeferredDiffs()

        runBlocking {
            inOrder(pendingReadStateOpDao).apply {
                verify(pendingReadStateOpDao).upsertAll(any())
                verify(pendingReadStateOpDao).queryLocalPending(eq(1))
            }
        }
    }

    @Test
    fun `prepareReadStateForSync persists pending sync ops before querying remote queue`() {
        val pendingReadStateOpDao = pendingReadStateOpDao()
        var upsertCount = 0
        var firstRemoteQueryUpsertCount: Int? = null
        runBlocking {
            whenever(pendingReadStateOpDao.upsertAll(any())).thenAnswer {
                upsertCount += 1
                Unit
            }
            whenever(pendingReadStateOpDao.queryRemotePending(eq(1))).thenAnswer {
                if (firstRemoteQueryUpsertCount == null) {
                    firstRemoteQueryUpsertCount = upsertCount
                }
                emptyList<PendingReadStateOp>()
            }
        }
        val holder = createHolder(
            account = Account(
                id = 1,
                name = "FreshRSS",
                type = AccountType(AccountType.FreshRSS.id),
            ),
            pendingReadStateOpDao = pendingReadStateOpDao,
            deferDbCommits = true,
        )

        holder.updateDiff(unreadArticle(), markRead = true)
        runBlocking {
            holder.prepareReadStateForSync(1)
            assertEquals(2, firstRemoteQueryUpsertCount)
        }
    }

    @Test
    fun `prepareReadStateForSync preserves local commit when re-persisting same diff`() = runBlocking {
        val pendingReadStateOpDao = pendingReadStateOpDao()
        val existingOp = PendingReadStateOp(
            articleId = "article",
            accountId = 1,
            feedId = "feed",
            isUnread = false,
            localCommitted = true,
            remoteSynced = false,
        )
        val upsertCaptor = argumentCaptor<List<PendingReadStateOp>>()
        whenever(pendingReadStateOpDao.queryByArticleIds(eq(setOf("article"))))
            .thenReturn(listOf(existingOp))

        val holder = createHolder(
            account = Account(
                id = 1,
                name = "FreshRSS",
                type = AccountType(AccountType.FreshRSS.id),
            ),
            pendingReadStateOpDao = pendingReadStateOpDao,
            deferDbCommits = true,
        )

        holder.updateDiff(unreadArticle(), markRead = true)
        holder.prepareReadStateForSync(1)

        verify(pendingReadStateOpDao, atLeastOnce()).upsertAll(upsertCaptor.capture())
        assertTrue(
            upsertCaptor.allValues.last().single().localCommitted
        )
    }

    @Test
    fun `flushPendingSyncDiffs waits for pending op persistence before remote mark`() = runBlocking {
        val pendingReadStateOpDao = delayedPendingReadStateOpDao()
        val rssRepository = mock<AbstractRssRepository>()
        whenever(rssRepository.syncReadStatus(setOf("article"), true)).thenReturn(setOf("article"))
        whenever(rssRepository.syncReadStatus(emptySet(), false)).thenReturn(emptySet())

        val holder = createHolder(
            account = Account(
                id = 1,
                name = "FreshRSS",
                type = AccountType(AccountType.FreshRSS.id),
            ),
            pendingReadStateOpDao = pendingReadStateOpDao,
            rssRepository = rssRepository,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ioDispatcher = Dispatchers.Default,
            deferDbCommits = true,
        )

        while (!holder.shouldSyncWithRemote) {
            delay(10)
        }

        val article = unreadArticle()
        holder.updateDiff(article, markRead = true)
        invokeFlushPendingSyncDiffs(
            holder,
            mapOf(article.article.id to Diff(true, article))
        )

        assertEquals(
            listOf("upsertAll:end", "markRemoteSynced"),
            pendingReadStateOpDao.events.filter { it == "upsertAll:end" || it == "markRemoteSynced" }
        )
    }

    private fun createHolder(
        account: Account = Account(
            id = 1,
            name = "Local",
            type = AccountType(AccountType.Local.id),
        ),
        pendingReadStateOpDao: PendingReadStateOpDao = pendingReadStateOpDao(),
        rssRepository: AbstractRssRepository = mock<AbstractRssRepository>(),
        applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        deferDbCommits: Boolean = true,
    ): DiffMapHolder {
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(Files.createTempDirectory("diff-map-holder-test").toFile())

        val localAccountFlow = MutableStateFlow(account)
        val accountService = mock<AccountService>()
        whenever(accountService.currentAccountFlow).thenReturn(localAccountFlow)

        val rssService = mock<RssService>()
        whenever(rssService.get()).thenReturn(rssRepository)

        return DiffMapHolder(
            context = context,
            applicationScope = applicationScope,
            ioDispatcher = ioDispatcher,
            mainDispatcher = Dispatchers.Unconfined,
            accountService = accountService,
            rssService = rssService,
            pendingReadStateOpDao = pendingReadStateOpDao,
        ).apply {
            this.deferDbCommits = deferDbCommits
        }
    }

    private fun pendingReadStateOpDao(): PendingReadStateOpDao {
        val pendingReadStateOpDao = mock<PendingReadStateOpDao>()
        runBlocking {
            whenever(pendingReadStateOpDao.queryLocalPending(eq(1))).thenReturn(emptyList<PendingReadStateOp>())
            whenever(pendingReadStateOpDao.queryRemotePending(eq(1))).thenReturn(emptyList<PendingReadStateOp>())
            whenever(pendingReadStateOpDao.queryByAccountId(eq(1))).thenReturn(emptyList<PendingReadStateOp>())
            whenever(pendingReadStateOpDao.queryByArticleIds(any())).thenReturn(emptyList<PendingReadStateOp>())
        }
        return pendingReadStateOpDao
    }

    private fun delayedPendingReadStateOpDao(): DelayedPendingReadStateOpDao = DelayedPendingReadStateOpDao()

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

    private suspend fun invokeFlushPendingSyncDiffs(
        holder: DiffMapHolder,
        diffs: Map<String, Diff>,
    ) {
        val method = DiffMapHolder::class.declaredFunctions.single {
            it.name == "flushPendingSyncDiffs"
        }
        method.isAccessible = true
        method.callSuspend(holder, diffs)
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

    private class DelayedPendingReadStateOpDao : PendingReadStateOpDao {
        val events = mutableListOf<String>()
        private val ops = linkedMapOf<String, PendingReadStateOp>()
        private var delayNextUpsert = true

        override suspend fun upsert(op: PendingReadStateOp) {
            upsertAll(listOf(op))
        }

        override suspend fun upsertAll(ops: List<PendingReadStateOp>) {
            events += "upsertAll:start"
            if (delayNextUpsert) {
                delayNextUpsert = false
                delay(200)
            }
            ops.forEach { this.ops[it.articleId] = it }
            events += "upsertAll:end"
        }

        override suspend fun queryByAccountId(accountId: Int): List<PendingReadStateOp> =
            ops.values.filter { it.accountId == accountId }

        override suspend fun queryByArticleIds(articleIds: Set<String>): List<PendingReadStateOp> =
            articleIds.mapNotNull { ops[it] }

        override suspend fun queryLocalPending(accountId: Int): List<PendingReadStateOp> =
            ops.values.filter { it.accountId == accountId && !it.localCommitted }

        override suspend fun queryRemotePending(accountId: Int): List<PendingReadStateOp> =
            ops.values.filter { it.accountId == accountId && !it.remoteSynced }

        override suspend fun markLocalCommitted(articleIds: Set<String>, isUnread: Boolean) {
            articleIds.forEach { articleId ->
                ops[articleId]?.takeIf { it.isUnread == isUnread }?.let { op ->
                    ops[articleId] = op.copy(localCommitted = true)
                }
            }
        }

        override suspend fun markRemoteSynced(articleIds: Set<String>, isUnread: Boolean) {
            events += "markRemoteSynced"
            articleIds.forEach { articleId ->
                ops[articleId]?.takeIf { it.isUnread == isUnread }?.let { op ->
                    ops[articleId] = op.copy(remoteSynced = true)
                }
            }
        }

        override suspend fun deleteCompleted() {
            ops.entries.removeIf { (_, op) -> op.localCommitted && op.remoteSynced }
        }

        override suspend fun deleteByArticleIdsAndUnreadState(articleIds: Set<String>, isUnread: Boolean) {
            articleIds.forEach { articleId ->
                ops[articleId]?.takeIf { it.isUnread == isUnread }?.let {
                    ops.remove(articleId)
                }
            }
        }

        override suspend fun deleteByAccountId(accountId: Int) {
            ops.entries.removeIf { (_, op) -> op.accountId == accountId }
        }
    }
}
