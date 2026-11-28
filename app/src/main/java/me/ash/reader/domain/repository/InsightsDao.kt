package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.insights.ArticleContent
import me.ash.reader.domain.model.insights.ArticleDateRef
import me.ash.reader.domain.model.insights.ArticleFeedRef
import me.ash.reader.domain.model.insights.ArticleRank
import me.ash.reader.domain.model.insights.ArticleVector
import me.ash.reader.domain.model.insights.FeedPrior
import me.ash.reader.domain.model.insights.RankerModel
import me.ash.reader.domain.model.insights.ReadingEvent
import me.ash.reader.domain.model.insights.InsightsExportArticle
import me.ash.reader.domain.model.insights.ReadingSession
import me.ash.reader.domain.model.insights.UnreadArticleShallow
import java.util.Date

@Dao
interface InsightsDao {

    //region Event log

    @Insert
    suspend fun insertEvent(event: ReadingEvent): Long

    @Insert
    suspend fun insertEvents(events: List<ReadingEvent>)

    @Query(
        """
        SELECT * FROM reading_event
        WHERE accountId = :accountId
        AND createdAt >= :since
        AND feedId IS NOT NULL
        """
    )
    suspend fun queryEventsSince(accountId: Int, since: Date): List<ReadingEvent>

    /** Engagement events only: what feed-prior computation needs, skipping bulky SCROLL/LINK rows. */
    @Query(
        """
        SELECT * FROM reading_event
        WHERE accountId = :accountId
        AND createdAt >= :since
        AND feedId IS NOT NULL
        AND type IN (:types)
        """
    )
    suspend fun queryEngagementEventsSince(
        accountId: Int,
        since: Date,
        types: List<Int>,
    ): List<ReadingEvent>

    /** New-engagement-event count since [since]: gates burst retraining. */
    @Query(
        """
        SELECT COUNT(*) FROM reading_event
        WHERE accountId = :accountId
        AND createdAt > :since
        AND type IN (:types)
        """
    )
    suspend fun countEngagementEventsSince(
        accountId: Int,
        since: Date,
        types: List<Int>,
    ): Int

    @Query("DELETE FROM reading_event WHERE createdAt < :cutoff")
    suspend fun deleteEventsBefore(cutoff: Date): Int

    @Query(
        """
        SELECT * FROM reading_event
        WHERE type = :type
        AND durationMs IS NULL
        AND createdAt >= :since
        LIMIT :limit
        """
    )
    suspend fun queryEventsOfTypeWithoutDuration(
        type: Int,
        since: Date,
        limit: Int,
    ): List<ReadingEvent>

    /** feedId backfill for events logged before the article fully committed. */
    @Query(
        """
        SELECT id, feedId FROM article
        WHERE id IN (:ids)
        """
    )
    suspend fun queryFeedIdsForArticles(ids: List<String>): List<ArticleFeedRef>

    /** Publish dates for freshness features at training time. */
    @Query(
        """
        SELECT id, date FROM article
        WHERE id IN (:ids)
        """
    )
    suspend fun queryArticleDatesForArticles(ids: List<String>): List<ArticleDateRef>

    @Query("UPDATE reading_event SET durationMs = :durationMs WHERE id IN (:ids)")
    suspend fun updateEventDurationMs(ids: Set<Long>, durationMs: Long)

    //endregion

    //region Sessions

    @Insert
    suspend fun insertSession(session: ReadingSession): Long

    @Query(
        """
        SELECT * FROM reading_session
        WHERE accountId = :accountId
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    suspend fun queryRecentSessions(accountId: Int, limit: Int): List<ReadingSession>

    //endregion

    //region Serving contract (materialized ranks)

    @Upsert
    suspend fun upsertRanks(ranks: List<ArticleRank>)

    /**
     * Atomic rank-pass write: resets the account's admission flags first, then
     * applies the new pass. Without the reset, rows that drop out of the
     * candidate set keep a stale admitted=1, and concurrent rank passes can
     * leave a mixed admitted set (over quota, double exploration slots).
     */
    @Transaction
    suspend fun replaceRanks(accountId: Int, ranks: List<ArticleRank>) {
        resetAdmittedFlags(accountId)
        upsertRanks(ranks)
    }

    @Query("UPDATE article_rank SET admitted = 0, exploration = 0 WHERE accountId = :accountId")
    suspend fun resetAdmittedFlags(accountId: Int)

    @Query("DELETE FROM article_rank WHERE accountId = :accountId AND computedAt < :cutoff")
    suspend fun deleteStaleRanks(accountId: Int, cutoff: Date): Int

    @Query(
        """
        SELECT r.* FROM article_rank AS r
        JOIN article AS a ON a.id = r.articleId
        WHERE r.accountId = :accountId
        AND r.admitted = 1
        AND a.isRead = 0
        ORDER BY r.score DESC
        LIMIT :limit
        """
    )
    fun observeForYou(accountId: Int, limit: Int): Flow<List<ArticleRank>>

    @Query(
        """
        SELECT r.* FROM article_rank AS r
        JOIN article AS a ON a.id = r.articleId
        WHERE r.accountId = :accountId
        AND a.isRead = 0
        ORDER BY r.score DESC
        LIMIT :limit
        """
    )
    fun observeRankedUnread(accountId: Int, limit: Int): Flow<List<ArticleRank>>

    /**
     * Bounded shallow scan of unread articles inside the scoring window. Loads
     * lengths, never content — full raw descriptions can exceed the cursor
     * window on full-text feeds and would kill ranking with a hidden
     * CursorWindow exception.
     */
    @Query(
        """
        SELECT id, feedId, date, length(rawDescription) AS contentLength FROM article
        WHERE accountId = :accountId
        AND isRead = 0
        AND date >= :since
        ORDER BY date DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun queryUnreadArticleShallow(
        accountId: Int,
        since: Date,
        limit: Int,
        offset: Int,
    ): List<UnreadArticleShallow>

    /** Content for the refine pass; call in small chunks (see RankWorker). */
    @Query(
        """
        SELECT id, rawDescription FROM article
        WHERE id IN (:ids)
        """
    )
    suspend fun queryArticleContent(ids: List<String>): List<ArticleContent>

    //endregion

    //region Insights export

    @Query("SELECT * FROM reading_event WHERE accountId = :accountId ORDER BY createdAt")
    suspend fun queryAllEventsForExport(accountId: Int): List<ReadingEvent>

    @Query("SELECT * FROM reading_session WHERE accountId = :accountId ORDER BY startedAt")
    suspend fun queryAllSessionsForExport(accountId: Int): List<ReadingSession>

    @Query("SELECT * FROM article_rank WHERE accountId = :accountId")
    suspend fun queryAllRanksForExport(accountId: Int): List<ArticleRank>

    /** Vectors for the given articles under one model — scoring + centroid input. */
    @Query(
        """
        SELECT * FROM article_vector
        WHERE accountId = :accountId
        AND modelId = :modelId
        AND articleId IN (:ids)
        """
    )
    suspend fun queryVectorsForArticles(
        accountId: Int,
        modelId: String,
        ids: List<String>,
    ): List<ArticleVector>

    @Upsert
    suspend fun upsertVectors(vectors: List<ArticleVector>)

    /**
     * Vectors of articles the user engaged with (opened or meaningfully dwelled)
     * under one model — the engagement centroid source for content similarity.
     */
    @Query(
        """
        SELECT av.* FROM article_vector AS av
        JOIN reading_event AS re ON re.articleId = av.articleId
        WHERE av.accountId = :accountId
        AND av.modelId = :modelId
        AND re.accountId = :accountId
        AND re.type IN (1, 2)
        AND re.type = CASE WHEN re.type = 2 THEN CASE WHEN re.censored = 1 THEN 99 ELSE 2 END ELSE 1 END
        GROUP BY av.articleId
        """
    )
    suspend fun queryEngagedVectors(accountId: Int, modelId: String): List<ArticleVector>

    /**
     * Articles referenced by events or ranks — the minimum the offline replay
     * evaluation needs to recompute features (includes rawDescription for
     * content-based expected-read-time).
     */
    @Query(
        """
        SELECT DISTINCT a.id, a.feedId, a.date, a.title, a.rawDescription
        FROM article AS a
        WHERE a.accountId = :accountId
        AND (
            a.id IN (SELECT DISTINCT articleId FROM reading_event WHERE accountId = :accountId)
            OR a.id IN (SELECT DISTINCT articleId FROM article_rank WHERE accountId = :accountId)
        )
        """
    )
    suspend fun queryArticlesForInsightsExport(accountId: Int): List<InsightsExportArticle>

    //endregion

    //region Feed priors

    @Query("SELECT * FROM feed_prior WHERE accountId = :accountId")
    suspend fun queryFeedPriors(accountId: Int): List<FeedPrior>

    @Upsert
    suspend fun upsertFeedPriors(priors: List<FeedPrior>)

    //endregion

    //region Trained models

    @Query(
        """
        SELECT * FROM ranker_model
        WHERE accountId = :accountId AND stage = :stage
        ORDER BY trainedAt DESC
        LIMIT 1
        """
    )
    suspend fun queryLatestModel(accountId: Int, stage: Int): RankerModel?

    @Upsert
    suspend fun upsertModel(model: RankerModel)

    /** Per-account purge on account deletion — insights are private data. */
    @Transaction
    suspend fun deleteAllForAccount(accountId: Int) {
        deleteEventsForAccount(accountId)
        deleteSessionsForAccount(accountId)
        deleteRanksForAccount(accountId)
        deletePriorsForAccount(accountId)
        deleteModelsForAccount(accountId)
        deleteVectorsForAccount(accountId)
    }

    @Query("DELETE FROM reading_event WHERE accountId = :accountId")
    suspend fun deleteEventsForAccount(accountId: Int)

    @Query("DELETE FROM reading_session WHERE accountId = :accountId")
    suspend fun deleteSessionsForAccount(accountId: Int)

    @Query("DELETE FROM article_rank WHERE accountId = :accountId")
    suspend fun deleteRanksForAccount(accountId: Int)

    @Query("DELETE FROM feed_prior WHERE accountId = :accountId")
    suspend fun deletePriorsForAccount(accountId: Int)

    @Query("DELETE FROM ranker_model WHERE accountId = :accountId")
    suspend fun deleteModelsForAccount(accountId: Int)

    /** Prunes old fits but always keeps each stage's newest model — otherwise
     * a stage that stops passing its thresholds would silently fall back to
     * Tier 0 serving after the retention window. */
    @Query(
        """
        DELETE FROM ranker_model
        WHERE accountId = :accountId
        AND trainedAt < :cutoff
        AND id NOT IN (
            SELECT MAX(id) FROM ranker_model WHERE accountId = :accountId GROUP BY stage
        )
        """
    )
    suspend fun deleteModelsBefore(accountId: Int, cutoff: Date)

    @Query("DELETE FROM article_vector WHERE accountId = :accountId")
    suspend fun deleteVectorsForAccount(accountId: Int)

    //endregion
}
