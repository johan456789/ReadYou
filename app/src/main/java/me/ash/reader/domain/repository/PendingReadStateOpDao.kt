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
        WHERE accountId = :accountId
        """
    )
    suspend fun queryByAccountId(accountId: Int): List<PendingReadStateOp>

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
