package me.ash.reader.domain.model.article

import androidx.room.Embedded
import androidx.room.Relation
import me.ash.reader.domain.model.feed.Feed

/**
 * An [article] contains a [feed].
 */
data class ArticleWithFeed(
    @field:Embedded
    var article: Article,
    @field:Relation(parentColumn = "feedId", entityColumn = "id")
    var feed: Feed,
)
