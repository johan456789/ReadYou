package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import me.ash.reader.domain.model.insights.ExpectedReadTime
import me.ash.reader.domain.model.insights.FeedPrior
import me.ash.reader.domain.model.insights.RankFeatures
import me.ash.reader.domain.model.insights.RankerModel
import me.ash.reader.domain.model.insights.RankerStage
import me.ash.reader.domain.model.insights.ReadingEvent
import me.ash.reader.domain.model.insights.ReadingEventType
import me.ash.reader.domain.model.insights.toModelBytes
import me.ash.reader.domain.repository.InsightsDao
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
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
    private val priorCalculator: InsightsPriorCalculator,
    private val accountService: AccountService,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val accountId =
            inputData.getInt("accountId", -1).takeIf { it != -1 }
                ?: accountService.currentAccountIdFlow.value
                ?: return Result.success()
        // Cancellation must propagate: a stopped worker must not keep fitting.
        try {
            train(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Training failed for account %d", accountId)
        }
        return Result.success()
    }

    private suspend fun train(accountId: Int) {
        val now = System.currentTimeMillis()
        val events =
            insightsDao.queryEventsSince(accountId, Date(now - TRAINING_WINDOW_MS))
                .sortedBy { it.createdAt.time }
        if (events.isEmpty()) return

        val priors = priorCalculator.computeDecayed(accountId, now)
        val priorByFeed = priors.associateBy { it.feedId }

        var trainedStages = 0
        if (trainOpenModel(accountId, events, priorByFeed, now)) trainedStages++
        if (trainDwellModel(accountId, events, priorByFeed, now)) trainedStages++

        if (trainedStages > 0) {
            insightsDao.deleteModelsBefore(accountId, Date(now - MODEL_RETENTION_MS))
            // New weights change every score — refresh the For You set now
            // rather than waiting for the next sync.
            RankWorker.enqueueNow(workManager, accountId)
        }
        Timber.tag(TAG).i("Training done for account %d: %d stage(s) fitted", accountId, trainedStages)
    }

    private suspend fun trainOpenModel(
        accountId: Int,
        events: List<ReadingEvent>,
        priorByFeed: Map<String, FeedPrior>,
        now: Long,
    ): Boolean {
        val openTimesByArticle =
            events.filter { it.type == ReadingEventType.OPEN && it.articleId != null }
                .groupBy { it.articleId!! }

        val impressions =
            events.filter {
                it.type == ReadingEventType.IMPRESSION &&
                    it.feedId != null && it.articleId != null
            }.takeLast(MAX_OPEN_SAMPLES)
        if (impressions.size < MIN_OPEN_SAMPLES) return false

        val expectedByArticle =
            fetchContentExpected(impressions.mapNotNull { it.articleId }.toSet())
        val dateByArticle =
            fetchArticleDates(impressions.mapNotNull { it.articleId }.toSet())

        val features = ArrayList<FloatArray>(impressions.size)
        val labels = ArrayList<Float>(impressions.size)
        for (imp in impressions) {
            val expected = expectedByArticle[imp.articleId] ?: continue
            val articleDate = dateByArticle[imp.articleId] ?: continue
            val label =
                if (openTimesByArticle[imp.articleId]?.any { it.createdAt.time >= imp.createdAt.time } == true) 1f else 0f
            features.add(
                RankFeatures.openFeatures(
                    feedOpenRate = priorCalculator.shrunkOpenRate(priorByFeed[imp.feedId]).toFloat(),
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

        val weights =
            RankerTrainer.fitLogistic(
                features = features,
                labels = labels,
                sampleWeights = sampleWeights,
                checkCancelled = { coroutineContext.ensureActive() },
            )
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
        priorByFeed: Map<String, FeedPrior>,
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
            val ratio = (dwellMinutes / expectedMinutes).coerceIn(0.05, 3.0)
            features.add(
                RankFeatures.dwellFeatures(
                    feedDwellPriorLog = priorCalculator.dwellPriorLog(priorByFeed[dwell.feedId]),
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
            runCatching { insightsDao.queryArticleContent(chunk) }
                .getOrNull()
                ?.forEach { expected[it.id] = ExpectedReadTime.estimateMs(it.rawDescription) }
        }
        return expected
    }

    private suspend fun fetchArticleDates(articleIds: Set<String>): Map<String, Long> {
        val dates = HashMap<String, Long>()
        for (chunk in articleIds.chunked(CONTENT_CHUNK)) {
            runCatching { insightsDao.queryArticleDatesForArticles(chunk) }
                .getOrNull()
                ?.forEach { dates[it.id] = it.date.time }
        }
        return dates
    }

    companion object {
        private const val TAG = "TrainWorker"
        const val TRAIN_WORK_NAME = "TRAIN_RANKER"

        private const val TRAINING_WINDOW_MS = 180L * 86_400_000L
        private const val MODEL_RETENTION_MS = 30L * 86_400_000L
        private const val CONTENT_CHUNK = 50

        // Activation thresholds: below these, Tier 0 heuristics keep serving.
        private const val MIN_OPEN_SAMPLES = 200
        private const val MIN_POSITIVES = 20
        private const val MAX_OPEN_SAMPLES = 3_000
        private const val MIN_DWELL_SAMPLES = 50
        private const val MAX_DWELL_SAMPLES = 500

        fun enqueuePeriodicWork(workManager: WorkManager) {
            val constraints =
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                TRAIN_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrainWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
