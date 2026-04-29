package me.ash.reader.domain.model.feed

import androidx.room.*
import me.ash.reader.domain.model.group.Group

/**
 * TODO: Add class description
 */
@Entity(
    tableName = "feed",
    foreignKeys = [ForeignKey(
        entity = Group::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
)
data class Feed(
    @field:PrimaryKey
    val id: String,
    @field:ColumnInfo
    val name: String,
    @field:ColumnInfo
    val icon: String? = null,
    @field:ColumnInfo
    val url: String,
    @field:ColumnInfo(index = true)
    var groupId: String,
    @field:ColumnInfo(index = true)
    val accountId: Int,
    @field:ColumnInfo
    val isNotification: Boolean = false,
    @field:ColumnInfo
    val isFullContent: Boolean = false,
    @field:ColumnInfo(defaultValue = "0")
    val isBrowser: Boolean = false,
    @field:ColumnInfo(defaultValue = "NULL")
    val sortOrder: Int? = null,
    @Ignore val important: Int = 0
) {
    constructor(
        id: String,
        name: String,
        icon: String?,
        url: String,
        groupId: String,
        accountId: Int,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
        sortOrder: Int?
    ) : this(
        id = id,
        name = name,
        icon = icon,
        url = url,
        groupId = groupId,
        accountId = accountId,
        isNotification = isNotification,
        isFullContent = isFullContent,
        isBrowser = isBrowser,
        sortOrder = sortOrder,
        important = 0
    )
}
