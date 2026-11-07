package me.ash.reader.infrastructure.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_13_14 creates the reading-insights tables exactly as
 * Room expects them from the exported schemas (columns, nullability, PKs,
 * indices) — the mismatch class of bug that only surfaces at runtime.
 */
@RunWith(AndroidJUnit4::class)
class InsightsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AndroidDatabase::class.java,
    )

    @Test
    fun migrate13To14_createsInsightsTables() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            // FK-valid parents for the article row.
            db.execSQL("INSERT INTO `group` (id, name, accountId) VALUES ('g1', 'g', 1)")
            db.execSQL(
                "INSERT INTO feed (id, name, icon, url, groupId, accountId, isNotification, isFullContent, isBrowser, sortOrder) " +
                    "VALUES ('f1', 'f', NULL, 'u', 'g1', 1, 0, 0, 0, NULL)"
            )
            db.execSQL(
                """
                INSERT INTO article (
                    id, date, title, author, rawDescription, shortDescription,
                    fullContent, img, link, feedId, accountId, isRead, isStarred,
                    isReadLater, updateAt
                ) VALUES (
                    'a1', 1000, 't', NULL, 'r', 's', NULL, NULL, 'l', 'f1', 1, 0, 0, 0, NULL
                )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14).use { db ->
            // Reading-insights tables exist and accept rows matching the schema.
            db.execSQL(
                """
                INSERT INTO reading_event (
                    accountId, articleId, feedId, type, createdAt,
                    durationMs, depth, position, context, url, censored
                ) VALUES (1, 'a1', 'f1', 1, 1000, NULL, NULL, 3, NULL, NULL, 0)
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO article_rank (articleId, accountId, feedId, pOpen, predictedMinutes, score, exploration, admitted, expectedReadMs, computedAt) " +
                    "VALUES ('a1', 1, 'f1', 0.5, 2.0, 1.0, 0, 1, 120000, 1000)"
            )
            db.execSQL("INSERT INTO feed_prior (accountId, feedId, decayedImpressions, decayedOpens, decayedEngagedOpens, decayedLogDwellSum, updatedAt) VALUES (1, 'f1', 1.0, 1.0, 0.0, 0.0, 1000)")
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-13-14.db"
    }
}
