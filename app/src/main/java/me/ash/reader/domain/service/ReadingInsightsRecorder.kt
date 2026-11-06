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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Resolved once per session; -1 until known. */
    private var cachedAccountId: Int = -1

    // Reader dwell accumulation (foreground-gated).
    private var readerArticleId: String? = null
    private var readerFeedId: String? = null
    private var readerEnterElapsed: Long = 0
    private var readerAccumulatedMs: Long = 0
    private var readerPaused = false
    private var readerLastDepth: Float = 0f
    private var lastScrollSentElapsed: Long = 0

    // Impression tracking (per session).
    private val visibleSince = HashMap<String, Pair<Long, Int>>() // articleId -> (since, position)
    private val impressedThisSession = HashSet<String>()
    private val openedThisSession = HashSet<String>()

    private var lastLinkOutUrl: String? = null
    private var lastLinkOutAtElapsed: Long = 0

    init {
        applicationScope.launch(ioDispatcher) { drainEventLoop() }
        // Register for the openURL choke point. Link opens that carry article
        // context call [onLinkOut] directly; the de-dupe below absorbs the
        // double emission for the same url.
        LinkOutBridge.listener = { url ->
            onLinkOut(url = url, articleId = null, isArticleSelf = false, durable = true)
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
        applicationScope.launch(ioDispatcher) {
            runCatching { cachedAccountId = accountService.getCurrentAccountId() }
            sweepJob?.cancel()
            sweepJob = applicationScope.launch(ioDispatcher) { impressionSweepLoop() }
        }
    }

    fun onAppBackground() {
        val (startedAt, entryPoint, durationMs) = synchronized(lock) {
            if (!sessionActive) return
            sessionActive = false
            if (readerArticleId != null && !readerPaused) {
                readerAccumulatedMs += SystemClock.elapsedRealtime() - readerEnterElapsed
                readerPaused = true
            }
            visibleSince.clear()
            impressedThisSession.clear()
            openedThisSession.clear()
            Triple(sessionStartedWall, sessionEntryPoint, System.currentTimeMillis() - sessionStartedWall)
        }
        sweepJob?.cancel()
        sweepJob = null
        applicationScope.launch(ioDispatcher) {
            if (isEnabled()) {
                runCatching {
                    insightsDao.insertSession(
                        ReadingSession(
                            accountId = accountService.getCurrentAccountId(),
                            startedAt = Date(startedAt),
                            endedAt = Date(),
                            durationMs = durationMs,
                            entryPoint = entryPoint,
                        )
                    )
                }
            }
            // A finished session is new evidence; refresh the For You set.
            RankWorker.enqueueNow(workManager)
        }
    }

    //endregion

    //region Reader (ReadingPage — shared by both surfaces)

    /** Called when an article becomes the visible reader page. Flushes the previous article. */
    fun onEnterReader(articleId: String, feedId: String?) {
        synchronized(lock) {
            if (readerArticleId == articleId) return
            flushReaderDwellLocked()
            readerArticleId = articleId
            readerFeedId = feedId
            readerEnterElapsed = SystemClock.elapsedRealtime()
            readerAccumulatedMs = 0
            readerPaused = false
            readerLastDepth = 0f
            if (openedThisSession.add(articleId)) {
                postEventLocked(
                    type = ReadingEventType.OPEN,
                    articleId = articleId,
                    feedId = feedId,
                )
            }
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

    /** Throttled scroll-depth notification; the latest depth rides on the flushed DWELL. */
    fun onReaderScroll(depth: Float) {
        val elapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            readerLastDepth = depth
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

    fun onMarkReadSingle(articleId: String, feedId: String?) {
        synchronized(lock) {
            postEventLocked(
                type = ReadingEventType.MARK_READ_MANUAL,
                articleId = articleId,
                feedId = feedId,
                context = ReadingEventContext.MARK_SOURCE_LIST,
            )
        }
    }

    /**
     * Bulk marks are batch-level evidence, not per-article negatives: they feed
     * feed-level priors only. [affectedCount] is stored in [ReadingEvent.position].
     */
    fun onMarkReadBulk(scope: Int, feedId: String?, groupId: String?, affectedCount: Int) {
        synchronized(lock) {
            postEventLocked(
                type = ReadingEventType.MARK_READ_BULK,
                feedId = feedId,
                context = scope,
                position = affectedCount,
            )
        }
    }

    //endregion

    //region Impressions (FlowPage)

    /**
     * Replaces the current set of visible article ids (id to list position).
     * Cheap; called from a snapshotFlow collector. A card must stay visible
     * [IMPRESSION_MIN_VISIBLE_MS] to become an IMPRESSION — glances don't count.
     */
    fun noteVisibleArticleIds(visible: List<Pair<String, Int>>) {
        synchronized(lock) {
            if (!sessionActive) return
            val elapsed = SystemClock.elapsedRealtime()
            visibleSince.keys.retainAll(visible.mapTo(HashSet()) { it.first })
            for ((id, position) in visible) {
                if (impressedThisSession.contains(id)) continue
                visibleSince[id] = visibleSince[id]?.first?.let { it to position } ?: (elapsed to position)
            }
        }
    }

    private suspend fun impressionSweepLoop() {
        while (true) {
            delay(IMPRESSION_SWEEP_INTERVAL_MS) // cancellation lands here
            val due = ArrayList<ReadingEvent>(8)
            val sessionInactive = synchronized(lock) {
                if (!sessionActive) return@synchronized true
                val elapsed = SystemClock.elapsedRealtime()
                val iterator = visibleSince.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (elapsed - entry.value.first >= IMPRESSION_MIN_VISIBLE_MS) {
                        impressedThisSession.add(entry.key)
                        due.add(
                            ReadingEvent(
                                accountId = cachedAccountId,
                                articleId = entry.key,
                                type = ReadingEventType.IMPRESSION,
                                createdAt = Date(),
                                position = entry.value.second,
                            )
                        )
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
     * body link). [durable] persists synchronously — callers are about to
     * leave the app and the event must survive process death; the away time is
     * reconciled on the next foreground ([reconcileLinkOuts]).
     */
    fun onLinkOut(url: String, articleId: String?, isArticleSelf: Boolean, durable: Boolean) {
        val resolved = synchronized(lock) {
            val elapsed = SystemClock.elapsedRealtime()
            if (url == lastLinkOutUrl && elapsed - lastLinkOutAtElapsed < LINK_OUT_DEDUPE_MS) return
            lastLinkOutUrl = url
            lastLinkOutAtElapsed = elapsed
            val attributedId = articleId ?: readerArticleId ?: return
            attributedId to (if (isArticleSelf) ReadingEventContext.LINK_SELF else ReadingEventContext.LINK_INLINE)
        }
        if (durable) {
            runBlocking {
                withContext(ioDispatcher) { insertLinkOut(url, resolved.first, resolved.second) }
            }
        } else {
            applicationScope.launch(ioDispatcher) { insertLinkOut(url, resolved.first, resolved.second) }
        }
    }

    private suspend fun insertLinkOut(url: String, articleId: String, contextFlag: Int) {
        if (!isEnabled()) return
        val accountId = accountService.getCurrentAccountId()
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
     * Reconciles pending link-outs on regaining foreground: away time becomes
     * the event's durationMs. Elapsed < 3s is noise (0), > 1h expired (-1);
     * both are dropped at training time.
     */
    suspend fun reconcileLinkOuts() {
        if (!isEnabled()) return
        runCatching {
            val accountId = accountService.getCurrentAccountId()
            val now = System.currentTimeMillis()
            val pending =
                insightsDao.queryEventsOfTypeWithoutDuration(
                    accountId = accountId,
                    type = ReadingEventType.LINK_OUT,
                    since = Date(now - LINK_OUT_MAX_AWAY_MS),
                    limit = 50,
                )
            for (event in pending) {
                val elapsed = now - event.createdAt.time
                val durationMs =
                    when {
                        elapsed < LINK_OUT_MIN_AWAY_MS -> 0L
                        elapsed > LINK_OUT_MAX_AWAY_MS -> -1L
                        else -> elapsed
                    }
                insightsDao.updateEventDurationMs(setOf(event.id), durationMs)
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
        if (durationMs < DWELL_MIN_MS) return // open-and-instant-leave noise
        postEventLocked(
            type = ReadingEventType.DWELL,
            articleId = articleId,
            feedId = readerFeedId,
            durationMs = durationMs,
            depth = readerLastDepth,
            censored = readerPaused, // truncated by leaving the app, not by finishing
        )
    }

    /** Caller holds [lock]; resolves the account id lazily. */
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
        if (cachedAccountId < 0) {
            cachedAccountId = runCatching { accountService.getCurrentAccountId() }.getOrDefault(-1)
        }
        if (cachedAccountId < 0) return
        eventChannel.trySend(
            ReadingEvent(
                accountId = cachedAccountId,
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
            if (isEnabled()) {
                runCatching { insightsDao.insertEvents(batch) }
            }
            batch.clear()
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
        const val IMPRESSION_MIN_VISIBLE_MS = 1_000L
        const val IMPRESSION_SWEEP_INTERVAL_MS = 500L
        const val DWELL_MIN_MS = 500L
        const val SCROLL_MIN_INTERVAL_MS = 1_000L
        const val LINK_OUT_DEDUPE_MS = 2_000L
        const val LINK_OUT_MIN_AWAY_MS = 3_000L
        const val LINK_OUT_MAX_AWAY_MS = 60 * 60_000L
    }
}
