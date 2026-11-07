package me.ash.reader.domain.model.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RankFeaturesTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun `feature dimensions match the declared constants`() {
        val open =
            RankFeatures.openFeatures(
                feedOpenRate = 0.3f,
                freshness = 0.8f,
                expectedMinutes = 6f,
                atMillis = 0L,
                position = 5,
                zone = zone,
            )
        val dwell =
            RankFeatures.dwellFeatures(
                feedDwellPriorLog = 1.1f,
                freshness = 0.8f,
                expectedMinutes = 6f,
                atMillis = 0L,
                zone = zone,
            )
        assertEquals(RankFeatures.OPEN_FEATURE_COUNT, open.size)
        assertEquals(RankFeatures.DWELL_FEATURE_COUNT, dwell.size)
        assertEquals(1f, open[0], 1e-6f)
        assertEquals(1f, dwell[0], 1e-6f)
    }

    @Test
    fun `open and dwell share the first five features`() {
        // Shared prefix (bias, feed signal, freshness, expected, hour sin..)
        // must agree for identical inputs so the two stages stay comparable.
        val open =
            RankFeatures.openFeatures(0.3f, 0.8f, 6f, fixedMillis(), position = 0, zone = zone)
        val dwell = RankFeatures.dwellFeatures(1.1f, 0.8f, 6f, fixedMillis(), zone = zone)
        assertEquals(open[0], dwell[0], 1e-6f)
        assertEquals(open[2], dwell[2], 1e-6f)
        assertEquals(open[3], dwell[3], 1e-6f)
        assertEquals(open[4], dwell[4], 1e-6f)
        assertEquals(open[5], dwell[5], 1e-6f)
    }

    @Test
    fun `all features stay in a sane bounded range`() {
        val open =
            RankFeatures.openFeatures(1.5f, 2f, 500f, fixedMillis(), position = 500, zone = zone)
        for (f in open) {
            assertTrue("feature out of range: $f", f in -2f..2f)
        }
    }

    @Test
    fun `hour features are cyclic without a midnight cliff`() {
        val beforeMidnight = RankFeatures.openFeatures(0f, 1f, 1f, atUtc("2026-01-01T23:59:00"), 0, zone)
        val afterMidnight = RankFeatures.openFeatures(0f, 1f, 1f, atUtc("2026-01-02T00:01:00"), 0, zone)
        val distance =
            Math.hypot(
                (beforeMidnight[4] - afterMidnight[4]).toDouble(),
                (beforeMidnight[5] - afterMidnight[5]).toDouble(),
            )
        assertTrue("23:59 and 00:01 must be nearly identical: $distance", distance < 0.05)
    }

    @Test
    fun `position normalizes monotonically and saturates`() {
        val p0 = RankFeatures.openFeatures(0f, 1f, 1f, fixedMillis(), position = 0, zone = zone)[8]
        val p10 = RankFeatures.openFeatures(0f, 1f, 1f, fixedMillis(), position = 10, zone = zone)[8]
        val p50 = RankFeatures.openFeatures(0f, 1f, 1f, fixedMillis(), position = 50, zone = zone)[8]
        val p500 = RankFeatures.openFeatures(0f, 1f, 1f, fixedMillis(), position = 500, zone = zone)[8]
        assertTrue(p0 < p10 && p10 < p50 && p50 == p500)
    }

    @Test
    fun `model weight byte packing round-trips`() {
        val weights = floatArrayOf(0f, -1.5f, 3.25f, 1e-8f, Float.MAX_VALUE / 2)
        assertEquals(weights.toList(), weights.toModelBytes().toModelFloats().toList())
    }

    private fun fixedMillis(): Long = atUtc("2026-01-15T10:30:00")

    private fun atUtc(iso: String): Long =
        java.time.LocalDateTime.parse(iso)
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()
}
