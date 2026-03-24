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
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val pendingReadStateOpDao: PendingReadStateOpDao,
) {
    val diffMap = mutableStateMapOf<String, Diff>()

    private val pendingSyncDiffs = mutableStateMapOf<String, Diff>()
    private val syncedDiffs = mutableMapOf<String, Diff>()
    private val pendingSyncMutex = Mutex()

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
        commitDiffsFromCache()
        if (account.type != AccountType.Local) {
            syncOnChange()
        }
    }

    private fun cleanup(@Suppress("UNUSED_PARAMETER") previousAccount: Account) {
        remoteJob?.cancel()
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

    fun checkIfUnread(articleWithFeed: ArticleWithFeed): Boolean {
        return diffMap[articleWithFeed.article.id]?.isUnread ?: articleWithFeed.article.isUnread
    }

    /**
     * Updates the diff map with changes to an article's read/unread status.
     *
     * This function manages a map (`diffMap`) that tracks pending changes (diffs) to the
     * read/unread status of articles. These changes are not immediately applied to the
     * underlying data store but are held in `diffMap` until a later commit operation.
     *
     * The function supports three modes of updating:
     *
     * 1. **Toggle:** If `isUnread` is `null`, the function toggles the current read/unread
     *    status of the article.  If the article is currently unread, it will be marked as read,
     *    and vice-versa.
     * 2. **Mark as Unread:** If `isUnread` is `true`, the article will be marked as unread,
     *    regardless of its current status.
     * 3. **Mark as Read:** If `isUnread` is `false`, the article will be marked as read,
     *    regardless of its current status.
     *
     * The function determines if a change needs to be tracked based on the current status and desired status:
     *  - If the requested change matches the article's current status, the diff is removed from the map, if it exists. (No change is needed.)
     *  - Otherwise, the diff is added to or updated in the map.
     *
     * @param articleWithFeed The article and its associated feed data. This is used to identify the article
     *                        and access its current read/unread state.
     * @param isUnread An optional boolean indicating the desired read/unread status of the article.
     *                 - `null`: Toggles the current read/unread status.
     *                 - `true`: Marks the article as unread.
     *                 - `false`: Marks the article as read.
     *
     * @return A [Diff] object representing the changes made to the article.
     *
     * @see Diff
     */
    private fun updateDiffInternal(
        articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id

        val diff = diffMap[articleId]

        if (diff == null) {
            val isUnread = isUnread ?: !articleWithFeed.article.isUnread
            if (isUnread == articleWithFeed.article.isUnread) {
                return null
            }
            val diff = Diff(
                isUnread = isUnread, articleWithFeed = articleWithFeed
            )
            diffMap[articleId] = diff
            return diff
        } else {
            if (isUnread == null || diff.isUnread != isUnread) {
                val diff = diffMap.remove(articleId)
                return diff?.copy(isUnread = !diff.isUnread)
            }
        }
        return null
    }

    fun updateDiff(
        vararg articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ) {
        val appliedDiffs = articleWithFeed.mapNotNull {
            updateDiffInternal(it, isUnread)
        }
        if (appliedDiffs.isEmpty()) return

        if (shouldSyncWithRemote) {
            appliedDiffs.forEach {
                appendDiffToSync(it)
            }
        }
        applicationScope.launch(ioDispatcher) {
            commitAppliedDiffsToDb(appliedDiffs.associateBy { it.articleId })
        }
    }

    private fun appendDiffToSync(diff: Diff) {
        val syncedDiff = syncedDiffs[diff.articleId]
        if (syncedDiff == null || syncedDiff.isUnread != diff.isUnread) {
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
        rssService.get().batchMarkAsRead(articleIds = diffBatch.markReadIds, isUnread = false)
        rssService.get().batchMarkAsRead(articleIds = diffBatch.markUnreadIds, isUnread = true)

        ReadStateDiffApplier.removeMatchingDiffs(
            currentDiffs = diffMap,
            appliedDiffs = diffsToCommit,
        )
        syncCacheWithCurrentDiffs()
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
        val markAsReadArticles =
            toBeSync.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles =
            toBeSync.filter { it.value.isUnread }.map { it.key }.toSet()

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
            }
            commitDiffsToDb()
        }
    }

    private suspend fun flushPendingReadStateQueue(accountId: Int) {
        val queuedOps = pendingReadStateOpDao.queryByAccountId(accountId)
        if (queuedOps.isEmpty()) return

        val markAsReadArticles = queuedOps.filter { !it.isUnread }.map { it.articleId }.toSet()
        val markAsUnreadArticles = queuedOps.filter { it.isUnread }.map { it.articleId }.toSet()
        val synced = syncReadStateOps(markAsReadArticles, markAsUnreadArticles)
        if (synced.isEmpty()) return

        pendingReadStateOpDao.deleteByArticleIds(synced)
        syncedDiffs += queuedOps
            .filter { synced.contains(it.articleId) }
            .associate { it.articleId to Diff(it.isUnread, it.articleId, it.feedId) }
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
                    isUnread = false
                )
            }
            val unread = async {
                rssService.syncReadStatus(
                    articleIds = markAsUnreadArticles,
                    isUnread = true
                )
            }
            runCatching { read.await() }.getOrElse { emptySet() } +
                runCatching { unread.await() }.getOrElse { emptySet() }
        }
    }

    private suspend fun persistPendingReadStateOps(diffs: Collection<Diff>) {
        if (!shouldSyncWithRemote || diffs.isEmpty()) return
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
            isUnread = diff.isUnread,
        )
    }

    private suspend fun syncCacheWithCurrentDiffs() {
        try {
            if (cacheFile.exists() && cacheFile.canWrite()) {
                cacheFile.delete()
            }
            if (diffMap.isNotEmpty()) {
                val tmpJson = gson.toJson(diffMap)
                userCacheDir.mkdirs()
                cacheFile.createNewFile()
                if (cacheFile.exists() && cacheFile.canWrite()) {
                    cacheFile.writeText(tmpJson)
                }
            }
        } catch (_: Exception) {
        }
    }

}

data class Diff(
    val isUnread: Boolean, val articleId: String, val feedId: String
) {
    constructor(isUnread: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isUnread = isUnread,
        articleId = articleWithFeed.article.id,
        feedId = articleWithFeed.feed.id,
    )
}
