package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.PreferencesKey.Companion.readingImageMaximize
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalReadingImageMaximize =
    compositionLocalOf<ReadingImageMaximizePreference> { ReadingImageMaximizePreference.default }

sealed class ReadingImageMaximizePreference(val value: Boolean) : Preference() {
    object ON : ReadingImageMaximizePreference(true)
    object OFF : ReadingImageMaximizePreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(PreferencesKey.readingImageMaximize, value)
        }
    }

    companion object {

        val default = ON
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[PreferencesKey.keys[readingImageMaximize]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}

operator fun ReadingImageMaximizePreference.not(): ReadingImageMaximizePreference =
    when (value) {
        true -> ReadingImageMaximizePreference.OFF
        false -> ReadingImageMaximizePreference.ON
    }
