package me.ash.reader.domain.model.article

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "pending_read_state_op",
    indices = [Index("accountId"), Index("feedId")],
)
data class PendingReadStateOp(
    @PrimaryKey
    val articleId: String,
    @ColumnInfo
    val accountId: Int,
    @ColumnInfo
    val feedId: String,
    @ColumnInfo
    val isUnread: Boolean,
    @ColumnInfo
    val updatedAt: Date = Date(),
    @ColumnInfo(defaultValue = "0")
    val localCommitted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val remoteSynced: Boolean = false,
) {
    val isRead: Boolean
        get() = !isUnread
}
