package me.ash.reader.domain.service

import me.ash.reader.domain.model.insights.FeedPrior
import java.util.Date
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure admission policy for the For You set: top-score selection under a
 * daily quota and a per-feed cap, plus one quota-exempt exploration slot
 * chosen by Thompson-style sampling on each feed's posterior.
 *
 * Extracted from RankWorker as plain data-in/data-out so the quota/cap/
 * exploration contract is unit testable without a database.
 */
internal object RankAdmission {

    const val DAILY_QUOTA = 20
    const val PER_FEED_CAP = 3

    /** Shrunk-posterior pseudo-counts — must match InsightsPriorCalculator. */
    private const val ALPHA = 2.0
    private const val BETA = 6.0

    data class Entry(
        val articleId: String,
        val feedId: String,
        val date: Date,
        val score: Float,
    )

    data class Decision(
        val admittedIds: Set<String>,
        val explorationIds: Set<String>,
    )

    fun admit(
        candidates: List<Entry>,
        /** feedId -> (decayedImpressions, decayedOpens) for the exploration posterior. */
        feedExposure: Map<String, Pair<Double, Double>>,
    ): Decision {
        val admittedIds = LinkedHashSet<String>()
        val perFeedCount = HashMap<String, Int>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (admittedIds.size >= DAILY_QUOTA) break
            val count = perFeedCount.getOrDefault(candidate.feedId, 0)
            if (count >= PER_FEED_CAP) continue
            perFeedCount[candidate.feedId] = count + 1
            admittedIds.add(candidate.articleId)
        }

        val explorationIds = explore(candidates, admittedIds, feedExposure)
        return Decision(admittedIds, explorationIds)
    }

    private fun explore(
        candidates: List<Entry>,
        admittedIds: Set<String>,
        feedExposure: Map<String, Pair<Double, Double>>,
    ): Set<String> {
        val remaining = candidates.filter { it.articleId !in admittedIds }
        if (remaining.isEmpty()) return emptySet()

        val byFeed = remaining.groupBy { it.feedId }
        var bestScore = Double.NEGATIVE_INFINITY
        var best: Entry? = null
        for ((feedId, articles) in byFeed) {
            val (impressions, opens) = feedExposure[feedId] ?: (0.0 to 0.0)
            // Sample around the shrunk posterior with spread 1/sqrt(n+2) as a
            // cheap Thompson stand-in (no gamma sampler at Tier 0).
            val posteriorMean = (opens + ALPHA) / (impressions + ALPHA + BETA)
            val sample = posteriorMean + gaussian() / sqrt(impressions + 2.0)
            if (sample > bestScore) {
                bestScore = sample
                best = articles.maxBy { it.date } // newest voice of the least-heard feed
            }
        }
        return best?.let { setOf(it.articleId) } ?: emptySet()
    }

    /** Box–Muller standard normal; kotlin.random has no nextGaussian. */
    private fun gaussian(): Double {
        val u1 = Random.Default.nextDouble().coerceIn(1e-9, 1.0)
        val u2 = Random.Default.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
