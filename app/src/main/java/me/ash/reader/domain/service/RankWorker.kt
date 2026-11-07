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
import me.ash.reader.domain.model.insights.RankFeatures
import me.ash.reader.domain.model.insights.RankerStage
import me.ash.reader.domain.model.insights.UnreadArticleShallow
import me.ash.reader.domain.model.insights.toModelFloats
import me.ash.reader.domain.repository.InsightsDao
import timber.log.Timber
import java.util.Date
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

/**
 * Materializes the For You set: recomputes decayed feed priors from the event
 * log, scores unread articles, applies the admission policy (quota + per-feed
 * cap + exploration slot), and upserts [ArticleRank] rows. Runs chained after
 * every sync and after every finished session.
 *
 * Serving falls back to Tier 0 heuristic weights (documented constants in the
 * companion) until [RankerModel] rows from [TrainWorker] exist with a matching
 * feature version. The scoring shape `pOpen * predictedMinutes` is already the
 * two-stage target so the serving contract never changes.
 */
@HiltWorker
class RankWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val insightsDao: InsightsDao,
    private val accountService: AccountService,
    private val priorCalculator: InsightsPriorCalculator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val accountId =
            inputData.getInt("accountId", -1).takeIf { it != -1 }
                ?: accountService.currentAccountIdFlow.value
                ?: return Result.success() // onboarding: no account yet, nothing to rank
        // A ranking failure must never fail the surrounding sync chain — but
        // cancellation must propagate, or a stopped worker keeps burning CPU.
        try {
            rank(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Ranking failed for account %d", accountId)
        }
        return Result.success()
    }

    internal data class Candidate(
        val meta: UnreadArticleShallow,
        val rank: ArticleRank,
    )

    private suspend fun rank(accountId: Int) {
        val now = System.currentTimeMillis()

        val priors = priorCalculator.computeDecayed(accountId, now)
        if (priors.isNotEmpty()) insightsDao.upsertFeedPriors(priors)
        val priorByFeed = priors.associateBy { it.feedId }

        // Trained stage A/B models (M2) activate as soon as they exist with a
        // matching feature version; otherwise Tier 0 heuristic constants serve.
        // Weights are decoded once per pass, not per candidate.
        val openWeights =
            insightsDao.queryLatestModel(accountId, RankerStage.STAGE_OPEN)
                ?.takeIf { it.featureVersion == RankFeatures.FEATURE_VERSION }
                ?.weights?.toModelFloats()
                ?.takeIf { it.size == RankFeatures.OPEN_FEATURE_COUNT }
        val dwellWeights =
            insightsDao.queryLatestModel(accountId, RankerStage.STAGE_DWELL)
                ?.takeIf { it.featureVersion == RankFeatures.FEATURE_VERSION }
                ?.weights?.toModelFloats()
                ?.takeIf { it.size == RankFeatures.DWELL_FEATURE_COUNT }

        val (refined, rest) = scanCandidates(accountId, now, priorByFeed, openWeights, dwellWeights)
        val all = refined + rest

        if (all.isNotEmpty()) {
            // Admission draws ONLY from the refined candidates: the unrefined tail
            // carries length-based pre-estimates that are not comparable with
            // content-based ones and would let markup-heavy rows outrank real ones.
            val decision = admit(refined, priorByFeed)

            insightsDao.upsertRanks(
                all.map {
                    it.rank.copy(
                        admitted = it.meta.id in decision.admittedIds || it.meta.id in decision.explorationIds,
                        exploration = it.meta.id in decision.explorationIds,
                    )
                }
            )
        }

        // Retention runs regardless of candidate count — event-log pruning must
        // not depend on there being anything to rank this pass.
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
        openWeights: FloatArray?,
        dwellWeights: FloatArray?,
    ): Pair<List<Candidate>, List<Candidate>> {
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
        if (shallow.isEmpty()) return emptyList<Candidate>() to emptyList()

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
                        openWeights = openWeights,
                        dwellWeights = dwellWeights,
                    ),
                )
            }

        val refineIds =
            provisional.sortedByDescending { it.rank.score }.take(REFINE_COUNT).map { it.meta.id }
        val refinedIds = refineIds.toSet()
        val refinedExpected = HashMap<String, Long>()
        for (chunk in refineIds.chunked(REFINE_CHUNK)) {
            runCatching { insightsDao.queryArticleContent(chunk) }
                .getOrNull()
                ?.forEach { refinedExpected[it.id] = ExpectedReadTime.estimateMs(it.rawDescription) }
        }
        val refined = ArrayList<Candidate>(refineIds.size)
        val rest = ArrayList<Candidate>()
        for (candidate in provisional) {
            val refinedScore = refinedExpected[candidate.meta.id]
            if (refinedScore != null) {
                refined.add(
                    Candidate(
                        candidate.meta,
                        score(
                            candidate.meta,
                            priorByFeed[candidate.meta.feedId],
                            now,
                            accountId,
                            refinedScore,
                            openWeights,
                            dwellWeights,
                        ),
                    )
                )
            } else {
                rest.add(candidate)
            }
        }
        return refined to rest
    }

    private fun score(
        article: UnreadArticleShallow,
        prior: FeedPrior?,
        now: Long,
        accountId: Int,
        expectedMs: Long,
        openWeights: FloatArray?,
        dwellWeights: FloatArray?,
    ): ArticleRank {
        val openRate = priorCalculator.shrunkOpenRate(prior).toFloat()
        val freshness = RankFeatures.freshness(article.date.time, now)
        val expectedMinutes = expectedMs / 60_000f

        val pOpen: Float
        val predictedMinutes: Float
        // Per-stage fallback: a stage uses its trained model when present and
        // falls back to the Tier 0 formula otherwise — an account whose open
        // model is fitted but whose dwell samples are still under threshold
        // should keep the fitted stage instead of dropping both to Tier 0.
        val usedOpenModel = openWeights != null
        val usedDwellModel = dwellWeights != null
        if (usedOpenModel) {
            // The position feature is zeroed at serving (training-only device
            // against position bias).
            val xOpen =
                RankFeatures.openFeatures(
                    feedOpenRate = openRate,
                    freshness = freshness,
                    expectedMinutes = expectedMinutes,
                    atMillis = now,
                    position = 0,
                )
            pOpen =
                RankFeatures
                    .sigmoid(dot(openWeights, xOpen))
                    .coerceIn(0.01f, 0.95f)
        } else {
            pOpen = (P_OPEN_BASE + P_OPEN_OPEN_RATE * openRate).toFloat().coerceIn(0.02f, 0.9f)
        }
        if (usedDwellModel) {
            // Stage B predicts ln(dwell/expected) — unit-safe by construction.
            val xDwell =
                RankFeatures.dwellFeatures(
                    feedDwellPriorLog = priorCalculator.dwellPriorLog(prior),
                    freshness = freshness,
                    expectedMinutes = expectedMinutes,
                    atMillis = now,
                )
            val ratio =
                exp(dot(dwellWeights, xDwell).toDouble())
                    .coerceIn(RankFeatures.DWELL_RATIO_MIN, RankFeatures.DWELL_RATIO_MAX)
            predictedMinutes = (expectedMinutes * ratio).toFloat().coerceAtLeast(0.2f)
        } else {
            predictedMinutes =
                (expectedMinutes * priorCalculator.dwellMultiplier(prior).toFloat())
                    .coerceAtLeast(0.2f)
        }
        // Freshness gates only when a Tier 0 component is in play; the trained
        // models carry freshness as a fitted feature, so gating again would
        // double-count it.
        val freshnessGate = (FRESHNESS_WEIGHT * freshness + 1.0 - FRESHNESS_WEIGHT).toFloat()
        val scoreValue =
            if (usedOpenModel && usedDwellModel) pOpen * predictedMinutes
            else pOpen * predictedMinutes * freshnessGate

        return ArticleRank(
            articleId = article.id,
            accountId = accountId,
            feedId = article.feedId,
            pOpen = pOpen,
            predictedMinutes = predictedMinutes,
            score = scoreValue,
            feedPrior = openRate,
            freshness = freshness,
            expectedReadMs = expectedMs,
            computedAt = Date(now),
        )
    }

    private fun dot(w: FloatArray, x: FloatArray): Float {
        var sum = 0f
        for (i in w.indices) sum += w[i] * x[i]
        return sum
    }

    //endregion

    //region Admission policy

    /**
     * Top-score selection under a daily quota and a per-feed cap, plus one
     * exploration slot chosen by Thompson-style sampling on each feed's
     * posterior (uncertainty shrinks with exposure) so quiet feeds still get
     * surfaced. The exploration slot is quota-exempt by design: without it,
     * long-tail content would never earn the engagement data it needs.
     *
     * Pure and dependency-free so the quota/cap/exploration contract is unit
     * testable ([RankAdmission] holds the logic; this delegates).
     */
    private fun admit(candidates: List<Candidate>, priorByFeed: Map<String, FeedPrior>): Admission {
        val decision =
            RankAdmission.admit(
                candidates.map { RankAdmission.Entry(it.meta.id, it.meta.feedId, it.meta.date, it.rank.score) },
                priorByFeed.mapValues { (_, p) -> p.decayedImpressions to p.decayedOpens },
            )
        return Admission(decision.admittedIds, decision.explorationIds)
    }

    private data class Admission(
        val admittedIds: Set<String>,
        val explorationIds: Set<String>,
    )

    //endregion

    companion object {
        private const val TAG = "RankWorker"
        const val RANK_WORK_NAME = "RANK_WORK"

        /**
         * Distinct from the sync tags: rank runs must not appear as sync work
         * in the troubleshooting page, and must not be cancelled by sync
         * cancellation paths.
         */
        const val INSIGHTS_WORK_TAG = "INSIGHTS_WORK_TAG"

        // Tier 0 model constants — the fallback until TrainWorker has fitted
        // both stages past the sample thresholds.
        private const val P_OPEN_BASE = 0.10
        private const val P_OPEN_OPEN_RATE = 0.55
        private const val FRESHNESS_WEIGHT = 0.65

        private const val RANK_RETENTION_MS = 7L * 86_400_000L

        // Bounded-scan tuning: shallow length-only rows are tiny, content
        // chunks are not — hence the small refine chunk size.
        private const val SCAN_WINDOW_MS = 60L * 86_400_000L
        private const val SCAN_CHUNK = 500
        private const val MAX_SCAN = 5_000
        private const val REFINE_COUNT = 200
        private const val REFINE_CHUNK = 50
        private const val EVENT_RETENTION_MS = 180L * 86_400_000L

        fun enqueueNow(
            workManager: WorkManager,
            accountId: Int? = null,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val builder = OneTimeWorkRequestBuilder<RankWorker>()
            accountId?.let { builder.setInputData(workDataOf("accountId" to it)) }
            workManager.enqueueUniqueWork(
                RANK_WORK_NAME,
                policy,
                builder.addTag(INSIGHTS_WORK_TAG).build(),
            )
        }
    }
}
