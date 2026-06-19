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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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

    fun updateStarredStatus(articleId: String?, isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            if (articleId != null) {
                rssService.get().markAsStarred(articleId = articleId, isStarred = isStarred)
            }
        }
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

    fun initData(articleId: String, listIndex: Int? = null) {
        viewModelScope.launch {
            val snapshotList = articleListUseCase.itemSnapshotList

            val itemByIndex =
                listIndex?.let { snapshotList.getOrNull(it) as? ArticleFlowItem.Article }

            val itemFromList =
                if (itemByIndex != null && itemByIndex.articleWithFeed.article.id != articleId) {
                    itemByIndex
                } else {
                    snapshotList.find { item ->
                        item is ArticleFlowItem.Article &&
                            item.articleWithFeed.article.id == articleId
                    } as? ArticleFlowItem.Article
                }

            val item =
                itemByIndex?.articleWithFeed
                    ?: (itemFromList?.articleWithFeed
                        ?: rssService.get().findArticleById(articleId)!!)

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
                        .prefetchArticleId()
                        .copy(previousPage = null, nextPage = null)
                        .renderContent(this)
                }
            }
            loadAdjacentPages(item.article.id)
        }
    }

    fun clearReadingData() {
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val contentState =
            if (articleWithFeed.feed.isFullContent) {
                val fullContent =
                    readerCacheHelper.readFullContent(articleWithFeed.article.id).getOrNull()
                if (fullContent != null) ReaderState.FullContent(fullContent)
                else {
                    renderFullContent()
                    ReaderState.Loading
                }
            } else ReaderState.Description(articleWithFeed.article.rawDescription)

        return copy(content = contentState)
    }

    fun renderDescriptionContent() {
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
            )
        }
    }

    fun renderFullContent() {
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

    fun updateReadStatus(markRead: Boolean) {
        val articleWithFeed = readingUiState.value.articleWithFeed ?: return
        diffMapHolder.updateDiff(articleWithFeed, markRead = markRead)

        _readingUiState.update { state ->
            if (state.articleWithFeed?.article?.id != articleWithFeed.article.id) return@update state
            state.withReadState(diffMapHolder.checkIfRead(articleWithFeed))
        }
    }

    fun updateStarredStatus(isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            _readingUiState.update { it.copy(isStarred = isStarred) }
            currentArticle?.let {
                rssService.get().markAsStarred(articleId = it.id, isStarred = isStarred)
            }
        }
    }

    private fun setLoading() {
        _readerState.update { it.copy(content = ReaderState.Loading) }
    }

    fun ReaderState.prefetchArticleId(): ReaderState {
        val items = articleListUseCase.itemSnapshotList
        val currentId = currentArticle?.id
        val index =
            items.indexOfFirst { item ->
                item is ArticleFlowItem.Article && item.articleWithFeed.article.id == currentId
            }
        var previousArticle: ReaderState.PrefetchResult? = null
        var nextArticle: ReaderState.PrefetchResult? = null

        if (index != -1 || currentId == null) {
            val prevIterator = items.listIterator(index)
            while (prevIterator.hasPrevious()) {
                val previousIndex = prevIterator.previousIndex()
                val prev = prevIterator.previous()
                if (prev is ArticleFlowItem.Article) {
                    previousArticle =
                        ReaderState.PrefetchResult(
                            articleId = prev.articleWithFeed.article.id,
                            index = previousIndex,
                        )
                    break
                }
            }
            val nextIterator = items.listIterator(index + 1)
            while (nextIterator.hasNext()) {
                val nextIndex = nextIterator.nextIndex()
                val next = nextIterator.next()
                if (
                    next is ArticleFlowItem.Article && next.articleWithFeed.article.id != currentId
                ) {
                    nextArticle =
                        ReaderState.PrefetchResult(
                            articleId = next.articleWithFeed.article.id,
                            index = nextIndex,
                        )
                    break
                }
            }
        }

        Timber.d("$previousArticle, $nextArticle, $listIndex")
        return copy(nextArticle = nextArticle, previousArticle = previousArticle, listIndex = index)
    }

    private fun loadAdjacentPages(currentArticleId: String) {
        viewModelScope.launch {
            val items = articleListUseCase.itemSnapshotList.items
            val currentIndex =
                items.indexOfFirst { item ->
                    item is ArticleFlowItem.Article &&
                        item.articleWithFeed.article.id == currentArticleId
                }
            if (currentIndex == -1) return@launch

            val previous = items.findPreviousArticle(currentIndex)
            val next = items.findNextArticle(currentIndex, currentArticleId)
            val previousPage = previous?.let { buildReaderPage(it.articleWithFeed, it.index) }
            val nextPage = next?.let { buildReaderPage(it.articleWithFeed, it.index) }

            _readerState.update { state ->
                if (state.articleId != currentArticleId) state
                else state.copy(previousPage = previousPage, nextPage = nextPage)
            }

            previousPage?.let { page ->
                if (page.content is ReaderState.Loading) {
                    loadAdjacentFullContent(currentArticleId, AdjacentPageSlot.Previous, page)
                }
            }
            nextPage?.let { page ->
                if (page.content is ReaderState.Loading) {
                    loadAdjacentFullContent(currentArticleId, AdjacentPageSlot.Next, page)
                }
            }
        }
    }

    private suspend fun buildReaderPage(
        articleWithFeed: ArticleWithFeed,
        listIndex: Int,
    ): ReaderPage {
        val contentState =
            if (articleWithFeed.feed.isFullContent) {
                readerCacheHelper
                    .readFullContent(articleWithFeed.article.id)
                    .getOrNull()
                    ?.let { ReaderState.FullContent(it) }
                    ?: ReaderState.Loading
            } else {
                ReaderState.Description(articleWithFeed.article.rawDescription)
            }

        return articleWithFeed.toReaderPage(listIndex = listIndex, content = contentState)
    }

    private fun loadAdjacentFullContent(
        currentArticleId: String,
        slot: AdjacentPageSlot,
        page: ReaderPage,
    ) {
        viewModelScope.launch {
            readerCacheHelper
                .readOrFetchFullContent(page.article)
                .onSuccess { content ->
                    updateAdjacentPageContent(
                        currentArticleId = currentArticleId,
                        slot = slot,
                        pageArticleId = page.articleId,
                        content = ReaderState.FullContent(content),
                    )
                }
                .onFailure { th ->
                    updateAdjacentPageContent(
                        currentArticleId = currentArticleId,
                        slot = slot,
                        pageArticleId = page.articleId,
                        content = ReaderState.Error(th.message.toString()),
                    )
                }
        }
    }

    private fun updateAdjacentPageContent(
        currentArticleId: String,
        slot: AdjacentPageSlot,
        pageArticleId: String,
        content: ReaderState.ContentState,
    ) {
        _readerState.update { state ->
            if (state.articleId != currentArticleId) return@update state
            when (slot) {
                AdjacentPageSlot.Previous ->
                    if (state.previousPage?.articleId == pageArticleId) {
                        state.copy(previousPage = state.previousPage.copy(content = content))
                    } else state

                AdjacentPageSlot.Next ->
                    if (state.nextPage?.articleId == pageArticleId) {
                        state.copy(nextPage = state.nextPage.copy(content = content))
                    } else state
            }
        }
    }

    private fun List<ArticleFlowItem>.findPreviousArticle(
        currentIndex: Int,
    ): IndexedArticleWithFeed? {
        val iterator = listIterator(currentIndex)
        while (iterator.hasPrevious()) {
            val previousIndex = iterator.previousIndex()
            val previous = iterator.previous()
            if (previous is ArticleFlowItem.Article) {
                return IndexedArticleWithFeed(previousIndex, previous.articleWithFeed)
            }
        }
        return null
    }

    private fun List<ArticleFlowItem>.findNextArticle(
        currentIndex: Int,
        currentArticleId: String,
    ): IndexedArticleWithFeed? {
        val iterator = listIterator(currentIndex + 1)
        while (iterator.hasNext()) {
            val nextIndex = iterator.nextIndex()
            val next = iterator.next()
            if (
                next is ArticleFlowItem.Article &&
                    next.articleWithFeed.article.id != currentArticleId
            ) {
                return IndexedArticleWithFeed(nextIndex, next.articleWithFeed)
            }
        }
        return null
    }

    private fun ArticleWithFeed.toReaderPage(
        listIndex: Int,
        content: ReaderState.ContentState,
    ): ReaderPage =
        ReaderPage(
            article = article,
            articleId = article.id,
            feedName = feed.name,
            title = article.title,
            author = article.author,
            link = article.link,
            publishedDate = article.date,
            content = content,
            listIndex = listIndex,
        )

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

private enum class AdjacentPageSlot {
    Previous,
    Next,
}

private data class IndexedArticleWithFeed(
    val index: Int,
    val articleWithFeed: ArticleWithFeed,
)

data class ReaderPage(
    val article: Article,
    val articleId: String,
    val feedName: String,
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ReaderState.ContentState = ReaderState.Loading,
    val listIndex: Int? = null,
)

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    val listIndex: Int? = null,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
    val previousPage: ReaderPage? = null,
    val nextPage: ReaderPage? = null,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

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
