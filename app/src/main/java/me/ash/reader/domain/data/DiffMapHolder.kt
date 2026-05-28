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
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.ui.ext.dollarLast
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
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

    private val pendingSyncDiffs = mutableStateMapOf<String, Diff>()
    private val syncedDiffs = mutableMapOf<String, Diff>()
    private val pendingSyncMutex = Mutex()
    private val pendingReadStateMutex = Mutex()
    private val pendingReadStateQueueLock = Any()
    private var pendingReadStateTail: Job? = null

    val diffMapSnapshotFlow = snapshotFlow { diffMap.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    private val pendingSyncDiffsSnapshotFlow = snapshotFlow { pendingSyncDiffs.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    val shouldSyncWithRemote get() = currentAccount?.type?.id?.let { it != AccountType.Local.id } ?: false

    private val gson = Gson()

    private val cacheDir = context.cacheDir.resolve("diff")
    private var userCacheDir = cacheDir

    private var currentAccount: Account? = null

    private val cacheFile: File get() = userCacheDir.resolve("diff_map.json")

    var remoteJob: Job? = null

    private fun enqueuePendingReadStateWork(block: suspend () -> Unit): Job {
        val queuedJob =
            synchronized(pendingReadStateQueueLock) {
                val previous = pendingReadStateTail
                applicationScope.launch(ioDispatcher) {
                    previous?.join()
                    pendingReadStateMutex.withLock {
                        block()
                    }
                }.also { pendingReadStateTail = it }
            }
        queuedJob.invokeOnCompletion {
            synchronized(pendingReadStateQueueLock) {
                if (pendingReadStateTail === queuedJob) {
                    pendingReadStateTail = null
                }
            }
        }
        return queuedJob
    }

    private suspend fun awaitPendingReadStateWork() {
        synchronized(pendingReadStateQueueLock) { pendingReadStateTail }?.join()
    }

    private suspend fun mutateDiffMapOnMain(block: MutableMap<String, Diff>.() -> Unit) {
        withContext(mainDispatcher) {
            diffMap.block()
        }
    }

    private suspend fun removeAppliedDiffsFromUi(appliedDiffs: Map<String, Diff>) {
        mutateDiffMapOnMain {
            ReadStateDiffApplier.removeMatchingDiffs(
                currentDiffs = this,
                appliedDiffs = appliedDiffs,
            )
        }
    }

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
        account.id?.let { accountId ->
            replayPendingOpsToLocalDb(accountId)
        }
        commitDiffsFromCache()
        if (account.type.id != AccountType.Local.id) {
            syncOnChange()
        }
    }

    private suspend fun cleanup(@Suppress("UNUSED_PARAMETER") previousAccount: Account) {
        remoteJob?.cancel()
        awaitPendingReadStateWork()
        previousAccount.id?.let { accountId ->
            pendingReadStateMutex.withLock {
                flushLocalPendingOps(accountId)
            }
        }
        pendingReadStateMutex.withLock {
            commitDiffsToDb()
        }
        mutateDiffMapOnMain { clear() }
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

        Timber.tag("DiffMapHolder").d("updateDiff: deferDbCommits=$deferDbCommits, appliedDiffsCount=${appliedDiffs.size}")

        enqueuePendingReadStateWork {
            persistPendingReadStateOps(appliedDiffs)

            if (!deferDbCommits) {
                Timber.tag("DiffMapHolder").d("Immediately committing ${appliedDiffs.size} articles to DB")
                commitAppliedDiffsToDb(appliedDiffs.associateBy { it.articleId })
            } else {
                Timber.tag("DiffMapHolder").d("Deferring DB commits for ${appliedDiffs.size} articles")
            }
        }
    }

    /**
     * Flushes any deferred DB commits. Call this when exiting the Unread filter view,
     * starting a sync, or when the user navigates away from the flow page.
     */
    fun flushDeferredDiffs() {
        val accountId = currentAccount?.id ?: return
        enqueuePendingReadStateWork {
            flushLocalPendingOps(accountId)
        }
    }

    private suspend fun flushLocalPendingOps(accountId: Int) {
        val pendingOps = pendingReadStateOpDao.queryLocalPending(accountId)
        if (pendingOps.isEmpty()) {
            Timber.tag("DiffMapHolder").d("flushLocalPendingOps: nothing to flush")
            return
        }

        Timber.tag("DiffMapHolder").d("flushLocalPendingOps: flushing ${pendingOps.size} articles")

        val (readOps, unreadOps) = pendingOps.partition { it.isRead }
        val markAsReadIds = readOps.map { it.articleId }.toSet()
        val markAsUnreadIds = unreadOps.map { it.articleId }.toSet()

        if (markAsReadIds.isNotEmpty()) {
            rssService.get().batchMarkAsRead(articleIds = markAsReadIds, markRead = true)
            pendingReadStateOpDao.markLocalCommitted(markAsReadIds, isUnread = false)
        }
        if (markAsUnreadIds.isNotEmpty()) {
            rssService.get().batchMarkAsRead(articleIds = markAsUnreadIds, markRead = false)
            pendingReadStateOpDao.markLocalCommitted(markAsUnreadIds, isUnread = true)
        }

        removeAppliedDiffsFromUi(
            pendingOps.associate { it.articleId to Diff(it.isRead, it.articleId, it.feedId) }
        )

        pendingReadStateOpDao.deleteCompleted()
    }

    private fun appendDiffToSync(diff: Diff) {
        val syncedDiff = syncedDiffs[diff.articleId]
        if (syncedDiff == null || syncedDiff.isRead != diff.isRead) {
            pendingSyncDiffs[diff.articleId] = diff
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

        if (diffBatch.markReadIds.isNotEmpty()) {
            rssService.get().batchMarkAsRead(articleIds = diffBatch.markReadIds, markRead = true)
            pendingReadStateOpDao.markLocalCommitted(diffBatch.markReadIds, isUnread = false)
        }
        if (diffBatch.markUnreadIds.isNotEmpty()) {
            rssService.get().batchMarkAsRead(articleIds = diffBatch.markUnreadIds, markRead = false)
            pendingReadStateOpDao.markLocalCommitted(diffBatch.markUnreadIds, isUnread = true)
        }

        removeAppliedDiffsFromUi(diffsToCommit)

        pendingReadStateOpDao.deleteCompleted()
    }

    suspend fun prepareReadStateForSync(accountId: Int): Set<String> {
        if (currentAccount?.id != accountId) return emptySet()

        awaitPendingReadStateWork()
        pendingReadStateMutex.withLock {
            flushLocalPendingOps(accountId)
        }
        if (!shouldSyncWithRemote) return emptySet()

        return pendingSyncMutex.withLock {
            awaitPendingReadStateWork()
            val excludedReadStateIds =
                pendingReadStateMutex.withLock {
                    persistPendingReadStateOps(pendingSyncDiffs.values)
                    pendingReadStateOpDao.queryRemotePending(accountId)
                        .map { it.articleId.dollarLast() }
                        .toSet()
                }
            flushPendingReadStateQueue(accountId)
            excludedReadStateIds
        }
    }

    private suspend fun flushPendingSyncDiffs(diffs: Map<String, Diff>) {
        if (!shouldSyncWithRemote) return
        if (diffs.isEmpty()) return
        awaitPendingReadStateWork()
        val toBeSync = diffs
        val markAsReadArticles = toBeSync.filter { it.value.isRead }.map { it.key }.toSet()
        val markAsUnreadArticles = toBeSync.filter { !it.value.isRead }.map { it.key }.toSet()

        val synced = syncReadStateOps(markAsReadArticles, markAsUnreadArticles)

        val syncedDiffsSnapshot = diffs.filter { synced.contains(it.key) }
        ReadStateDiffApplier.removeMatchingDiffs(
            currentDiffs = pendingSyncDiffs,
            appliedDiffs = syncedDiffsSnapshot,
        )
        markRemoteSyncedOps(syncedDiffsSnapshot.values)
        syncedDiffs += syncedDiffsSnapshot
    }

    private fun commitDiffsFromCache() {
        enqueuePendingReadStateWork {
            if (cacheFile.exists() && cacheFile.canRead()) {
                val tmpJson = cacheFile.readText()
                val mapType = object : TypeToken<Map<String, Diff>>() {}.type
                val diffMapFromCache = gson.fromJson<Map<String, Diff>>(
                    tmpJson, mapType
                )
                diffMapFromCache?.let {
                    mutateDiffMapOnMain {
                        clear()
                        putAll(it)
                    }
                    persistPendingReadStateOps(it.values)
                }
                if (cacheFile.canWrite()) {
                    cacheFile.delete()
                }
            }
            commitDiffsToDb()
        }
    }

    private fun replayPendingOpsToLocalDb(accountId: Int) {
        enqueuePendingReadStateWork {
            val pendingOps = pendingReadStateOpDao.queryLocalPending(accountId)
            if (pendingOps.isEmpty()) return@enqueuePendingReadStateWork

            Timber.tag("DiffMapHolder").d("replayPendingOpsToLocalDb: replaying ${pendingOps.size} ops")

            val (readOps, unreadOps) = pendingOps.partition { it.isRead }
            val markAsReadIds = readOps.map { it.articleId }.toSet()
            val markAsUnreadIds = unreadOps.map { it.articleId }.toSet()

            if (markAsReadIds.isNotEmpty()) {
                rssService.get().batchMarkAsRead(articleIds = markAsReadIds, markRead = true)
                pendingReadStateOpDao.markLocalCommitted(markAsReadIds, isUnread = false)
            }
            if (markAsUnreadIds.isNotEmpty()) {
                rssService.get().batchMarkAsRead(articleIds = markAsUnreadIds, markRead = false)
                pendingReadStateOpDao.markLocalCommitted(markAsUnreadIds, isUnread = true)
            }

            pendingReadStateOpDao.deleteCompleted()
        }
    }

    private suspend fun flushPendingReadStateQueue(accountId: Int) {
        val queuedOps =
            pendingReadStateMutex.withLock {
                pendingReadStateOpDao.queryRemotePending(accountId)
            }
        if (queuedOps.isEmpty()) return

        val markAsReadArticles = queuedOps.filter { it.isRead }.map { it.articleId }.toSet()
        val markAsUnreadArticles = queuedOps.filter { !it.isRead }.map { it.articleId }.toSet()
        val synced = syncReadStateOps(markAsReadArticles, markAsUnreadArticles)
        if (synced.isEmpty()) return

        val syncedOps = queuedOps
            .filter { synced.contains(it.articleId) }
            .associate { it.articleId to Diff(it.isRead, it.articleId, it.feedId) }
        markRemoteSyncedOps(syncedOps.values)
        syncedDiffs += syncedOps
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
            val existingOps = pendingReadStateOpDao.queryByArticleIds(ops.map { it.articleId }.toSet())
                .associateBy { it.articleId }
            pendingReadStateOpDao.upsertAll(
                ops.map { op ->
                    val existing = existingOps[op.articleId]
                    if (existing != null && existing.isUnread == op.isUnread) {
                        op.copy(
                            localCommitted = existing.localCommitted,
                            remoteSynced = existing.remoteSynced,
                        )
                    } else {
                        op
                    }
                }
            )
        }
    }

    private suspend fun markRemoteSyncedOps(diffs: Collection<Diff>) {
        pendingReadStateMutex.withLock {
            val (readDiffs, unreadDiffs) = diffs.partition { it.isRead }
            val markReadIds = readDiffs.map { it.articleId }.toSet()
            val markUnreadIds = unreadDiffs.map { it.articleId }.toSet()
            if (markReadIds.isNotEmpty()) {
                pendingReadStateOpDao.markRemoteSynced(
                    articleIds = markReadIds,
                    isUnread = false,
                )
            }
            if (markUnreadIds.isNotEmpty()) {
                pendingReadStateOpDao.markRemoteSynced(
                    articleIds = markUnreadIds,
                    isUnread = true,
                )
            }
            pendingReadStateOpDao.deleteCompleted()
        }
    }

    private fun toPendingReadStateOp(diff: Diff): PendingReadStateOp? {
        val account = currentAccount ?: return null
        val accountId = account.id ?: return null
        return PendingReadStateOp(
            articleId = diff.articleId,
            accountId = accountId,
            feedId = diff.feedId,
            isUnread = !diff.isRead,
            localCommitted = false,
            remoteSynced = account.type.id == AccountType.Local.id,
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
