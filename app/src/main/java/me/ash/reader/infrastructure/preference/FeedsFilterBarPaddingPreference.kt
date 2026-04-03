package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.PreferencesKey.Companion.feedsFilterBarPadding
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalFeedsFilterBarPadding =
    compositionLocalOf { FeedsFilterBarPaddingPreference.default }

object FeedsFilterBarPaddingPreference {

    const val default = 60

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(PreferencesKey.feedsFilterBarPadding, value)
        }
    }

    fun fromPreferences(preferences: Preferences) =
        preferences[PreferencesKey.keys[feedsFilterBarPadding]?.key as Preferences.Key<Int>] ?: default
}
