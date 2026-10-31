package me.ash.reader.domain.model.article

import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/**
 * Data class for article metadata processing only.
 */

data class ArticleMeta(
    @field:PrimaryKey
    var id: String,
    @field:ColumnInfo(name = "isUnread")
    var isRead: Boolean = false,
    @field:ColumnInfo
    var isStarred: Boolean = false,
)
