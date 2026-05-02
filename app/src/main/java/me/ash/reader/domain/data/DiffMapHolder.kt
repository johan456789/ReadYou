package me.ash.reader.domain.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.PendingReadStateOp
import me.ash.reader.domain.repository.PendingReadStateOpDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.dollarLast
import java.io.File
import javax.inject.Inject

@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val pendingReadStateOpDao: PendingReadStateOpDao,
) {
    val diffMap = mutableStateMapOf<String, Diff>()

    /**
     * When true, DB commits for read state changes are deferred.
     * This is used in the Unread filter view to prevent articles from immediately
     * disappearing from the list when marked as read. The UI uses diffMap for
     * visual state (grayed out), while DB updates are batched until this is set to false.
     */
    @Volatile
    var deferDbCommits: Boolean = false

    private val deferredDiffs = mutableMapOf<String, Diff>()

    private val pendingSyncDiffs = mutableStateMapOf<String, Diff>()
    private val syncedDiffs = mutableMapOf<String, Diff>()
    private val pendingSyncMutex = Mutex()

    val diffMapSnapshotFlow = snapshotFlow { diffMap.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    private val pendingSyncDiffsSnapshotFlow = snapshotFlow { pendingSyncDiffs.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    val shouldSyncWithRemote get() = currentAccount?.type != AccountType.Local

    private val gson = Gson()

    private val cacheDir = context.cacheDir.resolve("diff")
    private var userCacheDir = cacheDir

    private var currentAccount: Account? = null

    private val cacheFile: File get() = userCacheDir.resolve("diff_map.json")

    var remoteJob: Job? = null

    init {
        applicationScope.launch {
            accountService.currentAccountFlow.mapNotNull { it }.collect { account ->
                val previousAccount = currentAccount
                if (previousAccount != null && previousAccount != account) {
                    cleanup(previousAccount)
                }
                currentAccount = account
                init(account)
            }
        }
    }

    private fun init(account: Account) {
        userCacheDir = cacheDir.resolve(account.id.toString())
        if (account.type == AccountType.Local) {
            account.id?.let(::commitLocalPendingReadStateOps)
        }
        commitDiffsFromCache()
        if (account.type != AccountType.Local) {
            syncOnChange()
        }
    }

    private suspend fun cleanup(@Suppress("UNUSED_PARAMETER") previousAccount: Account) {
        remoteJob?.cancel()
        val deferredToCommit: Map<String, Diff> =
            synchronized(deferredDiffs) {
                val snapshot = deferredDiffs.toMap()
                deferredDiffs.clear()
                snapshot
            }
        if (deferredToCommit.isNotEmpty()) {
            commitAppliedDiffsToDb(deferredToCommit)
        }
        commitDiffsToDb()
        diffMap.clear()
        pendingSyncDiffs.clear()
        syncedDiffs.clear()
    }

    private fun syncOnChange() {
        remoteJob = applicationScope.launch(ioDispatcher) {
            pendingSyncDiffsSnapshotFlow.debounce(2_000).collect {
                pendingSyncMutex.withLock {
                    withContext(ioDispatcher) {
                        flushPendingSyncDiffs(it)
                    }
                }
            }
        }
    }

    fun checkIfRead(articleWithFeed: ArticleWithFeed): Boolean {
        return diffMap[articleWithFeed.article.id]?.isRead ?: articleWithFeed.article.isRead
    }

    /**
     * Updates the diff map with changes to an article's read status.
     *
     * This function manages a map (`diffMap`) that tracks pending changes (diffs) to the
     * read status of articles. These changes are not immediately applied to the
     * underlying data store but are held in `diffMap` until a later commit operation.
     *
     * The function supports three modes of updating:
     *
     * 1. **Toggle:** If `markRead` is `null`, the function toggles the current read
     *    status of the article.
     * 2. **Mark as Read:** If `markRead` is `true`, the article will be marked as read,
     *    regardless of its current status.
     * 3. **Mark as Unread:** If `markRead` is `false`, the article will be marked as unread,
     *    regardless of its current status.
     *
     * The function determines if a change needs to be tracked based on the current status and desired status:
     *  - If the requested change matches the article's current status, the diff is removed from the map, if it exists. (No change is needed.)
     *  - Otherwise, the diff is added to or updated in the map.
     *
     * @param articleWithFeed The article and its associated feed data. This is used to identify the article
     *                        and access its current read state.
     * @param markRead An optional boolean indicating the desired read status of the article.
     *                 - `null`: Toggles the current read status.
     *                 - `true`: Marks the article as read.
     *                 - `false`: Marks the article as unread.
     *
     * @return A [Diff] object representing the changes made to the article.
     *
     * @see Diff
     */
    private fun updateDiffInternal(
        articleWithFeed: ArticleWithFeed, markRead: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id

        val diff = diffMap[articleId]

        if (diff == null) {
            val isRead = markRead ?: !articleWithFeed.article.isRead
            if (isRead == articleWithFeed.article.isRead) {
                return null
            }
            val newDiff = Diff(
                isRead = isRead, articleWithFeed = articleWithFeed
            )
            diffMap[articleId] = newDiff
            return newDiff
        } else {
            if (markRead == null) {
                // Toggle: flip the existing diff's isRead state
                val toggledIsRead = !diff.isRead
                val updatedDiff = diff.copy(isRead = toggledIsRead)
                if (toggledIsRead == articleWithFeed.article.isRead) {
                    // Toggling brings us back to the baseline, remove the diff
                    diffMap.remove(articleId)
                } else {
                    diffMap[articleId] = updatedDiff
                }
                return updatedDiff
            } else if (diff.isRead != markRead) {
                // Explicit markRead that differs from current diff.
                // If it matches the baseline article state, this diff becomes a no-op and should be removed.
                if (markRead == articleWithFeed.article.isRead) {
                    diffMap.remove(articleId)
                    return diff.copy(isRead = markRead)
                }
                val updatedDiff = diff.copy(isRead = markRead)
                diffMap[articleId] = updatedDiff
                return updatedDiff
            }
        }
        return null
    }

    fun updateDiff(
        vararg articleWithFeed: ArticleWithFeed, markRead: Boolean? = null
    ) {
        val appliedDiffs = articleWithFeed.mapNotNull {
            updateDiffInternal(it, markRead)
        }
        if (appliedDiffs.isEmpty()) return

        if (shouldSyncWithRemote) {
            appliedDiffs.forEach {
                appendDiffToSync(it)
            }
        }

        android.util.Log.d("DiffMapHolder", "updateDiff: deferDbCommits=$deferDbCommits, appliedDiffsCount=${appliedDiffs.size}")
        val diffsToCommitNow: Map<String, Diff>? =
            synchronized(deferredDiffs) {
                if (deferDbCommits) {
                    android.util.Log.d("DiffMapHolder", "Deferring DB commits for ${appliedDiffs.size} articles")
                    appliedDiffs.forEach { deferredDiffs[it.articleId] = it }
                    if (!shouldSyncWithRemote) {
                        applicationScope.launch(ioDispatcher) {
                            persistPendingReadStateOps(appliedDiffs)
                        }
                    }
                    null
                } else {
                    appliedDiffs.associateBy { it.articleId }
                }
            }
        if (diffsToCommitNow != null) {
            android.util.Log.d("DiffMapHolder", "Immediately committing ${diffsToCommitNow.size} articles to DB")
            applicationScope.launch(ioDispatcher) {
                commitAppliedDiffsToDb(diffsToCommitNow)
            }
        }
    }

    /**
     * Flushes any deferred DB commits. Call this when exiting the Unread filter view,
     * starting a sync, or when the user navigates away from the flow page.
     */
    fun flushDeferredDiffs() {
        val diffsToCommit: Map<String, Diff>
        synchronized(deferredDiffs) {
            if (deferredDiffs.isEmpty()) {
                android.util.Log.d("DiffMapHolder", "flushDeferredDiffs: nothing to flush")
                return
            }
            diffsToCommit = deferredDiffs.toMap()
            deferredDiffs.clear()
        }
        android.util.Log.d("DiffMapHolder", "flushDeferredDiffs: flushing ${diffsToCommit.size} articles")
        applicationScope.launch(ioDispatcher) {
            commitAppliedDiffsToDb(diffsToCommit)
        }
    }

    private fun appendDiffToSync(diff: Diff) {
        val syncedDiff = syncedDiffs[diff.articleId]
        if (syncedDiff == null || syncedDiff.isRead != diff.isRead) {
            pendingSyncDiffs[diff.articleId] = diff
            applicationScope.launch(ioDispatcher) {
                toPendingReadStateOp(diff)?.let { pendingReadStateOpDao.upsert(it) }
            }
        }
    }

    suspend fun commitDiffsToDb() {
        val diffsToCommit = diffMap.toMap()
        if (diffsToCommit.isEmpty()) return

        commitAppliedDiffsToDb(diffsToCommit)
    }

    private suspend fun commitAppliedDiffsToDb(diffsToCommit: Map<String, Diff>) {
        if (diffsToCommit.isEmpty()) return

        val diffBatch = ReadStateDiffApplier.toBatch(diffsToCommit)
        rssService.get().batchMarkAsRead(articleIds = diffBatch.markReadIds, markRead = true)
        rssService.get().batchMarkAsRead(articleIds = diffBatch.markUnreadIds, markRead = false)

        ReadStateDiffApplier.removeMatchingDiffs(
            currentDiffs = diffMap,
            appliedDiffs = diffsToCommit,
        )
        synchronized(deferredDiffs) {
            ReadStateDiffApplier.removeMatchingDiffs(
                currentDiffs = deferredDiffs,
                appliedDiffs = diffsToCommit,
            )
        }
    }

    suspend fun prepareReadStateForSync(accountId: Int): Set<String> {
        if (currentAccount?.id != accountId) return emptySet()

        commitDiffsToDb()
        if (!shouldSyncWithRemote) return emptySet()

        return pendingSyncMutex.withLock {
            persistPendingReadStateOps(pendingSyncDiffs.values)
            flushPendingReadStateQueue(accountId)
            pendingReadStateOpDao.queryByAccountId(accountId).map { it.articleId.dollarLast() }.toSet()
        }
    }

    private suspend fun flushPendingSyncDiffs(diffs: Map<String, Diff>) {
        if (!shouldSyncWithRemote) return
        if (diffs.isEmpty()) return
        val toBeSync = diffs
        val markAsReadArticles = toBeSync.filter { it.value.isRead }.map { it.key }.toSet()
        val markAsUnreadArticles = toBeSync.filter { !it.value.isRead }.map { it.key }.toSet()

        val synced = syncReadStateOps(markAsReadArticles, markAsUnreadArticles)

        val syncedDiffsSnapshot = diffs.filter { synced.contains(it.key) }
        ReadStateDiffApplier.removeMatchingDiffs(
            currentDiffs = pendingSyncDiffs,
            appliedDiffs = syncedDiffsSnapshot,
        )
        if (synced.isNotEmpty()) {
            pendingReadStateOpDao.deleteByArticleIds(synced)
        }
        syncedDiffs += syncedDiffsSnapshot
    }

    private fun commitDiffsFromCache() {
        applicationScope.launch(ioDispatcher) {
            if (cacheFile.exists() && cacheFile.canRead()) {
                val tmpJson = cacheFile.readText()
                val mapType = object : TypeToken<Map<String, Diff>>() {}.type
                val diffMapFromCache = gson.fromJson<Map<String, Diff>>(
                    tmpJson, mapType
                )
                diffMapFromCache?.let {
                    diffMap.clear()
                    diffMap.putAll(it)
                    persistPendingReadStateOps(it.values)
                }
                if (cacheFile.canWrite()) {
                    cacheFile.delete()
                }
            }
            commitDiffsToDb()
        }
    }

    private fun commitLocalPendingReadStateOps(accountId: Int) {
        applicationScope.launch(ioDispatcher) {
            val queuedOps = pendingReadStateOpDao.queryByAccountId(accountId)
            if (queuedOps.isEmpty()) return@launch

            val markAsReadArticles = queuedOps.filter { it.isRead }.map { it.articleId }.toSet()
            val markAsUnreadArticles = queuedOps.filter { !it.isRead }.map { it.articleId }.toSet()
            rssService.get().batchMarkAsRead(articleIds = markAsReadArticles, markRead = true)
            rssService.get().batchMarkAsRead(articleIds = markAsUnreadArticles, markRead = false)
            pendingReadStateOpDao.deleteByArticleIds(queuedOps.map { it.articleId }.toSet())
        }
    }

    private suspend fun flushPendingReadStateQueue(accountId: Int) {
        val queuedOps = pendingReadStateOpDao.queryByAccountId(accountId)
        if (queuedOps.isEmpty()) return

        val markAsReadArticles = queuedOps.filter { it.isRead }.map { it.articleId }.toSet()
        val markAsUnreadArticles = queuedOps.filter { !it.isRead }.map { it.articleId }.toSet()
        val synced = syncReadStateOps(markAsReadArticles, markAsUnreadArticles)
        if (synced.isEmpty()) return

        pendingReadStateOpDao.deleteByArticleIds(synced)
        syncedDiffs += queuedOps
            .filter { synced.contains(it.articleId) }
            .associate { it.articleId to Diff(it.isRead, it.articleId, it.feedId) }
    }

    private suspend fun syncReadStateOps(
        markAsReadArticles: Set<String>,
        markAsUnreadArticles: Set<String>,
    ): Set<String> {
        val rssService = rssService.get()
        return supervisorScope {
            val read = async {
                rssService.syncReadStatus(
                    articleIds = markAsReadArticles,
                    markRead = true
                )
            }
            val unread = async {
                rssService.syncReadStatus(
                    articleIds = markAsUnreadArticles,
                    markRead = false
                )
            }
            runCatching { read.await() }.getOrElse { emptySet() } +
                runCatching { unread.await() }.getOrElse { emptySet() }
        }
    }

    private suspend fun persistPendingReadStateOps(diffs: Collection<Diff>) {
        if (diffs.isEmpty()) return
        val ops = diffs.mapNotNull(::toPendingReadStateOp)
        if (ops.isNotEmpty()) {
            pendingReadStateOpDao.upsertAll(ops)
        }
    }

    private fun toPendingReadStateOp(diff: Diff): PendingReadStateOp? {
        val accountId = currentAccount?.id ?: return null
        return PendingReadStateOp(
            articleId = diff.articleId,
            accountId = accountId,
            feedId = diff.feedId,
            isUnread = !diff.isRead,
        )
    }

}

data class Diff(
    val isRead: Boolean, val articleId: String, val feedId: String
) {
    constructor(isRead: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isRead = isRead,
        articleId = articleWithFeed.article.id,
        feedId = articleWithFeed.feed.id,
    )
}
