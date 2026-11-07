package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import me.ash.reader.domain.model.insights.ExpectedReadTime
import me.ash.reader.domain.model.insights.RankFeatures
import me.ash.reader.domain.model.insights.RankerModel
import me.ash.reader.domain.model.insights.RankerStage
import me.ash.reader.domain.model.insights.ReadingEvent
import me.ash.reader.domain.model.insights.ReadingEventContext
import me.ash.reader.domain.model.insights.ReadingEventType
import me.ash.reader.domain.model.insights.toModelBytes
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.InsightsDao
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.min

/**
 * Fits the two-stage ranker on-device from the collected event log (M2):
 *
 * Stage A (open model) — logistic regression over IMPRESSION events, labeled
 * by whether the same article was opened later. Learns title/feed appeal.
 *
 * Stage B (dwell model) — ridge regression over uncensored DWELL events,
 * target ln(dwell / expected-read). Learns content quality in ratio space,
 * which also retires the Tier 0 dwell-multiplier unit mixing.
 *
 * Labels are derived HERE, at training time, from the raw event log — never
 * precomputed at logging time — so label logic can improve retroactively.
 *
 * Below the minimum-sample thresholds nothing is saved and Tier 0 heuristics
 * keep serving. Runs nightly under charging + idle constraints.
 */
@HiltWorker
class TrainWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val insightsDao: InsightsDao,
    private val accountDao: AccountDao,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Pause switch: off means no new models are fitted. Existing models
        // keep serving — serving is not collection.
        if (!isCollectionEnabled()) return Result.success()

        // Models are per-account: every account gets its nightly fit, not
        // whichever account happened to be current when the job fired.
        val specificId = inputData.getInt("accountId", -1).takeIf { it != -1 }
        val accountIds =
            specificId?.let { listOf(it) } ?: accountDao.queryAll().mapNotNull { it.id }
        for (accountId in accountIds) {
            try {
                train(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Training failed for account %d", accountId)
            }
        }
        return Result.success()
    }

    private suspend fun isCollectionEnabled(): Boolean =
        runCatching {
            applicationContext
                .dataStore
                .data
                .first()[PreferencesKey.booleanKey(PreferencesKey.insightsCollectionEnabled)] ?: true
        }.getOrDefault(true)

    private suspend fun train(accountId: Int): Boolean {
        val now = System.currentTimeMillis()
        // Engagement events only: SCROLL/LINK_OUT rows (the bulk of the log)
        // never participate in training and must not be dragged into memory.
        val events =
            insightsDao.queryEngagementEventsSince(
                accountId = accountId,
                since = Date(now - TRAINING_WINDOW_MS),
                types =
                    listOf(
                        ReadingEventType.IMPRESSION,
                        ReadingEventType.OPEN,
                        ReadingEventType.DWELL,
                        ReadingEventType.MARK_READ_BULK,
                    ),
            ).sortedBy { it.createdAt.time }
        if (events.isEmpty()) return false

        // Leak-free prior features: one replay pass over the sorted stream,
        // snapshotting each sample's feed priors BEFORE the sample's own
        // outcome enters the accumulators. Otherwise feedOpenRate already
        // contains the impression's own open and dwellPriorLog contains the
        // dwell being predicted — the model would learn from an answer it can
        // never see at serving time.
        val priors = HashMap<String, RunningPrior>()
        val snapshots = HashMap<Long, PriorSnapshot>()
        for (event in events) {
            val feedId = event.feedId ?: continue
            val prior = priors.getOrPut(feedId) { RunningPrior() }
            if (event.type == ReadingEventType.IMPRESSION || event.type == ReadingEventType.DWELL) {
                snapshots[event.id] = PriorSnapshot(prior.openRate(), prior.dwellPriorLog())
            }
            prior.add(event)
        }

        // Per-stage isolation: one stage's failure must not skip the other's
        // fit, the cleanup, or the re-rank.
        var fitted = false
        try {
            if (trainOpenModel(accountId, events, snapshots, now)) fitted = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Stage A (open) training failed for account %d", accountId)
        }
        try {
            if (trainDwellModel(accountId, events, snapshots, now)) fitted = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Stage B (dwell) training failed for account %d", accountId)
        }

        if (fitted) {
            insightsDao.deleteModelsBefore(accountId, Date(now - MODEL_RETENTION_MS))
            // New weights change every score — refresh the For You set now
            // rather than waiting for the next sync. APPEND_OR_REPLACE: a
            // rank pass that started before the new weights existed must not
            // cause this re-rank to be dropped by KEEP.
            RankWorker.enqueueNow(workManager, accountId, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        Timber.tag(TAG).i("Training for account %d: fitted=%b", accountId, fitted)
        return fitted
    }

    /** Decay-correct prior accumulators as of a point in the replay. */
    private class PriorSnapshot(val openRate: Float, val dwellPriorLog: Float)

    /**
     * Running per-feed accumulators replayed in time order; every accumulator
     * decays as time advances, then the event lands with weight 1. Formulas
     * come from [InsightsPriorCalculator] so replay and serving never diverge.
     */
    private class RunningPrior {
        var lastAt = -1L
        var impressions = 0.0
        var opens = 0.0
        var engagedOpens = 0.0
        var logDwellSum = 0.0

        fun advanceTo(at: Long) {
            if (lastAt < 0) {
                lastAt = at
                return
            }
            if (at > lastAt) {
                val factor =
                    Math.pow(
                        0.5,
                        (at - lastAt) / 86_400_000.0 / InsightsPriorCalculator.HALF_LIFE_DAYS,
                    )
                impressions *= factor
                opens *= factor
                engagedOpens *= factor
                logDwellSum *= factor
                lastAt = at
            }
        }

        fun add(event: ReadingEvent) {
            advanceTo(event.createdAt.time)
            when (event.type) {
                ReadingEventType.IMPRESSION -> impressions += 1.0
                ReadingEventType.OPEN -> opens += 1.0
                // Batch-level negative evidence: dilutes the feed's open rate.
                ReadingEventType.MARK_READ_BULK -> impressions += (event.position ?: 1).toDouble()
                ReadingEventType.DWELL -> {
                    val duration = event.durationMs ?: return
                    if (event.censored) return
                    if (duration >= InsightsPriorCalculator.ENGAGED_DWELL_MS) {
                        engagedOpens += 1.0
                        logDwellSum += ln((duration / 60_000.0).coerceAtLeast(0.05))
                    }
                }
            }
        }

        fun openRate(): Float = InsightsPriorCalculator.shrunkOpenRateOf(impressions, opens).toFloat()

        fun dwellPriorLog(): Float =
            InsightsPriorCalculator.dwellPriorLogOf(engagedOpens, logDwellSum)
    }

    private suspend fun trainOpenModel(
        accountId: Int,
        events: List<ReadingEvent>,
        snapshots: Map<Long, PriorSnapshot>,
        now: Long,
    ): Boolean {
        // Stage A positives are LIST picks only: swipe-opens within the pager
        // (OPEN_SOURCE_SWIPE) say nothing about title appeal, and there is no
        // impression to credit them against.
        val openTimesByArticle =
            events.filter {
                it.type == ReadingEventType.OPEN && it.articleId != null &&
                    ((it.context ?: 0) and ReadingEventContext.OPEN_SOURCE_SWIPE) == 0
            }.groupBy { it.articleId!! }

        val impressions =
            events.filter {
                it.type == ReadingEventType.IMPRESSION &&
                    it.feedId != null && it.articleId != null
            }.takeLast(MAX_OPEN_SAMPLES)
        if (impressions.size < MIN_OPEN_SAMPLES) return false

        // Attribution window: an open credits only the impression it followed —
        // at most OPEN_ATTRIBUTION_MS later, and never past the article's NEXT
        // impression. Without this, an article shown in many sessions and
        // opened once marks every exposure positive ("shown often → opened").
        val impressionsByArticle = impressions.groupBy { it.articleId!! }
        val windowEndByImpressionId = HashMap<Long, Long>()
        for (imps in impressionsByArticle.values) {
            for (i in imps.indices) {
                val nextAt = imps.getOrNull(i + 1)?.createdAt?.time ?: Long.MAX_VALUE
                windowEndByImpressionId[imps[i].id] =
                    min(imps[i].createdAt.time + OPEN_ATTRIBUTION_MS, nextAt)
            }
        }

        val expectedByArticle =
            fetchContentExpected(impressions.mapNotNull { it.articleId }.toSet())
        val dateByArticle =
            fetchArticleDates(impressions.mapNotNull { it.articleId }.toSet())

        val features = ArrayList<FloatArray>(impressions.size)
        val labels = ArrayList<Float>(impressions.size)
        for (imp in impressions) {
            val expected = expectedByArticle[imp.articleId] ?: continue
            val articleDate = dateByArticle[imp.articleId] ?: continue
            val windowEnd = windowEndByImpressionId[imp.id] ?: continue
            val label =
                if (openTimesByArticle[imp.articleId]
                        ?.any { it.createdAt.time >= imp.createdAt.time && it.createdAt.time <= windowEnd } == true
                ) 1f else 0f
            features.add(
                RankFeatures.openFeatures(
                    feedOpenRate = snapshots.getValue(imp.id).openRate,
                    freshness = RankFeatures.freshness(articleDate, imp.createdAt.time),
                    expectedMinutes = (expected / 60_000f),
                    atMillis = imp.createdAt.time,
                    position = imp.position ?: 0,
                )
            )
            labels.add(label)
        }
        val positives = labels.count { it == 1f }
        val negatives = labels.size - positives
        if (positives < MIN_POSITIVES || negatives < MIN_POSITIVES) return false

        // Balance the classes: opens are rare relative to impressions.
        val posWeight = (negatives.toFloat() / positives).coerceIn(1f, 10f)
        val sampleWeights = labels.map { if (it == 1f) posWeight else 1f }

        // Capture the coroutine context OUTSIDE the lambda: inside the member
        // scope the bare name resolves to the inherited, jobless worker
        // dispatcher, which would make ensureActive() a no-op.
        val ctx = currentCoroutineContext()
        val weights =
            RankerTrainer.fitLogistic(
                features = features,
                labels = labels,
                sampleWeights = sampleWeights,
                checkCancelled = { ctx.ensureActive() },
            )
        // Reweighting lifts log-odds by ln(posWeight); subtract it from the
        // bias so pOpen stays a calibrated probability — the serving score
        // reads pOpen x predictedMinutes as expected engaged minutes.
        weights[0] -= ln(posWeight)
        insightsDao.upsertModel(
            RankerModel(
                accountId = accountId,
                stage = RankerStage.STAGE_OPEN,
                featureVersion = RankFeatures.FEATURE_VERSION,
                weights = weights.toModelBytes(),
                trainSamples = labels.size,
                trainedAt = Date(now),
            )
        )
        return true
    }

    private suspend fun trainDwellModel(
        accountId: Int,
        events: List<ReadingEvent>,
        snapshots: Map<Long, PriorSnapshot>,
        now: Long,
    ): Boolean {
        val dwells =
            events.filter {
                it.type == ReadingEventType.DWELL &&
                    !it.censored && (it.durationMs ?: 0) > 0 && it.feedId != null &&
                    it.articleId != null
            }.takeLast(MAX_DWELL_SAMPLES)
        if (dwells.size < MIN_DWELL_SAMPLES) return false

        val expectedByArticle = fetchContentExpected(dwells.mapNotNull { it.articleId }.toSet())
        val dateByArticle = fetchArticleDates(dwells.mapNotNull { it.articleId }.toSet())

        val features = ArrayList<FloatArray>(dwells.size)
        val targets = ArrayList<Float>(dwells.size)
        for (dwell in dwells) {
            val expected = expectedByArticle[dwell.articleId] ?: continue
            val articleDate = dateByArticle[dwell.articleId] ?: continue
            val expectedMinutes = expected / 60_000.0
            val dwellMinutes = (dwell.durationMs!!) / 60_000.0
            // Target: log dwell ratio, clamped so pathological readings
            // (10x speed-reader or parked screen) can't dominate the fit.
            val ratio = (dwellMinutes / expectedMinutes).coerceIn(RankFeatures.DWELL_RATIO_MIN, RankFeatures.DWELL_RATIO_MAX)
            features.add(
                RankFeatures.dwellFeatures(
                    feedDwellPriorLog = snapshots.getValue(dwell.id).dwellPriorLog,
                    freshness = RankFeatures.freshness(articleDate, dwell.createdAt.time),
                    expectedMinutes = expectedMinutes.toFloat(),
                    atMillis = dwell.createdAt.time,
                )
            )
            targets.add(ln(ratio).toFloat())
        }
        if (features.size < MIN_DWELL_SAMPLES) return false

        val weights = RankerTrainer.fitRidge(features, targets)
        insightsDao.upsertModel(
            RankerModel(
                accountId = accountId,
                stage = RankerStage.STAGE_DWELL,
                featureVersion = RankFeatures.FEATURE_VERSION,
                weights = weights.toModelBytes(),
                trainSamples = targets.size,
                trainedAt = Date(now),
            )
        )
        return true
    }

    /** Content-based expected read times, chunked to stay cursor-safe. */
    private suspend fun fetchContentExpected(articleIds: Set<String>): Map<String, Long> {
        val expected = HashMap<String, Long>()
        for (chunk in articleIds.chunked(CONTENT_CHUNK)) {
            val content =
                try {
                    insightsDao.queryArticleContent(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            content?.forEach { expected[it.id] = ExpectedReadTime.estimateMs(it.rawDescription) }
        }
        return expected
    }

    private suspend fun fetchArticleDates(articleIds: Set<String>): Map<String, Long> {
        val dates = HashMap<String, Long>()
        for (chunk in articleIds.chunked(CONTENT_CHUNK)) {
            val rows =
                try {
                    insightsDao.queryArticleDatesForArticles(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            rows?.forEach { dates[it.id] = it.date.time }
        }
        return dates
    }

    companion object {
        private const val TAG = "TrainWorker"
        const val TRAIN_WORK_NAME = "TRAIN_RANKER"

        // Must match the serving prior window (InsightsPriorCalculator): the
        // replay's accumulators start at the window start, so samples see at
        // most this much history — same horizon, no fit/serve skew.
        private const val TRAINING_WINDOW_MS = 90L * 86_400_000L
        private const val MODEL_RETENTION_MS = 30L * 86_400_000L
        private const val CONTENT_CHUNK = 50

        // Activation thresholds: below these, Tier 0 heuristics keep serving.
        private const val MIN_OPEN_SAMPLES = 200
        private const val MIN_POSITIVES = 20
        private const val MAX_OPEN_SAMPLES = 3_000
        private const val MIN_DWELL_SAMPLES = 50
        private const val MAX_DWELL_SAMPLES = 500

        /** An open credits the impression it followed only within this window. */
        private const val OPEN_ATTRIBUTION_MS = 30L * 60_000L

        fun enqueuePeriodicWork(workManager: WorkManager) {
            val constraints =
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                TRAIN_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TrainWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .addTag(RankWorker.INSIGHTS_WORK_TAG)
                    .build(),
            )
        }
    }
}
