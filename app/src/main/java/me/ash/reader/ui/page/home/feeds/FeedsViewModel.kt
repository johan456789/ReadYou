package me.ash.reader.ui.page.home.feeds

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.FeedsGroupCollapsePreference
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import javax.inject.Inject

private const val TAG = "FeedsViewModel"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class FeedsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val workManager: WorkManager,
    private val androidStringsHelper: AndroidStringsHelper,
    @param:DefaultDispatcher
    private val defaultDispatcher: CoroutineDispatcher,
    @param:IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope
    private val applicationScope: CoroutineScope,
    private val settingsProvider: SettingsProvider,
    private val diffMapHolder: DiffMapHolder,
    private val filterStateUseCase: FilterStateUseCase,
    private val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
) : ViewModel() {

    private val collapseKey = stringPreferencesKey(PreferencesKey.feedsGroupCollapseState)
    private val gson = Gson()
    private val collapseType = object : TypeToken<Map<String, Boolean>>() {}.type

    private suspend fun readPersistedCollapseMap(): Map<String, Boolean> {
        return try {
            val prefs = context.dataStore.data.first()
            FeedsGroupCollapsePreference.fromPreferences(prefs)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun getDefaultExpand(): Boolean {
        return try {
            val v = context.dataStore.data.map { it[booleanPreferencesKey(PreferencesKey.feedsGroupListExpand)] }.first()
            v ?: true
        } catch (_: Exception) {
            true
        }
    }

    private val _feedsUiState =
        MutableStateFlow(FeedsUiState())
    val feedsUiState: StateFlow<FeedsUiState> = _feedsUiState.asStateFlow()

    // Preload persisted collapse state synchronously to avoid flicker (expanded -> collapsed)
    // Filter to current account if known; otherwise load all and prune after account resolves
    private val initialPersisted: Map<String, Boolean> = runBlocking {
        try {
            val prefs = context.dataStore.data.first()
            FeedsGroupCollapsePreference.fromPreferences(prefs)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    val syncWorkLiveData = workManager.getWorkInfosByTagLiveData(SyncWorker.SYNC_TAG)

    val filterStateFlow = filterStateUseCase.filterStateFlow
    val groupWithFeedsListFlow = groupWithFeedsListUseCase.groupWithFeedListFlow

    var currentJob: Job? = null

    fun sync() {
        applicationScope.launch(ioDispatcher) {
            rssService.get().doSyncOneTime()
        }
    }

    fun commitDiffs() {
        applicationScope.launch(ioDispatcher) {
            diffMapHolder.commitDiffsToDb()
        }
    }

    fun changeFilter(filterState: FilterState) {
        filterStateUseCase.updateFilterState(filterState)
    }

    init {
        // Pre-populate to avoid flicker, but only for current account to avoid cross-account bleed
        if (initialPersisted.isNotEmpty()) {
            val accountId = try {
                accountService.getCurrentAccountId()
            } catch (_: Exception) {
                null
            }
            val toPut = if (accountId != null) {
                val prefix = "${accountId}\$"
                initialPersisted.filterKeys { it.startsWith(prefix) }
            } else {
                initialPersisted
            }
            if (toPut.isNotEmpty()) _feedsUiState.value.groupsVisible.putAll(toPut)
        }
        val accountFlow = accountService.currentAccountFlow
        viewModelScope.launch {
            accountFlow.collect { account ->
                _feedsUiState.update { it.copy(account = account) }
            }
        }
        viewModelScope.launch {
            filterStateUseCase.filterStateFlow.mapLatest { it.filter }
                .combine(accountFlow) { filter, account ->
                    filter
                }
                .collect {
                    currentJob?.cancel()
                    currentJob = when (it) {
                        Filter.Unread -> pullUnreadFeeds()
                        Filter.Starred -> pullStarredFeeds()
                        else -> pullAllFeeds()
                    }
                }
        }

        // Persisted collapse state: init / prune / default handling
        // Pruning uses the displayed list; when filter hides groups (Unread+hideEmptyGroups)
        // we must not delete their persisted state.
        viewModelScope.launch {
            groupWithFeedsListFlow.collect { list ->
                val liveIds = list.map { it.group.id }.toSet()
                val persisted = readPersistedCollapseMap()
                val defaultExpand = getDefaultExpand()
                val groupsVisible = _feedsUiState.value.groupsVisible
                val currentFilter = try { filterStateFlow.value.filter } catch (_: Exception) { Filter.All }
                val canPrune = currentFilter == Filter.All || !settingsProvider.settings.hideEmptyGroups.value
                if (canPrune) {
                    val keysToRemove = groupsVisible.keys.filter { it !in liveIds }
                    keysToRemove.forEach { groupsVisible.remove(it) }
                }
                // Init new ids with persisted value or global default
                for (id in liveIds) {
                    if (!groupsVisible.containsKey(id)) {
                        val persistedValue = persisted[id]
                        groupsVisible[id] = persistedValue ?: defaultExpand
                    }
                }
            }
        }

        // Observe groupsVisible changes and debounce write to DataStore (merge per account)
        viewModelScope.launch {
            snapshotFlow { _feedsUiState.value.groupsVisible.toMap() }
                .debounce(300)
                .collect { currentMap ->
                    if (currentMap.isEmpty() && groupWithFeedsListFlow.value.isEmpty()) return@collect
                    if (currentMap.isEmpty()) {
                        val persisted = readPersistedCollapseMap()
                        if (persisted.isEmpty()) return@collect
                    }
                    val currentAccountId = _feedsUiState.value.account?.id
                    val filteredCurrent = if (currentAccountId != null) {
                        val prefix = "${currentAccountId}\$"
                        currentMap.filterKeys { it.startsWith(prefix) }
                    } else currentMap
                    context.dataStore.edit { prefs ->
                        val persisted = FeedsGroupCollapsePreference.fromPreferences(prefs)
                        if (filteredCurrent.isEmpty() && persisted.isEmpty()) return@edit
                        val merged = FeedsGroupCollapsePreference.mergedForWrite(persisted, currentAccountId, filteredCurrent)
                        if (merged != persisted) {
                            prefs[collapseKey] = FeedsGroupCollapsePreference.toJson(merged)
                        }
                    }
                }
        }
    }

    private fun pullAllFeeds(): Job {
        val articleCountMapFlow =
            rssService.get().pullImportant(isStarred = false, isUnread = false)

        return viewModelScope.launch {
            launch {
                articleCountMapFlow.mapLatest {
                    val sum = it.values.sum()
                    androidStringsHelper.getQuantityString(R.plurals.all_desc, sum, sum)
                }.flowOn(defaultDispatcher).collect { text ->
                    _feedsUiState.update { it.copy(importantSum = text) }
                }
            }
        }
    }

    private fun pullStarredFeeds(): Job {
        val starredCountMap = rssService.get().pullImportant(isStarred = true, isUnread = false)

        return viewModelScope.launch {
            starredCountMap.mapLatest {
                val sum = it.values.sum()
                androidStringsHelper.getQuantityString(R.plurals.starred_desc, sum, sum)
            }.flowOn(defaultDispatcher).collect { text ->
                _feedsUiState.update { it.copy(importantSum = text) }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun pullUnreadFeeds(): Job {
        val unreadCountMapFlow = rssService.get().pullImportant(isStarred = false, isUnread = true)

        return viewModelScope.launch {
            diffMapHolder.diffMapSnapshotFlow
                .combine(
                    unreadCountMapFlow
                ) { diffMap, unreadCountMap ->
                    val sum = unreadCountMap.values.sum()
                    val combinedSum =
                        sum + diffMap.values.sumOf { if (it.isRead) -1 else 1.toInt() } // KT-46360
                    androidStringsHelper.getQuantityString(
                        R.plurals.unread_desc,
                        combinedSum,
                        combinedSum
                    )
                }.debounce(200L).flowOn(defaultDispatcher).collect { text ->
                    _feedsUiState.update { it.copy(importantSum = text) }
                }
        }
    }

//    @OptIn(ExperimentalCoroutinesApi::class)
//    fun pullFeeds(filterState: FilterState, hideEmptyGroups: Boolean) {
//        val isStarred = filterState.filter.isStarred()
//        val isUnread = filterState.filter.isUnread()
//        _feedsUiState.update {
//            val important = rssService.get().pullImportant(isStarred, isUnread)
//            it.copy(
////                importantSum = important
////                    .mapLatest {
////                        (it["sum"] ?: 0).run {
////                            androidStringsHelper.getQuantityString(
////                                when {
////                                    isStarred -> R.plurals.starred_desc
////                                    isUnread -> R.plurals.unread_desc
////                                    else -> R.plurals.all_desc
////                                },
////                                this,
////                                this
////                            )
////                        }
////                    }.flowOn(defaultDispatcher),
//                groupWithFeedList = combine(
//                    important,
//                    rssService.get().pullFeeds()
//                ) { importantMap, groupWithFeedList ->
//                    val groupIterator = groupWithFeedList.iterator()
//                    while (groupIterator.hasNext()) {
//                        val groupWithFeed = groupIterator.next()
//                        val groupImportant = importantMap[groupWithFeed.group.id] ?: 0
//                        if (hideEmptyGroups && (isStarred || isUnread) && groupImportant == 0) {
//                            groupIterator.remove()
//                            continue
//                        }
//                        groupWithFeed.group.important = groupImportant
//                        val feedIterator = groupWithFeed.feeds.iterator()
//                        while (feedIterator.hasNext()) {
//                            val feed = feedIterator.next()
//                            val feedImportant = importantMap[feed.id] ?: 0
//                            groupWithFeed.group.feeds++
//                            if (hideEmptyGroups && (isStarred || isUnread) && feedImportant == 0) {
//                                feedIterator.remove()
//                                continue
//                            }
//                            feed.important = feedImportant
//                        }
//                    }
//                    groupWithFeedList
//                }.mapLatest { list ->
//                    list.filter { (group, feeds) ->
//                        group.id != feedsUiState.value.account?.id?.getDefaultGroupId() || feeds.isNotEmpty()
//                    }
//                }.flowOn(defaultDispatcher),
//            )
//        }
//    }
}

data class FeedsUiState(
    val account: Account? = null,
    val importantSum: String = "",
    val listState: LazyListState = LazyListState(),
    val groupsVisible: SnapshotStateMap<String, Boolean> = mutableStateMapOf(),
)
