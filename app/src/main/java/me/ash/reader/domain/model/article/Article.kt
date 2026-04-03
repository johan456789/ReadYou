package me.ash.reader.domain.model.article

import androidx.room.*
import me.ash.reader.domain.model.feed.Feed
import java.util.*

/**
 * TODO: Add class description
 */
@Entity(
    tableName = "article",
    foreignKeys = [ForeignKey(
        entity = Feed::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )]
)
data class Article(
    @field:PrimaryKey
    var id: String,
    @field:ColumnInfo
    var date: Date,
    @field:ColumnInfo
    var title: String,
    @field:ColumnInfo
    var author: String? = null,
    @field:ColumnInfo
    var rawDescription: String,
    @field:ColumnInfo
    var shortDescription: String,
    @field:ColumnInfo
    @Deprecated("fullContent is the same as rawDescription")
    var fullContent: String? = null,
    @field:ColumnInfo
    var img: String? = null,
    @field:ColumnInfo
    var link: String,
    @field:ColumnInfo(index = true)
    var feedId: String,
    @field:ColumnInfo(index = true)
    var accountId: Int,
    @field:ColumnInfo
    var isUnread: Boolean = true,
    @field:ColumnInfo
    var isStarred: Boolean = false,
    @field:ColumnInfo
    var isReadLater: Boolean = false,
    @field:ColumnInfo
    var updateAt: Date? = null,
) {

    @Ignore
    var dateString: String? = null
}
