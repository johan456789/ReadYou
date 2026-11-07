package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.insights.ArticleContent
import me.ash.reader.domain.model.insights.ArticleFeedRef
import me.ash.reader.domain.model.insights.ArticleRank
import me.ash.reader.domain.model.insights.FeedPrior
import me.ash.reader.domain.model.insights.RankerModel
import me.ash.reader.domain.model.insights.ReadingEvent
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

    @Query("DELETE FROM article_vector WHERE accountId = :accountId")
    suspend fun deleteVectorsForAccount(accountId: Int)

    //endregion
}
