package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.PreferencesKey.Companion.articleSwitchGesture
import me.ash.reader.ui.ext.PreferencesKey.Companion.pullToSwitchArticle
import me.ash.reader.ui.ext.PreferencesKey.Companion.swipeToSwitchArticle
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalArticleSwitchGesture =
    compositionLocalOf<ArticleSwitchGesturePreference> { ArticleSwitchGesturePreference.default }

sealed class ArticleSwitchGesturePreference(val value: Int) : Preference() {
    object HorizontalSwipe : ArticleSwitchGesturePreference(0)

    object VerticalPull : ArticleSwitchGesturePreference(1)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(PreferencesKey.articleSwitchGesture, value)
        }
    }

    fun toDesc(context: Context): String =
        when (this) {
            HorizontalSwipe -> context.getString(R.string.horizontal_swipe)
            VerticalPull -> context.getString(R.string.vertical_pull)
        }

    companion object {
        val default = HorizontalSwipe
        val values = listOf(HorizontalSwipe, VerticalPull)

        fun fromPreferences(preferences: Preferences): ArticleSwitchGesturePreference {
            return when (preferences[PreferencesKey.intKey(articleSwitchGesture)]) {
                HorizontalSwipe.value -> HorizontalSwipe
                VerticalPull.value -> VerticalPull
                null -> migrateFromLegacy(preferences)
                else -> default
            }
        }

        private fun migrateFromLegacy(preferences: Preferences): ArticleSwitchGesturePreference {
            val pull = preferences[PreferencesKey.booleanKey(pullToSwitchArticle)] == true
            val swipe = preferences[PreferencesKey.booleanKey(swipeToSwitchArticle)] == true
            return when {
                swipe -> HorizontalSwipe
                pull -> VerticalPull
                else -> default
            }
        }
    }
}
