package me.ash.reader.ui.page.home.foryou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import javax.inject.Inject

data class ForYouUiState(
    val articles: List<ArticleWithFeed> = emptyList(),
    val includeRead: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForYouViewModel @Inject constructor(
    val rssService: RssService,
    val diffMapHolder: DiffMapHolder,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val includeReadFlow = MutableStateFlow(false)

    private val _forYouUiState = MutableStateFlow(ForYouUiState())
    val forYouUiState: StateFlow<ForYouUiState> = _forYouUiState.asStateFlow()

    init {
        viewModelScope.launch {
            includeReadFlow.flatMapLatest { includeRead ->
                rssService.get().pullForYouArticles(includeRead)
            }.collect { articles ->
                _forYouUiState.update {
                    it.copy(articles = articles, includeRead = includeReadFlow.value)
                }
            }
        }
    }

    fun setIncludeRead(value: Boolean) {
        includeReadFlow.value = value
    }

    fun setDeferDbCommits(defer: Boolean) {
        diffMapHolder.deferDbCommits = defer
    }

    fun flushDeferredDiffs() {
        diffMapHolder.flushDeferredDiffs()
    }

    fun onToggleStarred(articleWithFeed: ArticleWithFeed) {
        applicationScope.launch(ioDispatcher) {
            rssService.get().markAsStarred(
                articleId = articleWithFeed.article.id,
                isStarred = !articleWithFeed.article.isStarred,
            )
        }
    }

    fun onToggleRead(articleWithFeed: ArticleWithFeed) {
        diffMapHolder.updateDiff(articleWithFeed)
    }
}
