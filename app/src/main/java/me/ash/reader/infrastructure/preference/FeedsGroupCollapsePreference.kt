package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore

object FeedsGroupCollapsePreference {

    private val gson = Gson()
    private val type = object : TypeToken<Map<String, Boolean>>() {}.type
    private val key = stringPreferencesKey(PreferencesKey.feedsGroupCollapseState)

    fun fromPreferences(preferences: Preferences): Map<String, Boolean> {
        val json = preferences[key] ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, Boolean>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun toJson(map: Map<String, Boolean>): String = gson.toJson(map)

    fun fromJson(json: String?): Map<String, Boolean> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            gson.fromJson<Map<String, Boolean>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun read(context: Context): Map<String, Boolean> {
        val json = context.dataStore.data.map { it[key] }.first()
        return fromJson(json)
    }

    suspend fun write(context: Context, map: Map<String, Boolean>) {
        val json = toJson(map)
        context.dataStore.edit { prefs ->
            prefs[key] = json
        }
    }

    // Merge helper for account-scoped flat map: preserve other accounts
    fun mergedForWrite(
        persisted: Map<String, Boolean>,
        currentAccountId: Int?,
        currentVisible: Map<String, Boolean>
    ): Map<String, Boolean> {
        if (currentAccountId == null) return persisted + currentVisible
        val prefix = "${currentAccountId}\$"
        val filtered = persisted.filterKeys { !it.startsWith(prefix) }
        return filtered + currentVisible
    }
}
