package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.PreferencesKey.Companion.readingImageHorizontalPadding
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalReadingImageHorizontalPadding =
    compositionLocalOf { ReadingImageHorizontalPaddingPreference.default }

object ReadingImageHorizontalPaddingPreference {

    const val default = 24

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(PreferencesKey.readingImageHorizontalPadding, value)
        }
    }

    fun fromPreferences(preferences: Preferences) =
        preferences[PreferencesKey.keys[readingImageHorizontalPadding]?.key as Preferences.Key<Int>] ?: default
}
