package me.ash.reader.ui.page.adaptive

import android.net.Uri
import androidx.compose.ui.util.fastFirstOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import javax.inject.Inject
import kotlin.collections.any
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import timber.log.Timber

private const val TAG = "FlowViewModel"

@OptIn(FlowPreview::class)
@HiltViewModel()
class ArticleListReaderViewModel
@Inject
constructor(
    private val rssService: RssService,
    @param:IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    val diffMapHolder: DiffMapHolder,
    private val filterStateUseCase: FilterStateUseCase,
    private val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
    private val settingsProvider: SettingsProvider,
    private val readerCacheHelper: ReaderCacheHelper,
    val textToSpeechManager: TextToSpeechManager,
    private val imageDownloader: AndroidImageDownloader,
    private val articleListUseCase: ArticlePagingListUseCase,
    workManager: WorkManager,
) : ViewModel() {

    val flowUiState: StateFlow<FlowUiState?> =
        articleListUseCase.pagerFlow
            .combine(groupWithFeedsListUseCase.groupWithFeedListFlow) {
                pagerData,
                groupWithFeedsList ->
                val filterState = pagerData.filterState
                var nextFilterState: FilterState? = null
                if (filterState.group != null) {
                    val groupList = groupWithFeedsList.map { it.group }
                    val index = groupList.indexOfFirst { it.id == filterState.group.id }
                    if (index != -1) {
                        val nextGroup = groupList.getOrNull(index + 1)
                        if (nextGroup != null) {
                            nextFilterState = filterState.copy(group = nextGroup)
                        }
                    } else {
                        val allGroupList =
                            rssService.get().queryAllGroupWithFeeds().map { it.group }
                        val index = allGroupList.indexOfFirst { it.id == filterState.group.id }
                        if (index != -1) {
                            val nextGroup =
                                allGroupList.subList(index, allGroupList.size).fastFirstOrNull {
                                    groupList.map { it.id }.contains(it.id)
                                }
                            if (nextGroup != null) {
                                nextFilterState = filterState.copy(group = nextGroup)
                            }
                        }
                    }
                } else if (filterState.feed != null) {
                    val feedList = groupWithFeedsList.flatMap { it.feeds }
                    val index = feedList.indexOfFirst { it.id == filterState.feed.id }
                    if (index != -1) {
                        val nextFeed = feedList.getOrNull(index + 1)
                        if (nextFeed != null) {
                            nextFilterState = filterState.copy(feed = nextFeed)
                        }
                    } else {
                        val allFeedList =
                            rssService.get().queryAllGroupWithFeeds().flatMap { it.feeds }
                        val index = allFeedList.indexOfFirst { it.id == filterState.feed.id }
                        if (index != -1) {
                            val nextFeed =
                                allFeedList.subList(index, allFeedList.size).fastFirstOrNull {
                                    feedList.map { it.id }.contains(it.id)
                                }
                            if (nextFeed != null) {
                                nextFilterState = filterState.copy(feed = nextFeed)
                            }
                        }
                    }
                }
                FlowUiState(nextFilterState = nextFilterState, pagerData = pagerData)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val syncWorkerStatusFlow =
        workManager
            .getWorkInfosByTagFlow(SyncWorker.SYNC_TAG)
            .map { it.any { workInfo -> workInfo.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow = _isSyncingFlow.asStateFlow()

    init {
        viewModelScope.launch {
            syncWorkerStatusFlow.debounce(500L).collect { _isSyncingFlow.value = it }
        }
    }

    fun updateReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        markRead: Boolean,
    ) {
        launchMarkReadStatus(
            applicationScope = applicationScope,
            ioDispatcher = ioDispatcher,
            markReadStatus = { markReadStatus(groupId, feedId, articleId, conditions, markRead) },
        )
    }

    fun markReadStatusInBackground(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        markRead: Boolean,
        onMarked: (Set<String>) -> Unit = {},
    ) {
        launchMarkReadStatus(
            applicationScope = applicationScope,
            ioDispatcher = ioDispatcher,
            markReadStatus = { markReadStatus(groupId, feedId, articleId, conditions, markRead) },
            onMarked = onMarked,
        )
    }

    suspend fun markReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        markRead: Boolean,
    ): Set<String> =
        kotlinx.coroutines.withContext(ioDispatcher) {
            rssService
                .get()
                .markAsRead(
                    groupId = groupId,
                    feedId = feedId,
                    articleId = articleId,
                    before = conditions.toDate(),
                    markRead = markRead,
                )
        }

    fun undoReadStatus(articleWithFeed: List<ArticleWithFeed>) {
        diffMapHolder.applyReadStateWithSync(articleWithFeed = articleWithFeed, markRead = false)
    }

    fun undoReadStatus(articleIds: Set<String>) {
        diffMapHolder.applyReadStateWithSync(articleIds = articleIds, markRead = false)
    }

    fun markAsReadFromListPosition(articleId: String, markAbove: Boolean): List<ArticleWithFeed> {
        val items = selectArticlesToMark(
            items = articleListUseCase.itemSnapshotList.items,
            targetArticleId = articleId,
            markAbove = markAbove,
            isRead = { diffMapHolder.checkIfRead(it) },
        )

        if (items.isNotEmpty()) {
            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), markRead = true)
        }
        return items
    }

    fun loadNextFeedOrGroup() {
        viewModelScope.launch {
            if (
                settingsProvider.settings.pullToSwitchFeed ==
                    PullToLoadNextFeedPreference.MarkAsReadAndLoadNextFeed
            ) {
                markAllAsRead()
            }
            flowUiState.value?.nextFilterState?.let { filterStateUseCase.updateFilterState(it) }
        }
    }

    fun markAllAsRead(): List<ArticleWithFeed> {
        val items =
            articleListUseCase.itemSnapshotList.items
                .filterIsInstance<ArticleFlowItem.Article>()
                .map { it.articleWithFeed }
                .filter { !diffMapHolder.checkIfRead(it) }
                .distinctBy { it.article.id }

        if (items.isNotEmpty()) {
            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), markRead = true)
        }
        return items
    }

    fun sync() {
        diffMapHolder.flushDeferredDiffs()
        viewModelScope.launch {
            _isSyncingFlow.value = true
            val isSyncing = syncWorkerStatusFlow.value
            if (!isSyncing) {
                delay(1000L)
                if (syncWorkerStatusFlow.value == false) {
                    _isSyncingFlow.value = false
                }
            }
        }
        applicationScope.launch(ioDispatcher) {
            val filterState = filterStateUseCase.filterStateFlow.value
            val service = rssService.get()
            when (service) {
                is LocalRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                is GoogleReaderRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                else -> service.doSyncOneTime()
            }
        }
    }

    fun resetFilter() =
        filterStateUseCase.updateFilterState(feed = null, group = null, searchContent = null)

    fun changeFilter(filterState: FilterState) {
        val currentFilter = filterStateUseCase.filterStateFlow.value.filter
        val newFilter = filterState.filter
        if (currentFilter.isUnread() && !newFilter.isUnread()) {
            diffMapHolder.flushDeferredDiffs()
        }
        filterStateUseCase.updateFilterState(
            filterState.feed,
            filterState.group,
            filterState.filter,
        )
    }

    /**
     * Enables deferred DB commits for read state changes.
     * This prevents articles from immediately disappearing in the Unread filter view.
     */
    fun setDeferDbCommits(defer: Boolean) {
        Timber.tag("ArticleListReaderVM").d("setDeferDbCommits($defer)")
        diffMapHolder.deferDbCommits = defer
    }

    /**
     * Flushes any pending deferred DB commits. Call this when leaving the flow page.
     */
    fun flushDeferredDiffs() {
        diffMapHolder.flushDeferredDiffs()
    }

    fun inputSearchContent(content: String? = null) {
        if (content != filterStateUseCase.filterStateFlow.value.searchContent)
            filterStateUseCase.updateFilterState(searchContent = content)
    }

    private val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    private val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    private val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article

    private val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    /**
     * Manual per-article FullContent choice ("Parse full content" toggle in [BottomBar]).
     * True = user asked for FullContent, False = user explicitly switched back to Description.
     * Needed because [readerCacheHelper] disk cache alone cannot distinguish "user switched
     * back to Description" (cache still exists) from "still wants FullContent", and
     * [Feed.isFullContent] alone ignores the manual toggle. Preserved across horizontal
     * swipe navigation so swiping back to an article still in the 3-slot
     * [ArticleSwipePager] quota reuses its FullContent page instead of resetting.
     */
    private val fullContentManualOverride = mutableMapOf<String, Boolean>()

    fun initData(articleId: String) {
        viewModelScope.launch {
            val snapshotList = articleListUseCase.itemSnapshotList

            val item =
                selectReadingListItem(items = snapshotList.items, articleId = articleId)
                    ?: rssService.get().findArticleById(articleId)
                    ?: return@launch

            if (!diffMapHolder.checkIfRead(item)) {
                diffMapHolder.updateDiff(item, markRead = true)
            }
            item.run {
                _readingUiState.update {
                    ReadingUiState(articleWithFeed = this, isStarred = article.isStarred)
                        .withReadState(isRead = true)
                }
                _readerState.update {
                    it.copy(
                            articleId = article.id,
                            feedName = feed.name,
                            title = article.title,
                            author = article.author,
                            link = article.link,
                            publishedDate = article.date,
                        )
                        .prefetchArticleId(article.id)
                        .renderContent(this)
                }
            }
        }
    }

    fun clearReadingData() {
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    /** Durably records the article currently open in the reading pane (for process-death resume). */
    suspend fun setCurrentArticle(articleId: String) {
        filterStateUseCase.setCurrentArticle(articleId)
    }

    /** Clears the recorded article when the reading pane is closed. */
    suspend fun clearCurrentArticle() {
        filterStateUseCase.setCurrentArticle(null)
    }

    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val articleId = articleWithFeed.article.id
        if (articleWithFeed.feed.isFullContent) {
            val fullContent = readerCacheHelper.readFullContent(articleId).getOrNull()
            val contentState =
                if (fullContent != null) ReaderState.FullContent(fullContent)
                else {
                    renderFullContent()
                    ReaderState.Loading
                }
            return copy(content = contentState)
        }

        // Feed is not auto-FullContent: preserve the manual "Parse full content" toggle
        // and disk cache across swipe navigation. An explicit switch back to Description
        // (override == false) wins over a stale cache entry.
        if (fullContentManualOverride[articleId] == false) {
            return copy(content = ReaderState.Description(articleWithFeed.article.rawDescription))
        }
        val cachedFullContent = readerCacheHelper.readFullContent(articleId).getOrNull()
        if (cachedFullContent != null) {
            return copy(content = ReaderState.FullContent(cachedFullContent))
        }
        if (fullContentManualOverride[articleId] == true) {
            renderFullContent()
            return copy(content = ReaderState.Loading)
        }
        return copy(content = ReaderState.Description(articleWithFeed.article.rawDescription))
    }

    suspend fun previewReaderState(articleId: String): ReaderState =
        withContext(ioDispatcher) {
            val snapshotList = articleListUseCase.itemSnapshotList
            val articleWithFeed =
                selectReadingListItem(items = snapshotList.items, articleId = articleId)
                    ?: rssService.get().findArticleById(articleId)
                    ?: return@withContext ReaderState(articleId = articleId)

            ReaderState(
                    articleId = articleWithFeed.article.id,
                    feedName = articleWithFeed.feed.name,
                    title = articleWithFeed.article.title,
                    author = articleWithFeed.article.author,
                    link = articleWithFeed.article.link,
                    publishedDate = articleWithFeed.article.date,
                )
                .prefetchArticleId(articleWithFeed.article.id)
                .copy(content = articleWithFeed.previewContent())
        }

    private suspend fun ArticleWithFeed.previewContent(): ReaderState.ContentState {
        // Neighbor prefetch for the 3-slot ArticleSwipePager: reuse the FullContent page
        // when the article is still loaded (manual toggle or disk cache), so swiping back
        // does not flash a Description. Never triggers a network fetch here.
        if (fullContentManualOverride[article.id] == false) {
            return ReaderState.Description(article.rawDescription)
        }
        readerCacheHelper.readFullContent(article.id).getOrNull()?.let {
            return ReaderState.FullContent(it)
        }
        return ReaderState.Description(article.rawDescription)
    }

    fun renderDescriptionContent() {
        currentArticle?.id?.let { fullContentManualOverride[it] = false }
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
            )
        }
    }

    fun renderFullContent() {
        currentArticle?.id?.let { fullContentManualOverride[it] = true }
        val fetchJob =
            viewModelScope.launch {
                readerCacheHelper
                    .readOrFetchFullContent(currentArticle!!)
                    .onSuccess { content ->
                        _readerState.update {
                            it.copy(content = ReaderState.FullContent(content = content))
                        }
                    }
                    .onFailure { th ->
                        _readerState.update {
                            it.copy(content = ReaderState.Error(th.message.toString()))
                        }
                    }
            }
        viewModelScope.launch {
            delay(100L)
            if (fetchJob.isActive) {
                setLoading()
            }
        }
    }

    /**
     * Resolves an article by id from the current list snapshot, falling back
     * to the database. Actions (read/star/share) resolve their target through
     * this so they never depend on which article the UI state happens to hold.
     */
    suspend fun readingArticle(articleId: String): ArticleWithFeed? =
        withContext(ioDispatcher) {
            selectReadingListItem(
                    items = articleListUseCase.itemSnapshotList.items,
                    articleId = articleId,
                ) ?: rssService.get().findArticleById(articleId)
        }

    fun updateReadStatus(articleId: String, markRead: Boolean) {
        // Fast path runs synchronously on the caller thread so a fast
        // navigate-away cannot cancel the tap before the diff is applied.
        // Snapshot access is main-safe; only the DB fallback suspends.
        selectReadingListItem(
                items = articleListUseCase.itemSnapshotList.items,
                articleId = articleId,
            )
            ?.let {
                applyReadStatus(articleId = articleId, articleWithFeed = it, markRead = markRead)
                return
            }
        viewModelScope.launch {
            rssService
                .get()
                .findArticleById(articleId)
                ?.let {
                    applyReadStatus(articleId = articleId, articleWithFeed = it, markRead = markRead)
                }
        }
    }

    private fun applyReadStatus(
        articleId: String,
        articleWithFeed: ArticleWithFeed,
        markRead: Boolean,
    ) {
        diffMapHolder.updateDiff(articleWithFeed, markRead = markRead)
        _readingUiState.update { state ->
            if (state.articleWithFeed?.article?.id != articleId) return@update state
            state.withReadState(diffMapHolder.checkIfRead(articleWithFeed))
        }
    }

    fun updateStarredStatus(articleId: String, isStarred: Boolean) {
        // Update the displayed flag synchronously so a concurrent initData
        // reload cannot clobber the tap before the DB write lands.
        _readingUiState.update { state ->
            if (state.articleWithFeed?.article?.id != articleId) return@update state
            state.copy(isStarred = isStarred)
        }
        applicationScope.launch(ioDispatcher) {
            rssService.get().markAsStarred(articleId = articleId, isStarred = isStarred)
        }
    }

    /**
     * Shares title+link resolved fresh from [articleId]. Lookup runs off-main,
     * but the share callback fires on the main thread since it touches UI.
     */
    fun shareArticle(articleId: String, doShare: (title: String?, link: String?) -> Unit) {
        applicationScope.launch {
            readingArticle(articleId)?.let {
                withContext(Dispatchers.Main.immediate) { doShare(it.article.title, it.article.link) }
            }
        }
    }

    private fun setLoading() {
        _readerState.update { it.copy(content = ReaderState.Loading) }
    }

    fun ReaderState.prefetchArticleId(
        articleId: String? = currentArticle?.id,
    ): ReaderState {
        val items = articleListUseCase.itemSnapshotList.items
        val (previousId, nextId) = adjacentArticleIds(items = items, articleId = articleId)
        val previousArticle = previousId?.let { ReaderState.PrefetchResult(articleId = it) }
        val nextArticle = nextId?.let { ReaderState.PrefetchResult(articleId = it) }

        Timber.d("$previousArticle, $nextArticle")
        return copy(nextArticle = nextArticle, previousArticle = previousArticle)
    }

    fun downloadImage(
        url: String,
        onSuccess: (Uri) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            imageDownloader.downloadImage(url).onSuccess(onSuccess).onFailure(onFailure)
        }
    }
}

data class FlowUiState(val pagerData: PagerData, val nextFilterState: FilterState? = null)

/**
 * Resolves the [ArticleWithFeed] to open in the reading pane from the current
 * article-list snapshot, keyed solely by article id. List positions shift
 * under the reader on sync inserts and read-state filtering, so a saved index
 * must never decide which article opens.
 */
internal fun selectReadingListItem(
    items: List<ArticleFlowItem>,
    articleId: String,
): ArticleWithFeed? =
    (items.find { item ->
            item is ArticleFlowItem.Article && item.articleWithFeed.article.id == articleId
        } as? ArticleFlowItem.Article)
        ?.articleWithFeed

/**
 * Previous/next article ids around [articleId] in snapshot order, skipping
 * non-article rows (date separators). Pure so the swipe/next-article targets
 * can be unit-tested; (null, null) when the id is unknown or null.
 */
internal data class AdjacentArticleIds(val previousId: String?, val nextId: String?)

internal fun adjacentArticleIds(
    items: List<ArticleFlowItem>,
    articleId: String?,
): AdjacentArticleIds {
    val ids =
        items
            .filterIsInstance<ArticleFlowItem.Article>()
            .map { it.articleWithFeed.article.id }
    val index = ids.indexOfFirst { it == articleId }
    if (index == -1) return AdjacentArticleIds(previousId = null, nextId = null)
    return AdjacentArticleIds(
        previousId = ids.getOrNull(index - 1),
        nextId = ids.getOrNull(index + 1)?.takeIf { it != articleId },
    )
}

internal fun selectArticlesToMark(
    items: Iterable<ArticleFlowItem>,
    targetArticleId: String,
    markAbove: Boolean,
    isRead: (ArticleWithFeed) -> Boolean = { it.article.isRead },
): List<ArticleWithFeed> {
    val articles = items.filterIsInstance<ArticleFlowItem.Article>().map { it.articleWithFeed }
    val targetIndex = articles.indexOfFirst { it.article.id == targetArticleId }
    if (targetIndex == -1) return emptyList()

    val relativeArticles =
        if (markAbove) {
            articles.subList(0, targetIndex)
        } else {
            articles.subList(targetIndex + 1, articles.size)
        }

    return relativeArticles.filter { !isRead(it) }.distinctBy { it.article.id }
}

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
) {
    fun withReadState(isRead: Boolean): ReadingUiState =
        copy(
            articleWithFeed =
                articleWithFeed?.copy(article = articleWithFeed.article.copy(isUnread = !isRead)),
            isRead = isRead,
        )
}

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
) {
    data class PrefetchResult(val articleId: String)

    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> message
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String) : ContentState

    data class Description(val content: String) : ContentState

    data class Error(val message: String) : ContentState

    data object Loading : ContentState
}
