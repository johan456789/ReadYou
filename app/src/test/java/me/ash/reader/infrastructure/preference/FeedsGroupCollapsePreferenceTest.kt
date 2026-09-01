package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsGroupCollapsePreferenceTest {

    @Test
    fun `fromJson returns empty for null and blank`() {
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromJson(null))
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromJson(""))
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromJson("   "))
    }

    @Test
    fun `fromJson returns empty for invalid json`() {
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromJson("{invalid}"))
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromJson("not json"))
    }

    @Test
    fun `fromJson parses valid flat map`() {
        val json = """{"1${'$'}read_you_app_default_group":true,"1${'$'}tech_group":false}"""
        val map = FeedsGroupCollapsePreference.fromJson(json)
        assertEquals(true, map["1\$read_you_app_default_group"])
        assertEquals(false, map["1\$tech_group"])
        assertEquals(2, map.size)
    }

    @Test
    fun `toJson and fromJson roundtrip`() {
        val original = mapOf("1\$a" to true, "2\$b" to false, "1\$c" to true)
        val json = FeedsGroupCollapsePreference.toJson(original)
        val restored = FeedsGroupCollapsePreference.fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun `fromPreferences returns empty when key missing`() {
        val prefs = preferencesOf()
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromPreferences(prefs))
    }

    @Test
    fun `fromPreferences returns map when valid json stored`() {
        val key = stringPreferencesKey(PreferencesKey.feedsGroupCollapseState)
        val json = """{"1${'$'}g1":true}"""
        val prefs = preferencesOf(key to json)
        assertEquals(mapOf("1\$g1" to true), FeedsGroupCollapsePreference.fromPreferences(prefs))
    }

    @Test
    fun `fromPreferences returns empty on invalid json`() {
        val key = stringPreferencesKey(PreferencesKey.feedsGroupCollapseState)
        val prefs = preferencesOf(key to "{bad json}")
        assertEquals(emptyMap<String, Boolean>(), FeedsGroupCollapsePreference.fromPreferences(prefs))
    }

    @Test
    fun `mergedForWrite preserves other accounts`() {
        val persisted = mapOf("1\$g1" to true, "2\$g1" to false, "1\$g2" to true)
        val current = mapOf("1\$g1" to false, "1\$g3" to true)
        val merged = FeedsGroupCollapsePreference.mergedForWrite(persisted, 1, current)
        // 2$g1 should be preserved, 1$g2 removed (stale, not in current), 1$g1 overwritten, 1$g3 added
        assertEquals(false, merged["1\$g1"])
        assertEquals(true, merged["1\$g3"])
        assertEquals(false, merged["2\$g1"])
        assertTrue(!merged.containsKey("1\$g2"))
    }

    @Test
    fun `mergedForWrite with null accountId just merges`() {
        val persisted = mapOf("1\$g1" to true)
        val current = mapOf("1\$g2" to false)
        val merged = FeedsGroupCollapsePreference.mergedForWrite(persisted, null, current)
        assertEquals(true, merged["1\$g1"])
        assertEquals(false, merged["1\$g2"])
    }

    @Test
    fun `mergedForWrite removes stale current account keys not in currentMap`() {
        // Simulate pruning: persisted had 1$g1,1$g2, current only has 1$g1 -> 1$g2 should be removed after merged
        val persisted = mapOf("1\$g1" to true, "1\$g2" to true, "2\$g1" to true)
        val current = mapOf("1\$g1" to true)
        val merged = FeedsGroupCollapsePreference.mergedForWrite(persisted, 1, current)
        assertTrue(merged.containsKey("1\$g1"))
        assertTrue(!merged.containsKey("1\$g2"))
        assertTrue(merged.containsKey("2\$g1"))
    }

    @Test
    fun `PreferencesKey contains feedsGroupCollapseState as StringKey`() {
        val key = PreferencesKey.keys[PreferencesKey.feedsGroupCollapseState]
        assertTrue(key is PreferencesKey.StringKey)
        assertEquals(PreferencesKey.feedsGroupCollapseState, key?.name)
    }
}
