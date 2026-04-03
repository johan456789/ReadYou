package me.ash.reader.domain.model.article

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.ash.reader.domain.model.feed.Feed

@Entity(
    tableName = "archived_article",
    indices = [Index(value = ["feedId"])],
    foreignKeys = [ForeignKey(
        entity = Feed::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )]
)
data class ArchivedArticle(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val feedId: String,
    val link: String
)
