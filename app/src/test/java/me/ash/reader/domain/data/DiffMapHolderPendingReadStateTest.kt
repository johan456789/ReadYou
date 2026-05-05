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
import me.ash.reader.domain.model.article.PendingReadStateOp
import me.ash.reader.domain.repository.PendingReadStateOpDao
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DiffMapHolderPendingReadStateTest {

    @Test
    fun `local account applies and deletes pending read state ops on init`() = runBlocking {
        val pendingReadStateOpDao = pendingReadStateOpDaoWith(
            localPending = listOf(
                PendingReadStateOp(
                    articleId = ARTICLE_ID,
                    accountId = ACCOUNT_ID,
                    feedId = FEED_ID,
                    isUnread = false,
                    updatedAt = Date(0L),
                    localCommitted = false,
                    remoteSynced = true,
                )
            )
        )
        val rssRepository = mock<AbstractRssRepository>()

        createHolder(
            account = Account(
                id = ACCOUNT_ID,
                name = "Local",
                type = AccountType(AccountType.Local.id),
            ),
            pendingReadStateOpDao = pendingReadStateOpDao,
            rssRepository = rssRepository,
        )

        verify(rssRepository).batchMarkAsRead(setOf(ARTICLE_ID), markRead = true)
        verify(pendingReadStateOpDao).markLocalCommitted(setOf(ARTICLE_ID), isUnread = false)
        verify(pendingReadStateOpDao).deleteCompleted()
    }

    @Test
    fun `remote account applies pending read state ops locally and marks localCommitted`() = runBlocking {
        val pendingReadStateOpDao = pendingReadStateOpDaoWith(
            localPending = listOf(
                PendingReadStateOp(
                    articleId = ARTICLE_ID,
                    accountId = ACCOUNT_ID,
                    feedId = FEED_ID,
                    isUnread = false,
                    updatedAt = Date(0L),
                    localCommitted = false,
                    remoteSynced = false,
                )
            )
        )
        val rssRepository = mock<AbstractRssRepository>()

        createHolder(
            account = Account(
                id = ACCOUNT_ID,
                name = "FreshRSS",
                type = AccountType(AccountType.FreshRSS.id),
            ),
            pendingReadStateOpDao = pendingReadStateOpDao,
            rssRepository = rssRepository,
        )

        verify(rssRepository).batchMarkAsRead(setOf(ARTICLE_ID), markRead = true)
        verify(pendingReadStateOpDao).markLocalCommitted(setOf(ARTICLE_ID), isUnread = false)
        verify(pendingReadStateOpDao).deleteCompleted()
    }

    private fun createHolder(
        account: Account,
        pendingReadStateOpDao: PendingReadStateOpDao,
        rssRepository: AbstractRssRepository,
    ): DiffMapHolder {
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(
            Files.createTempDirectory("diff-map-holder-pending-test").toFile()
        )

        val accountService = mock<AccountService>()
        whenever(accountService.currentAccountFlow).thenReturn(MutableStateFlow(account))

        val rssService = mock<RssService>()
        whenever(rssService.get()).thenReturn(rssRepository)

        return DiffMapHolder(
            context = context,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            accountService = accountService,
            rssService = rssService,
            pendingReadStateOpDao = pendingReadStateOpDao,
        )
    }

    private fun pendingReadStateOpDaoWith(
        localPending: List<PendingReadStateOp> = emptyList(),
        remotePending: List<PendingReadStateOp> = emptyList(),
    ): PendingReadStateOpDao {
        val pendingReadStateOpDao = mock<PendingReadStateOpDao>()
        runBlocking {
            whenever(pendingReadStateOpDao.queryLocalPending(eq(ACCOUNT_ID))).thenReturn(localPending)
            whenever(pendingReadStateOpDao.queryRemotePending(eq(ACCOUNT_ID))).thenReturn(remotePending)
            whenever(pendingReadStateOpDao.queryByAccountId(eq(ACCOUNT_ID))).thenReturn(localPending + remotePending)
        }
        return pendingReadStateOpDao
    }

    private companion object {
        const val ACCOUNT_ID = 1
        const val ARTICLE_ID = "1\$article"
        const val FEED_ID = "1\$feed"
    }
}
