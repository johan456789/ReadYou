package me.ash.reader.domain.service

import me.ash.reader.domain.model.insights.FeedPrior
import me.ash.reader.domain.model.insights.ReadingEventType
import me.ash.reader.domain.repository.InsightsDao
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * Per-feed engagement priors computed from the event log, shared by serving
 * ([RankWorker]) and training ([TrainWorker]) so both sides see identical
 * semantics. Accumulators are exponentially decayed (60-day half-life) over a
 * 90-day window and recomputed from raw events each pass — cheap at RSS
 * volumes and immune to accumulator drift.
 *
 * Dilution is deliberately not compensated: the recommender optimizes
 * per-article predicted dwell, so a high-volume feed's prior already averages
 * in its filler. Volume is handled at admission time (per-feed caps), nowhere
 * else.
 */
@Singleton
class InsightsPriorCalculator @Inject constructor(
    private val insightsDao: InsightsDao,
) {

    suspend fun computeDecayed(accountId: Int, now: Long): List<FeedPrior> {
        val events =
            insightsDao.queryEngagementEventsSince(
                accountId = accountId,
                since = Date(now - PRIOR_WINDOW_MS),
                types =
                    listOf(
                        ReadingEventType.IMPRESSION,
                        ReadingEventType.OPEN,
                        ReadingEventType.DWELL,
                    ),
            )
        if (events.isEmpty()) return emptyList()

        class Acc {
            var impressions: Double = 0.0
            var opens: Double = 0.0
            var engagedOpens: Double = 0.0
            var logDwellSum: Double = 0.0
        }

        val acc = HashMap<String, Acc>()
        for (event in events) {
            val feedId = event.feedId ?: continue
            val a = acc.getOrPut(feedId) { Acc() }
            val ageDays = (now - event.createdAt.time).coerceAtLeast(0) / 86_400_000.0
            val weight = Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
            when (event.type) {
                ReadingEventType.IMPRESSION -> a.impressions += weight
                ReadingEventType.OPEN -> a.opens += weight
                ReadingEventType.DWELL -> {
                    val duration = event.durationMs ?: continue
                    if (event.censored) continue // truncated by exit, not by finishing
                    if (duration >= ENGAGED_DWELL_MS) {
                        a.engagedOpens += weight
                        a.logDwellSum += weight * ln((duration / 60_000.0).coerceAtLeast(0.05))
                    }
                }
            }
        }
        return acc.map { (feedId, a) ->
            FeedPrior(
                accountId = accountId,
                feedId = feedId,
                decayedImpressions = a.impressions,
                decayedOpens = a.opens,
                decayedEngagedOpens = a.engagedOpens,
                decayedLogDwellSum = a.logDwellSum,
                updatedAt = Date(now),
            )
        }
    }

    /**
     * Beta-posterior open rate shrunk toward the global mean (pseudo-counts
     * α=2, β=6 → prior mean 0.25). The headline feed-appeal signal.
     */
    fun shrunkOpenRate(prior: FeedPrior?): Double =
        shrunkOpenRateOf(prior?.decayedImpressions ?: 0.0, prior?.decayedOpens ?: 0.0)

    /**
     * Geometric mean dwell of engaged opens, in minutes, as a multiplier on
     * the content-relative expected read time. 1.0 for feeds without history.
     * The Tier 0 scorer multiplies minutes by this.
     */
    fun dwellMultiplier(prior: FeedPrior?): Double =
        dwellMultiplierOf(prior?.decayedEngagedOpens ?: 0.0, prior?.decayedLogDwellSum ?: 0.0)

    /** ln(geometric-mean engaged dwell minutes), clamped; neutral default ln(3). */
    fun dwellPriorLog(prior: FeedPrior?): Float =
        dwellPriorLogOf(prior?.decayedEngagedOpens ?: 0.0, prior?.decayedLogDwellSum ?: 0.0)

    companion object {
        const val ALPHA = 2.0
        const val BETA = 6.0
        const val HALF_LIFE_DAYS = 60.0
        const val ENGAGED_DWELL_MS = 30_000L

        private const val PRIOR_WINDOW_MS = 90L * 86_400_000L

        fun shrunkOpenRateOf(impressions: Double, opens: Double): Double =
            (opens + ALPHA) / (impressions + ALPHA + BETA)

        fun dwellMultiplierOf(engagedOpens: Double, logDwellSum: Double): Double =
            if (engagedOpens >= 1.0) {
                exp(logDwellSum / engagedOpens).coerceIn(0.25, 2.5)
            } else 1.0

        fun dwellPriorLogOf(engagedOpens: Double, logDwellSum: Double): Float =
            if (engagedOpens >= 1.0) {
                (logDwellSum / engagedOpens).toFloat().coerceIn(0f, 3f)
            } else ln(3.0).toFloat()
    }
}
