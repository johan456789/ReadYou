package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.ash.reader.domain.model.article.PendingReadStateOp

@Dao
interface PendingReadStateOpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(op: PendingReadStateOp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(ops: List<PendingReadStateOp>)

    @Query(
        """
        SELECT * FROM pending_read_state_op
        WHERE articleId IN (:articleIds)
        """
    )
    suspend fun queryByArticleIds(articleIds: Set<String>): List<PendingReadStateOp>

    @Query(
        """
        SELECT * FROM pending_read_state_op
        WHERE accountId = :accountId
        """
    )
    suspend fun queryByAccountId(accountId: Int): List<PendingReadStateOp>

    @Query(
        """
        SELECT * FROM pending_read_state_op
        WHERE accountId = :accountId
        AND localCommitted = 0
        """
    )
    suspend fun queryLocalPending(accountId: Int): List<PendingReadStateOp>

    @Query(
        """
        SELECT * FROM pending_read_state_op
        WHERE accountId = :accountId
        AND remoteSynced = 0
        """
    )
    suspend fun queryRemotePending(accountId: Int): List<PendingReadStateOp>

    @Query(
        """
        UPDATE pending_read_state_op
        SET localCommitted = 1
        WHERE articleId IN (:articleIds)
        AND isUnread = :isUnread
        """
    )
    suspend fun markLocalCommitted(articleIds: Set<String>, isUnread: Boolean)

    @Query(
        """
        UPDATE pending_read_state_op
        SET remoteSynced = 1
        WHERE articleId IN (:articleIds)
        AND isUnread = :isUnread
        """
    )
    suspend fun markRemoteSynced(articleIds: Set<String>, isUnread: Boolean)

    @Query(
        """
        DELETE FROM pending_read_state_op
        WHERE localCommitted = 1
        AND remoteSynced = 1
        """
    )
    suspend fun deleteCompleted()

    @Query(
        """
        DELETE FROM pending_read_state_op
        WHERE articleId IN (:articleIds)
        AND isUnread = :isUnread
        """
    )
    suspend fun deleteByArticleIdsAndUnreadState(articleIds: Set<String>, isUnread: Boolean)

    @Query(
        """
        DELETE FROM pending_read_state_op
        WHERE accountId = :accountId
        """
    )
    suspend fun deleteByAccountId(accountId: Int)
}
