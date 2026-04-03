package me.ash.reader.domain.model.group

import androidx.room.Embedded
import androidx.room.Relation
import me.ash.reader.domain.model.feed.Feed

/**
 * A [group] contains many [feeds].
 */
data class GroupWithFeed(
    @field:Embedded
    val group: Group,
    @field:Relation(parentColumn = "id", entityColumn = "groupId")
    val feeds: MutableList<Feed>,
)
