package me.ash.reader.domain.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val currentFilterFeedKey =
        stringKey(PreferencesKey.currentFilterFeedId)
    private val currentFilterGroupKey =
        stringKey(PreferencesKey.currentFilterGroupId)
    private val currentFilterStatusKey =
        intKey(PreferencesKey.currentFilterStatus)
    private val currentReadingArticleKey =
        stringKey(PreferencesKey.currentReadingArticleId)

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
     * death. Writes are debounced since the scope changes on every filter interaction, and
     * feed/group keys are cleared when the scope reverts to "all" so no stale scope survives.
     */
    private fun persistScope() {
        persistScopeJob?.cancel()
        persistScopeJob =
            coroutineScope.launch {
                delay(300L)
                val state = filterState
                context.dataStore.edit { preferences ->
                    if (state.feed != null) {
                        preferences[currentFilterFeedKey] = state.feed.id
                    } else {
                        preferences.remove(currentFilterFeedKey)
                    }
                    if (state.group != null) {
                        preferences[currentFilterGroupKey] = state.group.id
                    } else {
                        preferences.remove(currentFilterGroupKey)
                    }
                    preferences[currentFilterStatusKey] = state.filter.index
                }
            }
    }

    /**
     * Restore the persisted browsing scope. Re-queries the feed/group by id (deletions resolve
     * to null and are safely ignored). Called only when resuming an in-progress read after a
     * process death.
     */
    fun restorePersistedScope() {
        coroutineScope.launch {
            val feedId = readString(currentFilterFeedKey)
            val groupId = readString(currentFilterGroupKey)
            val status = readInt(currentFilterStatusKey)
            val feed = feedId?.let { feedDao.queryById(it) }
            val group = groupId?.let { groupDao.queryById(it) }
            _filterUiState.update {
                it.copy(
                    feed = feed,
                    group = group,
                    filter = status?.let { Filter.values.getOrNull(it) } ?: it.filter,
                )
            }
        }
    }

    /** The id of the article currently open in the reading pane, if any. */
    suspend fun currentArticleId(): String? = readString(currentReadingArticleKey)

    /** Durably records the article currently open in the reading pane (or clears it). */
    suspend fun setCurrentArticle(articleId: String?) {
        context.dataStore.edit { preferences ->
            if (articleId == null) {
                preferences.remove(currentReadingArticleKey)
            } else {
                preferences[currentReadingArticleKey] = articleId
            }
        }
    }

    private suspend fun readString(key: Preferences.Key<String>): String? =
        context.dataStore.data.first()[key]

    private suspend fun readInt(key: Preferences.Key<Int>): Int? =
        context.dataStore.data.first()[key]

    private fun stringKey(name: String): Preferences.Key<String> =
        (PreferencesKey.keys[name] as? PreferencesKey.StringKey)?.key ?: error("Missing key $name")

    private fun intKey(name: String): Preferences.Key<Int> =
        (PreferencesKey.keys[name] as? PreferencesKey.IntKey)?.key ?: error("Missing key $name")
}

data class FilterState(
    val group: Group? = null,
    val feed: Feed? = null,
    val filter: Filter = Filter.All,
    val searchContent: String? = null,
)
