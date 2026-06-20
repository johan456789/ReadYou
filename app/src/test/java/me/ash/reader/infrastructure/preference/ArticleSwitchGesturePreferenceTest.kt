package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleSwitchGesturePreferenceTest {

    @Test
    fun `defaults to horizontal swipe when no preference exists`() {
        val preference = ArticleSwitchGesturePreference.fromPreferences(preferencesOf())

        assertEquals(ArticleSwitchGesturePreference.HorizontalSwipe, preference)
    }

    @Test
    fun `stored gesture preference wins over legacy booleans`() {
        val preferences =
            preferencesOf(
                intPreferencesKey(PreferencesKey.articleSwitchGesture) to
                    ArticleSwitchGesturePreference.VerticalPull.value,
                booleanPreferencesKey(PreferencesKey.swipeToSwitchArticle) to true,
            )

        val preference = ArticleSwitchGesturePreference.fromPreferences(preferences)

        assertEquals(ArticleSwitchGesturePreference.VerticalPull, preference)
    }

    @Test
    fun `migrates legacy swipe to horizontal swipe`() {
        val preferences =
            preferencesOf(booleanPreferencesKey(PreferencesKey.swipeToSwitchArticle) to true)

        val preference = ArticleSwitchGesturePreference.fromPreferences(preferences)

        assertEquals(ArticleSwitchGesturePreference.HorizontalSwipe, preference)
    }

    @Test
    fun `migrates legacy pull to vertical pull`() {
        val preferences =
            preferencesOf(booleanPreferencesKey(PreferencesKey.pullToSwitchArticle) to true)

        val preference = ArticleSwitchGesturePreference.fromPreferences(preferences)

        assertEquals(ArticleSwitchGesturePreference.VerticalPull, preference)
    }

    @Test
    fun `migrates legacy disabled gestures to horizontal swipe`() {
        val preferences =
            preferencesOf(
                booleanPreferencesKey(PreferencesKey.pullToSwitchArticle) to false,
                booleanPreferencesKey(PreferencesKey.swipeToSwitchArticle) to false,
            )

        val preference = ArticleSwitchGesturePreference.fromPreferences(preferences)

        assertEquals(ArticleSwitchGesturePreference.HorizontalSwipe, preference)
    }
}
