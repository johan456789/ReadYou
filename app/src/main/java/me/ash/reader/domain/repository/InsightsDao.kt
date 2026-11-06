package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.insights.ArticleRank
import me.ash.reader.domain.model.insights.FeedPrior
import me.ash.reader.domain.model.insights.RankerModel
import me.ash.reader.domain.model.insights.ReadingEvent
import me.ash.reader.domain.model.insights.ReadingSession
import me.ash.reader.domain.model.insights.UnreadArticleMeta
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

    @Query(
        """
        SELECT * FROM reading_event
        WHERE accountId = :accountId
        AND type = :type
        AND durationMs IS NULL
        AND createdAt >= :since
        LIMIT :limit
        """
    )
    suspend fun queryEventsOfTypeWithoutDuration(
        accountId: Int,
        type: Int,
        since: Date,
        limit: Int,
    ): List<ReadingEvent>

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
        AND durationMs IS NOT NULL
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

    @Query(
        """
        SELECT id, feedId, date, rawDescription FROM article
        WHERE accountId = :accountId
        AND isRead = 0
        """
    )
    suspend fun queryUnreadArticleMeta(accountId: Int): List<UnreadArticleMeta>

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

    //endregion
}
