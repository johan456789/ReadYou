package me.ash.reader.domain.model.group

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * TODO: Add class description
 */
@Entity(tableName = "group")
data class Group(
    @field:PrimaryKey
    val id: String,
    @field:ColumnInfo
    val name: String,
    @field:ColumnInfo(index = true)
    val accountId: Int,
)
