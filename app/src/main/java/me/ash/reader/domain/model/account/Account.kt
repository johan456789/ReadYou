package me.ash.reader.domain.model.account

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.infrastructure.preference.*
import java.util.*

/**
 * In the application, at least one account exists and different accounts
 * can have the same feeds and articles.
 */
@Entity(tableName = "account")
data class Account(
    @field:PrimaryKey(autoGenerate = true)
    val id: Int? = null,
    @field:ColumnInfo
    val name: String,
    @field:ColumnInfo
    val type: AccountType,
    @field:ColumnInfo
    val updateAt: Date? = null,
    @field:ColumnInfo
    val lastArticleId: String? = null,
    @field:ColumnInfo(defaultValue = "30")
    val syncInterval: SyncIntervalPreference = SyncIntervalPreference.default,
    @field:ColumnInfo(defaultValue = "0")
    val syncOnStart: SyncOnStartPreference = SyncOnStartPreference.default,
    @field:ColumnInfo(defaultValue = "0")
    val syncOnlyOnWiFi: SyncOnlyOnWiFiPreference = SyncOnlyOnWiFiPreference.default,
    @field:ColumnInfo(defaultValue = "0")
    val syncOnlyWhenCharging: SyncOnlyWhenChargingPreference = SyncOnlyWhenChargingPreference.default,
    @field:ColumnInfo(defaultValue = "2592000000")
    val keepArchived: KeepArchivedPreference = KeepArchivedPreference.default,
    @field:ColumnInfo(defaultValue = "")
    val syncBlockList: SyncBlockList = SyncBlockListPreference.default,
    @field:ColumnInfo(defaultValue = DESUtils.empty)
    val securityKey: String? = DESUtils.empty,
)
