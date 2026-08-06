package me.ash.reader.domain.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.get
import me.ash.reader.ui.ext.put
import javax.inject.Singleton

@Singleton
class FilterStateUseCase
@Inject
constructor(
    settingsProvider: SettingsProvider,
    private val feedDao: FeedDao,
    private val groupDao: GroupDao,
    @param:ApplicationScope private val coroutineScope: CoroutineScope,
    @param:ApplicationContext private val context: Context,
) {

    private val _filterUiState =
        MutableStateFlow(FilterState(filter = settingsProvider.settings.initialFilter.toFilter()))
    val filterStateFlow = _filterUiState.asStateFlow()
    private val filterState
        get() = filterStateFlow.value

    private var persistScopeJob: Job? = null

    fun updateFilterState(
        feed: Feed? = filterState.feed,
        group: Group? = filterState.group,
        filter: Filter = filterState.filter,
        searchContent: String? = filterState.searchContent,
    ) {
        _filterUiState.update {
            it.copy(feed = feed, group = group, searchContent = searchContent, filter = filter)
        }
        persistScope()
    }

    fun updateFilterState(filterState: FilterState) {
        _filterUiState.update { filterState }
        persistScope()
    }

    fun init(feedId: String?, groupId: String?) {
        coroutineScope.launch {
            val feed = feedId?.let { feedDao.queryById(it) }
            val group = groupId?.let { groupDao.queryById(it) }
            updateFilterState(feed = feed, group = group, filter = Filter.Unread)
        }
    }

    /**
     * Snapshot the browsing scope (feed/group/status) so it can be restored after a process
     * death. Writes are debounced since the scope changes on every filter interaction.
     */
    private fun persistScope() {
        persistScopeJob?.cancel()
        persistScopeJob =
            coroutineScope.launch {
                delay(300L)
                val state = filterState
                state.feed?.id?.let { context.dataStore.put(PreferencesKey.currentFilterFeedId, it) }
                state.group?.id?.let {
                    context.dataStore.put(PreferencesKey.currentFilterGroupId, it)
                }
                context.dataStore.put(PreferencesKey.currentFilterStatus, state.filter.index)
            }
    }

    /**
     * Restore the persisted browsing scope. Re-queries the feed/group by id (deletions resolve
     * to null and are safely ignored). Called only when resuming an in-progress read after a
     * process death.
     */
    fun restorePersistedScope() {
        coroutineScope.launch {
            val feedId = context.dataStore.get<String>(PreferencesKey.currentFilterFeedId)
            val groupId = context.dataStore.get<String>(PreferencesKey.currentFilterGroupId)
            val status = context.dataStore.get<Int>(PreferencesKey.currentFilterStatus)
            val feed = feedId?.let { feedDao.queryById(it) }
            val group = groupId?.let { groupDao.queryById(it) }
            updateFilterState(
                feed = feed,
                group = group,
                filter = status?.let { Filter.values.getOrNull(it) } ?: filterState.filter,
            )
        }
    }

    /** The id of the article currently open in the reading pane, if any. */
    fun currentArticleId(): String? =
        context.dataStore.get(PreferencesKey.currentReadingArticleId)

    fun setCurrentArticle(articleId: String?) {
        coroutineScope.launch {
            val typedKey =
                PreferencesKey.keys[PreferencesKey.currentReadingArticleId]
                    as? PreferencesKey.StringKey
                    ?: return@launch
            context.dataStore.edit { preferences ->
                if (articleId == null) {
                    preferences.remove(typedKey.key)
                } else {
                    preferences[typedKey.key] = articleId
                }
            }
        }
    }
}

data class FilterState(
    val group: Group? = null,
    val feed: Feed? = null,
    val filter: Filter = Filter.All,
    val searchContent: String? = null,
)
