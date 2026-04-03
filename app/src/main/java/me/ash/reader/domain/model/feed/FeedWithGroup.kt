package me.ash.reader.domain.model.feed

import androidx.room.Embedded
import androidx.room.Relation
import me.ash.reader.domain.model.group.Group

/**
 * A [feed] contains a [group].
 */
data class FeedWithGroup(
    @field:Embedded
    var feed: Feed,
    @field:Relation(parentColumn = "groupId", entityColumn = "id")
    var group: Group,
)
