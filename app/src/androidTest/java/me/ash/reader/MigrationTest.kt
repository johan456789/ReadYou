package me.ash.reader

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.MIGRATION_12_13
import me.ash.reader.infrastructure.db.allMigrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the database's declared migrations against real SQLite files created
 * from the exported schemas, and validates both the resulting schema and the
 * preserved data.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        AndroidDatabase::class.java,
    )

    @After
    fun cleanUpDatabaseFiles() {
        listOf(TEST_DB, "$TEST_DB-wal", "$TEST_DB-shm", "$TEST_DB-journal").forEach {
            instrumentation.targetContext.getDatabasePath(it).delete()
        }
    }

    @Test
    fun migrate12To13RenamesIsUnreadColumnToIsReadAndPreservesData() {
        seedVersion12Database()

        // Runs MIGRATION_12_13 against the seeded file and validates the result
        // against schema 13.json.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)
        helper.closeWhenFinished(migrated)

        migrated
            .query("SELECT id, title, isRead, isStarred FROM article ORDER BY id")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("a1", cursor.getString(0))
                assertEquals("Article 1", cursor.getString(1))
                assertEquals(0, cursor.getInt(2)) // was isUnread = 1
                assertEquals(0, cursor.getInt(3))
                assertEquals(true, cursor.moveToNext())
                assertEquals("a2", cursor.getString(0))
                assertEquals("Article 2", cursor.getString(1))
                assertEquals(1, cursor.getInt(2)) // was isUnread = 0
                assertEquals(1, cursor.getInt(3))
                assertEquals(false, cursor.moveToNext())
            }

        migrated
            .query(
                "SELECT articleId, isRead, localCommitted, remoteSynced " +
                    "FROM pending_read_state_op ORDER BY articleId"
            )
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("p1", cursor.getString(0))
                assertEquals(0, cursor.getInt(1)) // was isUnread = 1
                assertEquals(1, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(true, cursor.moveToNext())
                assertEquals("p2", cursor.getString(0))
                assertEquals(1, cursor.getInt(1)) // was isUnread = 0
                assertEquals(1, cursor.getInt(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(false, cursor.moveToNext())
            }

        // End-to-end: open the real database class on the migrated file and read
        // through the DAOs to confirm entity mapping matches the renamed column.
        val database = Room.databaseBuilder(
            instrumentation.targetContext,
            AndroidDatabase::class.java,
            TEST_DB,
        )
            .addMigrations(*allMigrations)
            .build()
        helper.closeWhenFinished(database)

        runBlocking {
            val metasById = database.articleDao()
                .queryMetadataAll(accountId = 1)
                .associateBy { it.id }
            assertEquals(false, metasById.getValue("a1").isRead)
            assertEquals(true, metasById.getValue("a2").isRead)

            val opsById = database.pendingReadStateOpDao()
                .queryByArticleIds(setOf("p1", "p2"))
                .associateBy { it.articleId }
            assertEquals(false, opsById.getValue("p1").isRead)
            assertEquals(true, opsById.getValue("p2").isRead)
        }

        database.close()
    }

    private fun seedVersion12Database() {
        // Room's file lock (migration-test.db.lck) needs the databases directory
        // to exist before the first open on a fresh install.
        val dbDir = instrumentation.targetContext.getDatabasePath(TEST_DB).parentFile
        if (dbDir == null || (!dbDir.isDirectory && !dbDir.mkdirs())) {
            throw IllegalStateException("Could not create database directory: $dbDir")
        }
        val db = helper.createDatabase(TEST_DB, 12)
        db.execSQL(
            "INSERT INTO `group` (id, name, accountId) VALUES ('g1', 'Group 1', 1)"
        )
        db.execSQL(
            "INSERT INTO feed (id, name, url, groupId, accountId, isNotification, isFullContent, isBrowser) " +
                "VALUES ('f1', 'Feed 1', 'https://example.com/rss', 'g1', 1, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO article (id, date, title, author, rawDescription, shortDescription, " +
                "fullContent, img, link, feedId, accountId, isUnread, isStarred, isReadLater, updateAt) VALUES " +
                "('a1', 1000, 'Article 1', NULL, '<p>one</p>', 'one', NULL, NULL, " +
                "'https://example.com/1', 'f1', 1, 1, 0, 0, NULL), " +
                "('a2', 2000, 'Article 2', NULL, '<p>two</p>', 'two', NULL, NULL, " +
                "'https://example.com/2', 'f1', 1, 0, 1, 0, NULL)"
        )
        db.execSQL(
            "INSERT INTO pending_read_state_op (articleId, accountId, feedId, isUnread, " +
                "updatedAt, localCommitted, remoteSynced) VALUES " +
                "('p1', 1, 'f1', 1, 1700000000000, 1, 0), " +
                "('p2', 1, 'f1', 0, 1700000000000, 1, 1)"
        )
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
