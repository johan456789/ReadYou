package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.ash.reader.domain.model.insights.ArticleRank
import me.ash.reader.domain.model.insights.ExpectedReadTime
import me.ash.reader.domain.model.insights.FeedPrior
import me.ash.reader.domain.model.insights.ReadingEventType
import me.ash.reader.domain.model.insights.UnreadArticleShallow
import me.ash.reader.domain.repository.InsightsDao
import timber.log.Timber
import java.util.Date
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Materializes the For You set: recomputes decayed feed priors from the event
 * log, scores unread articles, applies the admission policy (quota + per-feed
 * cap + exploration slot), and upserts [ArticleRank] rows. Runs chained after
 * every sync and after every finished session.
 *
 * M1 uses Tier 0 heuristic weights (the documented constants in [Tier0]);
 * [RankerModel] rows from TrainWorker (M2) replace them once fitted. The
 * scoring shape `pOpen * predictedMinutes` is already the two-stage target so
 * the serving contract never changes.
 */
@HiltWorker
class RankWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val insightsDao: InsightsDao,
    private val accountService: AccountService,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val accountId =
            inputData.getInt("accountId", -1).takeIf { it != -1 }
                ?: accountService.currentAccountIdFlow.value
                ?: return Result.success() // onboarding: no account yet, nothing to rank
        // A ranking failure must never fail the surrounding sync chain.
        runCatching { rank(accountId) }
            .onFailure { Timber.tag(TAG).e(it, "Ranking failed for account %d", accountId) }
        return Result.success()
    }

    private data class Candidate(
        val meta: UnreadArticleShallow,
        val rank: ArticleRank,
    )

    private data class Admission(
        val admittedIds: Set<String>,
        val explorationIds: Set<String>,
    )

    private suspend fun rank(accountId: Int) {
        val now = System.currentTimeMillis()

        val priors = computeDecayedPriors(accountId, now)
        if (priors.isNotEmpty()) insightsDao.upsertFeedPriors(priors)
        val priorByFeed = priors.associateBy { it.feedId }

        val candidates = scanCandidates(accountId, now, priorByFeed)
        if (candidates.isEmpty()) return

        val decision = admit(candidates, priorByFeed)

        insightsDao.upsertRanks(
            candidates.map {
                it.rank.copy(
                    admitted = it.meta.id in decision.admittedIds || it.meta.id in decision.explorationIds,
                    exploration = it.meta.id in decision.explorationIds,
                )
            }
        )
        insightsDao.deleteStaleRanks(accountId, Date(now - RANK_RETENTION_MS))
        insightsDao.deleteEventsBefore(Date(now - EVENT_RETENTION_MS))
    }

    //region Tier 0 scoring

    /**
     * Two-phase scoring that never bulk-loads article content: a shallow,
     * length-only scan over the scoring window, then a content-based refine of
     * the most promising slice. Full raw descriptions can exceed the SQLite
     * cursor window on full-text feeds, which made the old load-everything
     * query kill ranking silently on exactly those accounts.
     */
    private suspend fun scanCandidates(
        accountId: Int,
        now: Long,
        priorByFeed: Map<String, FeedPrior>,
    ): List<Candidate> {
        val shallow = ArrayList<UnreadArticleShallow>()
        var offset = 0
        while (shallow.size < MAX_SCAN) {
            val chunk =
                insightsDao.queryUnreadArticleShallow(
                    accountId = accountId,
                    since = Date(now - SCAN_WINDOW_MS),
                    limit = SCAN_CHUNK,
                    offset = offset,
                )
            shallow += chunk
            if (chunk.size < SCAN_CHUNK) break
            offset += SCAN_CHUNK
        }
        if (shallow.isEmpty()) return emptyList()

        val provisional =
            shallow.map {
                Candidate(
                    it,
                    score(
                        article = it,
                        prior = priorByFeed[it.feedId],
                        now = now,
                        accountId = accountId,
                        expectedMs = ExpectedReadTime.estimateFromLengthMs(it.contentLength),
                    ),
                )
            }

        val refineIds =
            provisional.sortedByDescending { it.rank.score }.take(REFINE_COUNT).map { it.meta.id }
        val refinedExpected = HashMap<String, Long>()
        for (chunk in refineIds.chunked(REFINE_CHUNK)) {
            runCatching { insightsDao.queryArticleContent(chunk) }
                .getOrNull()
                ?.forEach { refinedExpected[it.id] = ExpectedReadTime.estimateMs(it.rawDescription) }
        }
        return provisional.map { candidate ->
            refinedExpected[candidate.meta.id]?.let { expected ->
                Candidate(
                    candidate.meta,
                    score(candidate.meta, priorByFeed[candidate.meta.feedId], now, accountId, expected),
                )
            } ?: candidate
        }
    }

    private fun score(
        article: UnreadArticleShallow,
        prior: FeedPrior?,
        now: Long,
        accountId: Int,
        expectedMs: Long,
    ): ArticleRank {
        // Beta posterior over the feed's open rate, shrunk toward the global
        // mean (pseudo-counts α=2, β=6 → prior mean 0.25).
        val impressions = prior?.decayedImpressions ?: 0.0
        val opens = prior?.decayedOpens ?: 0.0
        val openRate = (opens + ALPHA) / (impressions + ALPHA + BETA)

        // Geometric mean dwell of engaged opens as a multiplier on the
        // content-relative expected read time; 1.0 for feeds without history.
        val engaged = prior?.decayedEngagedOpens ?: 0.0
        val dwellMultiplier =
            if (prior != null && engaged >= 1.0) {
                exp(prior.decayedLogDwellSum / engaged).coerceIn(0.25, 2.5)
            } else 1.0

        val ageHours = (now - article.date.time).coerceAtLeast(0) / 3_600_000.0
        val freshness = exp(-ageHours / FRESHNESS_TAU_HOURS)

        val pOpen = (P_OPEN_BASE + P_OPEN_OPEN_RATE * openRate).toFloat().coerceIn(0.02f, 0.9f)
        val predictedMinutes = (expectedMs / 60_000.0 * dwellMultiplier).toFloat().coerceAtLeast(0.2f)
        val freshnessGate = (FRESHNESS_WEIGHT * freshness + 1.0 - FRESHNESS_WEIGHT).toFloat()
        val scoreValue = pOpen * predictedMinutes * freshnessGate

        return ArticleRank(
            articleId = article.id,
            accountId = accountId,
            feedId = article.feedId,
            pOpen = pOpen,
            predictedMinutes = predictedMinutes,
            score = scoreValue,
            feedPrior = openRate.toFloat(),
            freshness = freshness.toFloat(),
            expectedReadMs = expectedMs,
            computedAt = Date(now),
        )
    }

    //endregion

    //region Admission policy

    /**
     * Top-score selection under a daily quota and a per-feed cap, plus one
     * exploration slot chosen by Thompson-style sampling on each feed's
     * posterior (uncertainty shrinks with exposure) so quiet feeds still get
     * surfaced. The exploration slot is quota-exempt by design: without it,
     * long-tail content would never earn the engagement data it needs.
     */
    private fun admit(candidates: List<Candidate>, priorByFeed: Map<String, FeedPrior>): Admission {
        val admittedIds = LinkedHashSet<String>()
        val perFeedCount = HashMap<String, Int>()
        for (candidate in candidates.sortedByDescending { it.rank.score }) {
            if (admittedIds.size >= DAILY_QUOTA) break
            val count = perFeedCount.getOrDefault(candidate.meta.feedId, 0)
            if (count >= PER_FEED_CAP) continue
            perFeedCount[candidate.meta.feedId] = count + 1
            admittedIds.add(candidate.meta.id)
        }

        val explorationIds = explore(candidates, admittedIds, priorByFeed)
        return Admission(admittedIds, explorationIds)
    }

    private fun explore(
        candidates: List<Candidate>,
        admittedIds: Set<String>,
        priorByFeed: Map<String, FeedPrior>,
    ): Set<String> {
        val remaining = candidates.filter { it.meta.id !in admittedIds }
        if (remaining.isEmpty()) return emptySet()

        val byFeed = remaining.groupBy { it.meta.feedId }
        var bestScore = Double.NEGATIVE_INFINITY
        var best: Candidate? = null
        for ((feedId, articles) in byFeed) {
            val impressions = priorByFeed[feedId]?.decayedImpressions ?: 0.0
            val opens = priorByFeed[feedId]?.decayedOpens ?: 0.0
            // Sample around the shrunk posterior with spread 1/sqrt(n+2) as a
            // cheap Thompson stand-in (no gamma sampler at Tier 0).
            val posteriorMean = (opens + ALPHA) / (impressions + ALPHA + BETA)
            val sample = posteriorMean + gaussian() / sqrt(impressions + 2.0)
            if (sample > bestScore) {
                bestScore = sample
                best = articles.maxBy { it.meta.date } // newest voice of the least-heard feed
            }
        }
        return best?.let { setOf(it.meta.id) } ?: emptySet()
    }

    /** Box–Muller standard normal; kotlin.random has no nextGaussian. */
    private fun gaussian(): Double {
        val u1 = Random.Default.nextDouble().coerceIn(1e-9, 1.0)
        val u2 = Random.Default.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    //endregion

    /**
     * Exponentially decayed per-feed accumulators (60-day half-life) over the
     * prior window. Recomputed from the event log each pass — cheap at RSS
     * volumes and immune to accumulator drift.
     */
    private suspend fun computeDecayedPriors(accountId: Int, now: Long): List<FeedPrior> {
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

    companion object {
        private const val TAG = "RankWorker"
        const val RANK_WORK_NAME = "RANK_WORK"

        /**
         * Distinct from the sync tags: rank runs must not appear as sync work
         * in the troubleshooting page, and must not be cancelled by sync
         * cancellation paths.
         */
        const val INSIGHTS_WORK_TAG = "INSIGHTS_WORK_TAG"

        const val DAILY_QUOTA = 20
        const val PER_FEED_CAP = 3

        // Tier 0 model constants — placeholder until TrainWorker: these ARE the model.
        private const val ALPHA = 2.0
        private const val BETA = 6.0
        private const val P_OPEN_BASE = 0.10
        private const val P_OPEN_OPEN_RATE = 0.55
        private const val FRESHNESS_TAU_HOURS = 72.0
        private const val FRESHNESS_WEIGHT = 0.65

        private const val HALF_LIFE_DAYS = 60.0
        private const val PRIOR_WINDOW_MS = 90L * 86_400_000L
        private const val ENGAGED_DWELL_MS = 30_000L
        private const val RANK_RETENTION_MS = 7L * 86_400_000L

        // Bounded-scan tuning: shallow length-only rows are tiny, content
        // chunks are not — hence the small refine chunk size.
        private const val SCAN_WINDOW_MS = 60L * 86_400_000L
        private const val SCAN_CHUNK = 500
        private const val MAX_SCAN = 5_000
        private const val REFINE_COUNT = 200
        private const val REFINE_CHUNK = 50
        private const val EVENT_RETENTION_MS = 180L * 86_400_000L

        fun enqueueNow(workManager: WorkManager, accountId: Int? = null) {
            val builder = OneTimeWorkRequestBuilder<RankWorker>()
            accountId?.let { builder.setInputData(workDataOf("accountId" to it)) }
            workManager.enqueueUniqueWork(
                RANK_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                builder.build(),
            )
        }
    }
}
