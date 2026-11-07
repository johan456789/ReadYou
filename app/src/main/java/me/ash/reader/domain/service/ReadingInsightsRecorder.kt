package me.ash.reader.domain.service

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.insights.ReadingEvent
import me.ash.reader.domain.model.insights.ReadingEventContext
import me.ash.reader.domain.model.insights.ReadingEventType
import me.ash.reader.domain.model.insights.ReadingSession
import me.ash.reader.domain.model.insights.SessionEntryPoint
import me.ash.reader.domain.repository.InsightsDao
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.LinkOutBridge
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import timber.log.Timber
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Facade for the silent, on-device reading-signal collection feeding the local
 * recommender. Every capture site funnels through here; nothing else in the
 * app writes the insights tables.
 *
 * Guarantees:
 *  - Nothing leaves the device. The only network the recommender ever touches
 *    is the (future, opt-in) embedding-model download.
 *  - Collection can be paused entirely via [PreferencesKey.insightsCollectionEnabled];
 *    every write path checks it.
 *  - Public methods are cheap and non-suspending (fire-and-forget), safe to
 *    call from the UI thread and from composables.
 *
 * Label derivation deliberately happens at training time, not here: this class
 * logs raw signals only (dwell milliseconds, scroll depth, positions, urls).
 */

/** A card rendered in the unread list, seen but not yet counted as exposed. */
data class VisibleCard(
    val articleId: String,
    val feedId: String?,
    val position: Int,
)

@Singleton
class ReadingInsightsRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val insightsDao: InsightsDao,
    private val workManager: WorkManager,
    private val accountService: AccountService,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val eventChannel =
        Channel<ReadingEvent>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Single lock guards all mutable capture state below.
    private val lock = Any()

    private var sessionActive = false
    private var sessionStartedWall: Long = 0
    private var sessionEntryPoint: Int? = null
    private var sweepJob: Job? = null

    // Reader dwell accumulation (foreground-gated).
    private var readerArticleId: String? = null
    private var readerFeedId: String? = null
    private var readerEnterElapsed: Long = 0
    private var readerAccumulatedMs: Long = 0
    private var readerPaused = false
    /**
     * Set when this open was interrupted by leaving the app; every dwell
     * flushed for the open carries it, including the post-resume span — the
     * resumed fragment must not present itself to training as a complete read.
     */
    private var readerCensored = false
    private var readerLastDepth: Float = 0f
    private var lastScrollSentElapsed: Long = 0

    // Impression tracking (per session).
    private val visibleSince = HashMap<String, PendingImpression>()
    private val impressedThisSession = HashSet<String>()

    private data class PendingImpression(
        val sinceElapsed: Long,
        val position: Int,
        val feedId: String?,
    )

    private var lastLinkOutUrl: String? = null
    private var lastLinkOutAtElapsed: Long = 0

    init {
        applicationScope.launch(ioDispatcher) { drainEventLoop() }
        // Register for the openURL choke point. Link opens that carry article
        // context call [onLinkOut] directly; the de-dupe below absorbs the
        // double emission for the same url.
        LinkOutBridge.listener = { url ->
            onLinkOut(url = url, articleId = null, isArticleSelf = false)
        }
    }

    //region Sessions (MainActivity lifecycle)

    fun onAppForeground(entryPoint: Int? = SessionEntryPoint.UNKNOWN) {
        val startedSweep = synchronized(lock) {
            if (sessionActive) return
            sessionActive = true
            sessionStartedWall = System.currentTimeMillis()
            sessionEntryPoint = entryPoint
            if (readerArticleId != null && readerPaused) {
                readerPaused = false
                readerEnterElapsed = SystemClock.elapsedRealtime()
            }
            true
        }
        if (!startedSweep) return
        sweepJob?.cancel()
        sweepJob = applicationScope.launch(ioDispatcher) { impressionSweepLoop() }
    }

    fun onAppBackground() {
        val (startedAt, entryPoint, durationMs) = synchronized(lock) {
            if (!sessionActive) return
            sessionActive = false
            // Checkpoint the open article's dwell before pausing: truncated by
            // leaving the app, not by finishing. If the process dies here the
            // pre-background time is already durable (censored events are
            // discounted at training time); if the user returns, the resumed
            // span flushes as its own uncensored event on exit.
            if (readerArticleId != null && !readerPaused) {
                readerAccumulatedMs += SystemClock.elapsedRealtime() - readerEnterElapsed
                readerPaused = true
                if (readerAccumulatedMs > 0) {
                    postEventLocked(
                        type = ReadingEventType.DWELL,
                        articleId = readerArticleId,
                        feedId = readerFeedId,
                        durationMs = readerAccumulatedMs,
                        depth = readerLastDepth,
                        censored = true,
                    )
                    readerAccumulatedMs = 0
                    readerCensored = true
                }
            }
            visibleSince.clear()
            impressedThisSession.clear()
            Triple(sessionStartedWall, sessionEntryPoint, System.currentTimeMillis() - sessionStartedWall)
        }
        sweepJob?.cancel()
        sweepJob = null
        applicationScope.launch(ioDispatcher) {
            flushChannel()
            val accountId = accountService.currentAccountIdFlow.value
            if (accountId != null && isEnabled()) {
                runCatching {
                    insightsDao.insertSession(
                        ReadingSession(
                            accountId = accountId,
                            startedAt = Date(startedAt),
                            endedAt = Date(),
                            durationMs = durationMs,
                            entryPoint = entryPoint,
                        )
                    )
                }
            }
            // A finished session is new evidence; refresh the For You set.
            if (isEnabled()) RankWorker.enqueueNow(workManager)
        }
    }

    //endregion

    //region Reader (ReadingPage — shared by both surfaces)

    /** Called when an article becomes the visible reader page. Flushes the previous article. */
    fun onEnterReader(articleId: String, feedId: String?, isSwipe: Boolean = false) {
        synchronized(lock) {
            if (readerArticleId == articleId) return
            flushReaderDwellLocked()
            readerArticleId = articleId
            readerFeedId = feedId
            readerEnterElapsed = SystemClock.elapsedRealtime()
            readerAccumulatedMs = 0
            readerPaused = false
            readerCensored = false
            readerLastDepth = 0f
            postEventLocked(
                type = ReadingEventType.OPEN,
                articleId = articleId,
                feedId = feedId,
                context = if (isSwipe) ReadingEventContext.OPEN_SOURCE_SWIPE else null,
            )
        }
    }

    /**
     * OPEN for articles consumed outside the reader (browser-mode feeds):
     * the browser IS the reader for those feeds, so the open counts with
     * full attribution context instead of flooring their open rate.
     */
    fun onOpenExternal(articleId: String, feedId: String?) {
        synchronized(lock) {
            postEventLocked(
                type = ReadingEventType.OPEN,
                articleId = articleId,
                feedId = feedId,
                context = ReadingEventContext.LINK_SELF,
            )
        }
    }

    /** Called when the reader page leaves composition. */
    fun onExitReader(depth: Float?) {
        synchronized(lock) {
            if (readerArticleId == null) return
            depth?.let { readerLastDepth = it }
            flushReaderDwellLocked()
            readerArticleId = null
            readerFeedId = null
            readerPaused = false
        }
    }

    /** Throttled scroll-depth notification; the deepest depth rides on the flushed DWELL. */
    fun onReaderScroll(depth: Float) {
        val elapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            readerLastDepth = maxOf(readerLastDepth, depth)
            if (readerArticleId == null) return
            if (elapsed - lastScrollSentElapsed < SCROLL_MIN_INTERVAL_MS) return
            lastScrollSentElapsed = elapsed
            postEventLocked(
                type = ReadingEventType.SCROLL,
                articleId = readerArticleId,
                feedId = readerFeedId,
                depth = depth,
            )
        }
    }

    //endregion

    //region Marks and stars (AbstractRssRepository)

    fun onToggleStarred(articleId: String, feedId: String?, isStarred: Boolean) {
        synchronized(lock) {
            postEventLocked(
                type = if (isStarred) ReadingEventType.STAR else ReadingEventType.UNSTAR,
                articleId = articleId,
                feedId = feedId,
            )
        }
    }

    fun onMarkReadSingle(articleId: String, feedId: String?, source: Int) {
        synchronized(lock) {
            postEventLocked(
                type = ReadingEventType.MARK_READ_MANUAL,
                articleId = articleId,
                feedId = feedId,
                context = source,
            )
        }
    }

    /**
     * Bulk marks are batch-level evidence, not per-article negatives: they feed
     * feed-level priors only. [affectedCount] is stored in
     * [ReadingEvent.position]; call once per intent site (swipe-batch, scroll
     * sweep, scope clear) — the deferred-diff commit path and op replays must
     * NOT call this or every write would double-count.
     */
    fun onMarkReadBulk(
        scope: Int,
        feedId: String?,
        groupId: String?,
        affectedCount: Int,
        source: Int = 0,
    ) {
        synchronized(lock) {
            if (affectedCount <= 0) return
            postEventLocked(
                type = ReadingEventType.MARK_READ_BULK,
                feedId = feedId,
                context = scope or source,
                position = affectedCount,
                // Bulk-scope payload (e.g. "group:<id>") reuses the url column.
                url = groupId?.let { "group:$it" },
            )
        }
    }

    //endregion

    //region Impressions (FlowPage)

    /**
     * Replaces the current set of visible article cards. Cheap; called from a
     * snapshotFlow collector. A card must stay visible
     * [IMPRESSION_MIN_VISIBLE_MS] to become an IMPRESSION — glances don't count.
     */
    fun noteVisibleArticleIds(visible: List<VisibleCard>) {
        synchronized(lock) {
            if (!sessionActive) return
            val elapsed = SystemClock.elapsedRealtime()
            visibleSince.keys.retainAll(visible.mapTo(HashSet()) { it.articleId })
            for (card in visible) {
                if (impressedThisSession.contains(card.articleId)) continue
                val existing = visibleSince[card.articleId]
                visibleSince[card.articleId] =
                    if (existing == null) {
                        PendingImpression(elapsed, card.position, card.feedId)
                    } else {
                        existing.copy(position = card.position, feedId = card.feedId ?: existing.feedId)
                    }
            }
        }
    }

    private suspend fun impressionSweepLoop() {
        while (true) {
            delay(IMPRESSION_SWEEP_INTERVAL_MS) // cancellation lands here
            val due = ArrayList<ReadingEvent>(8)
            val accountId = accountService.currentAccountIdFlow.value
            val sessionInactive = synchronized(lock) {
                if (!sessionActive) return@synchronized true
                val elapsed = SystemClock.elapsedRealtime()
                val iterator = visibleSince.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (elapsed - entry.value.sinceElapsed >= IMPRESSION_MIN_VISIBLE_MS) {
                        impressedThisSession.add(entry.key)
                        accountId?.let {
                            due.add(
                                ReadingEvent(
                                    accountId = it,
                                    articleId = entry.key,
                                    feedId = entry.value.feedId,
                                    type = ReadingEventType.IMPRESSION,
                                    createdAt = Date(),
                                    position = entry.value.position,
                                )
                            )
                        }
                        iterator.remove()
                    }
                }
                false
            }
            if (sessionInactive) return
            due.forEach { eventChannel.trySend(it) }
        }
    }

    //endregion

    //region Link-out (openURL choke point / explicit call sites)

    /**
     * Records that an outbound link was opened. When [articleId] is null, the
     * link is attributed to the article currently open in the reader (inline
     * body link). The write is async but starts before the caller leaves the
     * app; away time is reconciled on the next foreground ([reconcileLinkOuts]).
     */
    fun onLinkOut(url: String, articleId: String?, isArticleSelf: Boolean) {
        // Only web links; mailto:/tel:/intent: taps are not reading evidence.
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val resolved = synchronized(lock) {
            val elapsed = SystemClock.elapsedRealtime()
            if (url == lastLinkOutUrl && elapsed - lastLinkOutAtElapsed < LINK_OUT_DEDUPE_MS) return
            lastLinkOutUrl = url
            lastLinkOutAtElapsed = elapsed
            val attributedId = articleId ?: readerArticleId ?: return
            attributedId to (if (isArticleSelf) ReadingEventContext.LINK_SELF else ReadingEventContext.LINK_INLINE)
        }
        applicationScope.launch(ioDispatcher) { insertLinkOut(url, resolved.first, resolved.second) }
    }

    private suspend fun insertLinkOut(url: String, articleId: String, contextFlag: Int) {
        if (!isEnabled()) return
        val accountId = accountService.currentAccountIdFlow.value ?: return
        insightsDao.insertEvent(
            ReadingEvent(
                accountId = accountId,
                articleId = articleId,
                type = ReadingEventType.LINK_OUT,
                createdAt = Date(),
                context = contextFlag,
                url = url,
            )
        )
    }

    /**
     * Reconciles pending link-outs on regaining foreground: the raw elapsed
     * away time is stored as-is (no noise/expiry sentinels — training-time
     * policy). Rows never reconciled keep a NULL duration and are treated as
     * unknown at training time. Scans all accounts: a pending row from a
     * non-current account would otherwise stay unresolved forever.
     */
    suspend fun reconcileLinkOuts() {
        if (!isEnabled()) return
        runCatching {
            val now = System.currentTimeMillis()
            val pending =
                insightsDao.queryEventsOfTypeWithoutDuration(
                    type = ReadingEventType.LINK_OUT,
                    since = Date(now - LINK_OUT_MAX_AWAY_MS),
                    limit = 50,
                )
            for (event in pending) {
                insightsDao.updateEventDurationMs(setOf(event.id), now - event.createdAt.time)
            }
        }
    }

    //endregion

    //region Serving helpers

    /** Mean duration of recent clean sessions — the v1 session-budget prior. */
    suspend fun sessionBudgetPriorMs(accountId: Int): Long? =
        insightsDao.queryRecentSessions(accountId, limit = 10)
            .takeIf { it.isNotEmpty() }
            ?.let { sessions -> sessions.map { it.durationMs }.average().toLong() }

    //endregion

    //region Internals

    /** Flush the open reader article's dwell as a DWELL event. Caller holds [lock]. */
    private fun flushReaderDwellLocked() {
        val articleId = readerArticleId ?: return
        val durationMs = readerAccumulatedMs +
            (if (!readerPaused) SystemClock.elapsedRealtime() - readerEnterElapsed else 0L)
        readerAccumulatedMs = 0
        if (durationMs <= 0) return
        // Raw dwell is stored as-is — even very short reads are real signal;
        // bounce thresholds are training-time policy, not logging-time policy.
        postEventLocked(
            type = ReadingEventType.DWELL,
            articleId = articleId,
            feedId = readerFeedId,
            durationMs = durationMs,
            depth = readerLastDepth,
            censored = readerPaused || readerCensored, // truncated by leaving the app, not by finishing
        )
    }

    /** Caller holds [lock]; never blocks — events before an account exists are dropped. */
    private fun postEventLocked(
        type: Int,
        articleId: String? = null,
        feedId: String? = null,
        durationMs: Long? = null,
        depth: Float? = null,
        position: Int? = null,
        context: Int? = null,
        url: String? = null,
        censored: Boolean = false,
    ) {
        val accountId = accountService.currentAccountIdFlow.value ?: return
        eventChannel.trySend(
            ReadingEvent(
                accountId = accountId,
                articleId = articleId,
                feedId = feedId,
                type = type,
                createdAt = Date(),
                durationMs = durationMs,
                depth = depth,
                position = position,
                context = context,
                url = url,
                censored = censored,
            )
        )
    }

    private suspend fun drainEventLoop() {
        val batch = ArrayList<ReadingEvent>(32)
        while (true) {
            batch.add(eventChannel.receive())
            while (batch.size < 128) {
                val next = eventChannel.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            enrichFeedIds(batch)
            if (isEnabled()) {
                try {
                    insightsDao.insertEvents(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to persist %d insight events", batch.size)
                }
            }
            batch.clear()
        }
    }

    /** Best-effort flush of queued events when the app leaves foreground. */
    private suspend fun flushChannel() {
        val batch = ArrayList<ReadingEvent>(32)
        while (true) {
            val next = eventChannel.tryReceive().getOrNull() ?: break
            batch.add(next)
        }
        if (batch.isNotEmpty() && isEnabled()) {
            runCatching { enrichFeedIds(batch); insightsDao.insertEvents(batch) }
        }
    }

    /**
     * Backfills feedId for events logged before the reader committed the
     * article (pager lag) — feed-level priors would otherwise miss them.
     */
    private suspend fun enrichFeedIds(batch: MutableList<ReadingEvent>) {
        val missing =
            batch.filter { it.feedId == null && it.articleId != null }.mapNotNull { it.articleId }
        if (missing.isEmpty()) return
        val byId =
            runCatching { insightsDao.queryFeedIdsForArticles(missing) }
                .getOrNull()
                ?.associate { it.id to it.feedId }
                ?: return
        for (index in batch.indices) {
            val event = batch[index]
            if (event.feedId == null) {
                byId[event.articleId]?.let { batch[index] = event.copy(feedId = it) }
            }
        }
    }

    private suspend fun isEnabled(): Boolean =
        runCatching {
            context.dataStore.data.first().let {
                it[PreferencesKey.booleanKey(PreferencesKey.insightsCollectionEnabled)] ?: true
            }
        }.getOrDefault(true)

    //endregion

    private companion object {
        const val TAG = "ReadingInsightsRecorder"
        const val IMPRESSION_MIN_VISIBLE_MS = 1_000L
        const val IMPRESSION_SWEEP_INTERVAL_MS = 500L
        const val SCROLL_MIN_INTERVAL_MS = 1_000L
        const val LINK_OUT_DEDUPE_MS = 2_000L
        const val LINK_OUT_MAX_AWAY_MS = 60 * 60_000L
    }
}
