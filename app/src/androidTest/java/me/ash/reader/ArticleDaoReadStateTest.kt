package me.ash.reader

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * Executes the hard-coded read-state predicates in [AndroidDatabase] against a
 * real in-memory SQLite database so polarity regressions (e.g. after the
 * isUnread -> isRead column rename) are caught at test time.
 */
@RunWith(AndroidJUnit4::class)
class ArticleDaoReadStateTest {

    private lateinit var database: AndroidDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AndroidDatabase::class.java,
        ).allowMainThreadQueries().build()
        seed()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun latestUnreadQueriesReturnOnlyUnreadArticles() = runBlocking {
        val dao = database.articleDao()
        assertEquals(
            setOf("u1", "u2"),
            dao.queryLatestUnreadArticles(ACCOUNT_ID).first().map { it.article.id }.toSet(),
        )
        assertEquals(
            setOf("u1"),
            dao.queryLatestUnreadArticlesFromFeed(FEED_1).first().map { it.article.id }.toSet(),
        )
        assertEquals(
            setOf("u1", "u2"),
            dao.queryLatestUnreadArticlesFromGroup(GROUP).first().map { it.article.id }.toSet(),
        )
        assertEquals(
            setOf("u1", "u2"),
            dao.queryLatestUnreadArticleFlow(ACCOUNT_ID, limit = 15).first()
                .map { it.article.id }.toSet(),
        )
    }

    @Test
    fun unreadFullContentQueryReturnsUnreadArticlesOfFullContentFeeds() = runBlocking {
        val ids = database.articleDao()
            .queryUnreadFullContentArticles(ACCOUNT_ID)
            .map { it.id }
            .toSet()
        assertEquals(setOf("u2"), ids)
    }

    @Test
    fun archiveQueriesTargetReadUnstarredArticles() = runBlocking {
        val dao = database.articleDao()
        assertEquals(
            setOf("r1"),
            dao.queryArchivedArticleBefore(ACCOUNT_ID, before = Date(2_000))
                .map { it.id }.toSet(),
        )

        dao.deleteAllArchivedBeforeThan(ACCOUNT_ID, before = Date(2_000))

        assertEquals(
            setOf("u1", "u2", "s1"),
            dao.queryMetadataAll(ACCOUNT_ID).map { it.id }.toSet(),
        )
    }

    private fun seed() = runBlocking {
        database.groupDao().insert(Group(id = GROUP, name = "Group", accountId = ACCOUNT_ID))
        database.feedDao().insert(
            Feed(
                id = FEED_1,
                name = "Feed 1",
                url = "https://example.com/1",
                groupId = GROUP,
                accountId = ACCOUNT_ID,
            ),
            Feed(
                id = FEED_2,
                name = "Feed 2",
                url = "https://example.com/2",
                groupId = GROUP,
                accountId = ACCOUNT_ID,
                isFullContent = true,
            ),
        )
        database.articleDao().insert(
            article(id = "u1", date = 1_000, isRead = false),
            article(id = "u2", date = 1_100, isRead = false, feedId = FEED_2),
            article(id = "r1", date = 1_200, isRead = true),
            article(id = "s1", date = 1_300, isRead = true, isStarred = true),
        )
    }

    private fun article(
        id: String,
        date: Long,
        isRead: Boolean,
        isStarred: Boolean = false,
        feedId: String = FEED_1,
    ) = Article(
        id = id,
        date = Date(date),
        title = id,
        author = null,
        rawDescription = "<p>$id</p>",
        shortDescription = id,
        fullContent = null,
        img = null,
        link = "https://example.com/$id",
        feedId = feedId,
        accountId = ACCOUNT_ID,
        isRead = isRead,
        isStarred = isStarred,
        isReadLater = false,
        updateAt = Date(date),
    )

    private companion object {
        const val ACCOUNT_ID = 1
        const val GROUP = "group"
        const val FEED_1 = "feed1"
        const val FEED_2 = "feed2"
    }
}
