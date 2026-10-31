package me.ash.reader.infrastructure.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.ash.reader.domain.model.account.*
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.PendingReadStateOp
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.PendingReadStateOpDao
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.ext.toInt
import java.util.*

@Database(
    entities = [
        Account::class,
        Feed::class,
        Article::class,
        Group::class,
        ArchivedArticle::class,
        PendingReadStateOp::class,
    ],
    version = 13,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ]
)
@TypeConverters(
    AndroidDatabase.DateConverters::class,
    AccountTypeConverters::class,
    SyncIntervalConverters::class,
    SyncOnStartConverters::class,
    SyncOnlyOnWiFiConverters::class,
    SyncOnlyWhenChargingConverters::class,
    KeepArchivedConverters::class,
    SyncBlockListConverters::class,
)
abstract class AndroidDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun groupDao(): GroupDao
    abstract fun pendingReadStateOpDao(): PendingReadStateOpDao

    companion object {

        private var instance: AndroidDatabase? = null

        fun getInstance(context: Context): AndroidDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndroidDatabase::class.java,
                    "Reader"
                ).addMigrations(*allMigrations).build().also {
                    instance = it
                }
            }
        }
    }

    class DateConverters {

        @TypeConverter
        fun toDate(dateLong: Long?): Date? {
            return dateLong?.let { Date(it) }
        }

        @TypeConverter
        fun fromDate(date: Date?): Long? {
            return date?.time
        }
    }
}

val allMigrations = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
)

@Suppress("ClassName")
object MIGRATION_1_2 : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE article ADD COLUMN img TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_2_3 : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE article ADD COLUMN updateAt INTEGER DEFAULT ${System.currentTimeMillis()}
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncInterval INTEGER NOT NULL DEFAULT ${SyncIntervalPreference.default.value}
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnStart INTEGER NOT NULL DEFAULT ${SyncOnStartPreference.default.value.toInt()}
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyOnWiFi INTEGER NOT NULL DEFAULT ${SyncOnlyOnWiFiPreference.default.value.toInt()}
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyWhenCharging INTEGER NOT NULL DEFAULT ${SyncOnlyWhenChargingPreference.default.value.toInt()}
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN keepArchived INTEGER NOT NULL DEFAULT 2592000000
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncBlockList TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_3_4 : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN securityKey TEXT DEFAULT '${DESUtils.empty}'
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_4_5 : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE account ADD COLUMN lastArticleId TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_10_11 : Migration(10, 11) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE pending_read_state_op ADD COLUMN localCommitted INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE pending_read_state_op ADD COLUMN remoteSynced INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE pending_read_state_op
            SET remoteSynced = 1
            WHERE accountId IN (
                SELECT id FROM account
                WHERE type = ${AccountType.Local.id}
            )
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_11_12 : Migration(11, 12) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `account_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `name` TEXT NOT NULL,
                `type` INTEGER NOT NULL,
                `updateAt` INTEGER,
                `lastArticleId` TEXT,
                `syncInterval` INTEGER NOT NULL DEFAULT 30,
                `syncOnStart` INTEGER NOT NULL DEFAULT 0,
                `syncOnlyOnWiFi` INTEGER NOT NULL DEFAULT 0,
                `syncOnlyWhenCharging` INTEGER NOT NULL DEFAULT 0,
                `keepArchived` INTEGER NOT NULL DEFAULT 0,
                `syncBlockList` TEXT NOT NULL DEFAULT '',
                `securityKey` TEXT DEFAULT '${DESUtils.empty}'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `account_new` (
                `id`,
                `name`,
                `type`,
                `updateAt`,
                `lastArticleId`,
                `syncInterval`,
                `syncOnStart`,
                `syncOnlyOnWiFi`,
                `syncOnlyWhenCharging`,
                `keepArchived`,
                `syncBlockList`,
                `securityKey`
            )
            SELECT
                `id`,
                `name`,
                `type`,
                `updateAt`,
                `lastArticleId`,
                `syncInterval`,
                `syncOnStart`,
                `syncOnlyOnWiFi`,
                `syncOnlyWhenCharging`,
                `keepArchived`,
                `syncBlockList`,
                `securityKey`
            FROM `account`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `account`")
        db.execSQL("ALTER TABLE `account_new` RENAME TO `account`")
    }
}

@Suppress("ClassName")
object MIGRATION_12_13 : Migration(12, 13) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // The Kotlin isRead field is the semantic inverse of the legacy isUnread
        // column, so the stored values must be flipped while renaming the column.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `article_new` (
                `id` TEXT NOT NULL,
                `date` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `author` TEXT,
                `rawDescription` TEXT NOT NULL,
                `shortDescription` TEXT NOT NULL,
                `fullContent` TEXT,
                `img` TEXT,
                `link` TEXT NOT NULL,
                `feedId` TEXT NOT NULL,
                `accountId` INTEGER NOT NULL,
                `isRead` INTEGER NOT NULL,
                `isStarred` INTEGER NOT NULL,
                `isReadLater` INTEGER NOT NULL,
                `updateAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`feedId`) REFERENCES `feed`(`id`)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `article_new` (
                `id`, `date`, `title`, `author`, `rawDescription`, `shortDescription`,
                `fullContent`, `img`, `link`, `feedId`, `accountId`, `isRead`,
                `isStarred`, `isReadLater`, `updateAt`
            )
            SELECT
                `id`, `date`, `title`, `author`, `rawDescription`, `shortDescription`,
                `fullContent`, `img`, `link`, `feedId`, `accountId`, NOT `isUnread`,
                `isStarred`, `isReadLater`, `updateAt`
            FROM `article`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `article`")
        db.execSQL("ALTER TABLE `article_new` RENAME TO `article`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_feedId` ON `article` (`feedId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_accountId` ON `article` (`accountId`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_read_state_op_new` (
                `articleId` TEXT NOT NULL,
                `accountId` INTEGER NOT NULL,
                `feedId` TEXT NOT NULL,
                `isRead` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `localCommitted` INTEGER NOT NULL DEFAULT 0,
                `remoteSynced` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`articleId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `pending_read_state_op_new` (
                `articleId`, `accountId`, `feedId`, `isRead`, `updatedAt`,
                `localCommitted`, `remoteSynced`
            )
            SELECT
                `articleId`, `accountId`, `feedId`, NOT `isUnread`, `updatedAt`,
                `localCommitted`, `remoteSynced`
            FROM `pending_read_state_op`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `pending_read_state_op`")
        db.execSQL("ALTER TABLE `pending_read_state_op_new` RENAME TO `pending_read_state_op`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_read_state_op_accountId` " +
                "ON `pending_read_state_op` (`accountId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_read_state_op_feedId` " +
                "ON `pending_read_state_op` (`feedId`)"
        )
    }
}
