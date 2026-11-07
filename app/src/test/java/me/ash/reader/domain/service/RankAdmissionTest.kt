package me.ash.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class RankAdmissionTest {

    private fun entry(id: String, feed: String, score: Float, daysAgo: Long = 0) =
        RankAdmission.Entry(
            articleId = id,
            feedId = feed,
            date = Date(System.currentTimeMillis() - daysAgo * 86_400_000L),
            score = score,
        )

    private fun exposure(vararg pairs: Pair<String, Pair<Double, Double>>) = pairs.toMap()

    @Test
    fun `quota caps the admitted set across feeds`() {
        val candidates = (1..30).map { entry("a$it", "feed$it", it.toFloat()) }
        val exposureMap = candidates.associate { it.feedId to (10.0 to 5.0) }
        val decision = RankAdmission.admit(candidates, exposureMap)
        assertEquals(RankAdmission.DAILY_QUOTA, decision.admittedIds.size)
    }

    @Test
    fun `per-feed cap limits any single feed`() {
        val candidates = (1..30).map { entry("a$it", "feed", it.toFloat()) }
        val decision =
            RankAdmission.admit(candidates, exposure("feed" to (10.0 to 5.0)))
        // Quota is 20, but a single feed can occupy at most PER_FEED_CAP slots.
        assertEquals(RankAdmission.PER_FEED_CAP, decision.admittedIds.size)
    }

    @Test
    fun `top scores are admitted first`() {
        val candidates =
            listOf(entry("low", "f1", 1f), entry("high", "f2", 9f), entry("mid", "f3", 5f))
        val decision = RankAdmission.admit(candidates, emptyMap())
        assertTrue(decision.admittedIds.contains("high"))
        assertEquals(3, decision.admittedIds.size)
    }

    @Test
    fun `exploration slot is quota-exempt and disjoint from admitted`() {
        val candidates = (1..25).map { entry("a$it", "feed", it.toFloat()) }
        val decision =
            RankAdmission.admit(candidates, exposure("feed" to (10.0 to 5.0)))
        assertTrue(decision.explorationIds.size <= 1)
        assertTrue(decision.explorationIds.intersect(decision.admittedIds).isEmpty())
        assertTrue(decision.explorationIds.all { id -> candidates.any { it.articleId == id } })
    }

    @Test
    fun `admitted ids are always a subset of the refined input`() {
        // Regression for the round-3 P0: admission derived from a different
        // candidate list than the one written leaves article_rank empty.
        val refined = (1..10).map { entry("r$it", "f", it.toFloat()) }
        val decision = RankAdmission.admit(refined, emptyMap())
        assertTrue(
            decision.admittedIds.all { id -> refined.any { it.articleId == id } },
        )
        assertTrue(
            decision.explorationIds.all { id -> refined.any { it.articleId == id } },
        )
    }

    @Test
    fun `exploration gives a zero-history feed a real chance against an established one`() {
        // Thompson sampling on the open-rate posterior: a feed with no history
        // has posterior mean 0.25 with high spread; an established feed ~0.76
        // with low spread. The unseen feed cannot win most draws (its mean is
        // lower) but must win a non-trivial share — that is the starvation
        // guard. Faithful simulation puts it at ~22%, so a 10% floor is a
        // comfortable margin.
        val unseen = (1..5).map { entry("u$it", "unseen", 5f, daysAgo = it.toLong()) }
        val exposed = (1..5).map { entry("e$it", "exposed", 5f, daysAgo = it.toLong()) }
        val exposure =
            exposure(
                "unseen" to (0.0 to 0.0),
                "exposed" to (100.0 to 80.0),
            )
        var unseenWins = 0
        repeat(500) {
            val decision = RankAdmission.admit(unseen + exposed, exposure)
            if (decision.explorationIds.firstOrNull()?.startsWith("u") == true) unseenWins++
        }
        assertTrue("unseen won only $unseenWins/500", unseenWins > 60)
    }
}
