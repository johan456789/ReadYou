package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.PreferencesKey.Companion.swipeToSwitchArticle
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalSwipeToSwitchArticle = compositionLocalOf { SwipeToSwitchArticlePreference.default }

class SwipeToSwitchArticlePreference(val value: Boolean) : Preference() {
    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(PreferencesKey.swipeToSwitchArticle, value)
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) =
        SwipeToSwitchArticlePreference(!value).put(context, scope)

    companion object {
        val default = SwipeToSwitchArticlePreference(false)
        fun fromPreference(preference: Preferences): SwipeToSwitchArticlePreference {
            return SwipeToSwitchArticlePreference(
                preference[PreferencesKey.booleanKey(swipeToSwitchArticle)] ?: return default
            )
        }
    }
}
