package me.ash.reader.ui.ext

import android.content.Context
import timber.log.Timber
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

val Context.skipVersionNumber: String
    get() = this.dataStore.get(PreferencesKey.skipVersionNumber) ?: ""
val Context.isFirstLaunch: Boolean
    get() = this.dataStore.get(PreferencesKey.isFirstLaunch) ?: true

@Deprecated("Use AccountService to retrieve the current account")
val Context.currentAccountId: Int
    get() = this.dataStore.get(PreferencesKey.currentAccountId) ?: 1
@Deprecated("Use AccountService to retrieve the current account")
val Context.currentAccountType: Int
    get() = this.dataStore.get(PreferencesKey.currentAccountType) ?: 1

val Context.initialPage: Int
    get() = this.dataStore.get(PreferencesKey.initialPage) ?: 0
val Context.initialFilter: Int
    get() = this.dataStore.get(PreferencesKey.initialFilter) ?: 2

val Context.languages: Int
    get() = this.dataStore.get(PreferencesKey.languages) ?: 0

suspend fun DataStore<Preferences>.put(dataStoreKeys: String, value: Any) {
    val key = PreferencesKey.keys[dataStoreKeys]?.key ?: return
    this.edit {
        withContext(Dispatchers.IO) {
            when (value) {
                is Int -> {
                    it[key as Preferences.Key<Int>] = value
                }
                is Long -> {
                    it[key as Preferences.Key<Long>] = value
                }
                is String -> {
                    it[key as Preferences.Key<String>] = value
                }
                is Boolean -> {
                    it[key as Preferences.Key<Boolean>] = value
                }
                is Float -> {
                    it[key as Preferences.Key<Float>] = value
                }
                is Double -> {
                    it[key as Preferences.Key<Double>] = value
                }
                else -> {
                    throw IllegalArgumentException("Unsupported type")
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> DataStore<Preferences>.get(key: String): T? {
    return runBlocking {
        this@get.data
            .catch { exception ->
                if (exception is IOException) {
                    Timber.tag("RLog").e(exception, "Get data store error")
                    exception.printStackTrace()
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                PreferencesKey.keys[key]?.let { typedKey ->
                    preferences[typedKey.key as Preferences.Key<T>]
                }
            }
            .first()
    }
}

sealed interface PreferencesKey {
    val name: String
    val key: Preferences.Key<*>

    data class IntKey(
        override val name: String,
        override val key: Preferences.Key<Int> = intPreferencesKey(name),
    ) : PreferencesKey

    data class LongKey(
        override val name: String,
        override val key: Preferences.Key<Long> = longPreferencesKey(name),
    ) : PreferencesKey

    data class StringKey(
        override val name: String,
        override val key: Preferences.Key<String> = stringPreferencesKey(name),
    ) : PreferencesKey

    data class BooleanKey(
        override val name: String,
        override val key: Preferences.Key<Boolean> = booleanPreferencesKey(name),
    ) : PreferencesKey

    data class FloatKey(
        override val name: String,
        override val key: Preferences.Key<Float> = floatPreferencesKey(name),
    ) : PreferencesKey

    companion object {
        // Version
        const val isFirstLaunch = "isFirstLaunch"
        const val newVersionPublishDate = "newVersionPublishDate"
        const val newVersionLog = "newVersionLog"
        const val newVersionSizeString = "newVersionSizeString"
        const val newVersionDownloadUrl = "newVersionDownloadUrl"
        const val newVersionNumber = "newVersionNumber"
        const val skipVersionNumber = "skipVersionNumber"
        const val currentAccountId = "currentAccountId"
        const val currentAccountType = "currentAccountType"
        const val themeIndex = "themeIndex"
        const val customPrimaryColor = "customPrimaryColor"
        const val darkTheme = "darkTheme"
        const val amoledDarkTheme = "amoledDarkTheme"
        const val basicFonts = "basicFonts"

        // Feeds page
        const val feedsFilterBarStyle = "feedsFilterBarStyle"
        const val feedsFilterBarPadding = "feedsFilterBarPadding"
        const val feedsFilterBarTonalElevation = "feedsFilterBarTonalElevation"
        const val feedsTopBarTonalElevation = "feedsTopBarTonalElevation"
        const val feedsGroupListExpand = "feedsGroupListExpand"
        const val feedsGroupListTonalElevation = "feedsGroupListTonalElevation"

        // Flow page
        const val flowFilterBarStyle = "flowFilterBarStyle"
        const val flowFilterBarPadding = "flowFilterBarPadding"
        const val flowFilterBarTonalElevation = "flowFilterBarTonalElevation"
        const val flowTopBarTonalElevation = "flowTopBarTonalElevation"
        const val flowArticleListFeedIcon = "flowArticleListFeedIcon"
        const val flowArticleListFeedName = "flowArticleListFeedName"
        const val flowArticleListImage = "flowArticleListImage"
        const val flowArticleListDesc = "flowArticleListDescription"
        const val flowArticleListTime = "flowArticleListTime"
        const val flowArticleListDateStickyHeader = "flowArticleListDateStickyHeader"
        const val flowArticleListTonalElevation = "flowArticleListTonalElevation"
        const val flowArticleListReadIndicator = "flowArticleListReadStatusIndicator"
        const val flowSortUnreadArticles = "flowArticleListSortUnreadArticles"

        // Reading page
        const val readingBoldCharacters = "readingBoldCharacters"
        const val readingPageTonalElevation = "readingPageTonalElevation"
        const val readingTextFontSize = "readingTextFontSize"
        const val readingTextLineHeight = "readingTextLineHeight"
        const val readingTextLetterSpacing = "readingTextLetterSpacing"
        const val readingTextHorizontalPadding = "readingTextHorizontalPadding"
        const val readingTextBold = "readingTextBold"
        const val readingTextAlign = "readingTextAlign"
        const val readingTitleAlign = "readingTitleAlign"
        const val readingSubheadAlign = "readingSubheadAlign"
        const val readingTheme = "readingTheme"
        const val readingFonts = "readingFonts"
        const val readingAutoHideToolbar = "readingAutoHideToolbar"
        const val readingTitleBold = "readingTitleBold"
        const val readingSubheadBold = "readingSubheadBold"
        const val readingTitleUpperCase = "readingTitleUpperCase"
        const val readingSubheadUpperCase = "readingSubheadUpperCase"
        const val readingImageHorizontalPadding = "readingImageHorizontalPadding"
        const val readingImageRoundedCorners = "readingImageRoundedCorners"

        // Interaction
        const val initialPage = "initialPage"
        const val initialFilter = "initialFilter"
        const val swipeStartAction = "swipeStartAction"
        const val swipeEndAction = "swipeEndAction"
        const val markAsReadOnScroll = "markAsReadOnScroll"
        const val hideEmptyGroups = "hideEmptyGroups"
        const val pullToLoadNextFeed = "pullToLoadNextFeed"
        const val pullToSwitchArticle = "pullToSwitchArticle"
        const val openLink = "openLink"
        const val openLinkAppSpecificBrowser = "openLinkAppSpecificBrowser"
        const val sharedContent = "sharedContent"

        // Languages
        const val languages = "languages"

        private val keyList =
            listOf(
                // Version
                BooleanKey(isFirstLaunch),
                StringKey(newVersionPublishDate),
                StringKey(newVersionLog),
                StringKey(newVersionSizeString),
                StringKey(newVersionDownloadUrl),
                StringKey(newVersionNumber),
                StringKey(skipVersionNumber),
                IntKey(currentAccountId),
                IntKey(currentAccountType),
                IntKey(themeIndex),
                StringKey(customPrimaryColor),
                IntKey(darkTheme),
                BooleanKey(amoledDarkTheme),
                IntKey(basicFonts),
                // Feeds page
                IntKey(feedsFilterBarStyle),
                IntKey(feedsFilterBarPadding),
                IntKey(feedsFilterBarTonalElevation),
                IntKey(feedsTopBarTonalElevation),
                BooleanKey(feedsGroupListExpand),
                IntKey(feedsGroupListTonalElevation),
                // Flow page
                IntKey(flowFilterBarStyle),
                IntKey(flowFilterBarPadding),
                IntKey(flowFilterBarTonalElevation),
                IntKey(flowTopBarTonalElevation),
                BooleanKey(flowArticleListFeedIcon),
                BooleanKey(flowArticleListFeedName),
                BooleanKey(flowArticleListImage),
                BooleanKey(flowArticleListDesc),
                BooleanKey(flowArticleListTime),
                BooleanKey(flowArticleListDateStickyHeader),
                IntKey(flowArticleListTonalElevation),
                IntKey(flowArticleListReadIndicator),
                BooleanKey(flowSortUnreadArticles),
                // Reading page
                BooleanKey(readingBoldCharacters),
                IntKey(readingPageTonalElevation),
                IntKey(readingTextFontSize),
                FloatKey(readingTextLineHeight),
                FloatKey(readingTextLetterSpacing),
                IntKey(readingTextHorizontalPadding),
                BooleanKey(readingTextBold),
                IntKey(readingTextAlign),
                IntKey(readingTitleAlign),
                IntKey(readingSubheadAlign),
                IntKey(readingTheme),
                IntKey(readingFonts),
                BooleanKey(readingAutoHideToolbar),
                BooleanKey(readingTitleBold),
                BooleanKey(readingSubheadBold),
                BooleanKey(readingTitleUpperCase),
                BooleanKey(readingSubheadUpperCase),
                IntKey(readingImageHorizontalPadding),
                IntKey(readingImageRoundedCorners),
                // Interaction
                IntKey(initialPage),
                IntKey(initialFilter),
                IntKey(swipeStartAction),
                IntKey(swipeEndAction),
                BooleanKey(markAsReadOnScroll),
                BooleanKey(hideEmptyGroups),
                BooleanKey(pullToLoadNextFeed),
                BooleanKey(pullToSwitchArticle),
                IntKey(openLink),
                StringKey(openLinkAppSpecificBrowser),
                IntKey(sharedContent),
                // Languages
                IntKey(languages),
            )

        val keys = keyList.associateBy { it.name }
    }
}

val ignorePreferencesOnExportAndImport =
    listOf(
        PreferencesKey.currentAccountId,
        PreferencesKey.currentAccountType,
        PreferencesKey.isFirstLaunch,
    )

suspend fun Context.fromDataStoreToJSONString(): String {
    val preferences = dataStore.data.first()
    val map: Map<String, Any?> =
        preferences
            .asMap()
            .mapKeys { it.key.name }
            .filterKeys { it !in ignorePreferencesOnExportAndImport }
    return Gson().toJson(map)
}

suspend fun String.fromJSONStringToDataStore(context: Context) {
    val gson = Gson()
    val type = object : TypeToken<Map<String, *>>() {}.type
    val deserializedMap: Map<String, Any> = gson.fromJson(this, type)
    context.dataStore.edit { preferences ->
        deserializedMap
            .filterKeys { it !in ignorePreferencesOnExportAndImport }
            .forEach { (keyString, value) ->
                val preferencesKey = PreferencesKey.keys[keyString]
                when (preferencesKey) {
                    is PreferencesKey.BooleanKey -> {
                        if (value is Boolean) preferences[preferencesKey.key] = value
                    }
                    is PreferencesKey.FloatKey -> {
                        if (value is Number) preferences[preferencesKey.key] = value.toFloat()
                    }
                    is PreferencesKey.IntKey -> {
                        if (value is Number) preferences[preferencesKey.key] = value.toInt()
                    }
                    is PreferencesKey.LongKey -> {
                        if (value is Number) preferences[preferencesKey.key] = value.toLong()
                    }
                    is PreferencesKey.StringKey -> {
                        if (value is String) preferences[preferencesKey.key] = value
                    }
                    null -> return@forEach
                }
            }
    }
}
