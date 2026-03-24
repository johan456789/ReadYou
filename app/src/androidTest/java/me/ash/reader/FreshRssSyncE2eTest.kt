package me.ash.reader

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.GoogleReaderSecurityKey
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.android.MainActivity
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.preference.InitialFilterPreference
import me.ash.reader.infrastructure.preference.InitialPagePreference
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.spacerDollar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreshRssSyncE2eTest {
    @get:Rule
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val database by lazy { AndroidDatabase.getInstance(targetContext) }

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: FakeFreshRssDispatcher
    private var scenario: ActivityScenario<MainActivity>? = null

    private val markAsReadText by lazy { targetContext.getString(R.string.mark_as_read) }
    @Before
    fun setUp() {
        GoogleReaderAPI.clearInstance()
        database.clearAllTables()
        clearPreferences()
        targetContext.cacheDir.resolve("diff").deleteRecursively()
        targetContext.cacheDir.resolve("http").deleteRecursively()

        dispatcher = FakeFreshRssDispatcher()
        server =
            MockWebServer().apply {
                dispatcher = this@FreshRssSyncE2eTest.dispatcher
                start()
            }
    }

    @After
    fun tearDown() {
        scenario?.close()
        GoogleReaderAPI.clearInstance()
        server.shutdown()
    }

    @Test
    fun old_remote_reads_disappear_from_unread_list_after_sync() {
        val article =
            seedUnreadArticle(
                title = "Stale unread article",
                remoteArticleId = "stale-unread-article",
                publishedAt = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(400)),
                remoteUnreadIds = emptySet(),
                remoteReadIds = emptySet(),
            )

        dispatcher.networkAvailable = false
        launchApp()
        waitForText(article.title)

        dispatcher.networkAvailable = true
        pullToSyncFromFlow()
        awaitArticleUnreadState(article.localArticleId, expectedUnread = false)

        assertTrue(
            "Expected stale unread article to disappear after sync",
            device.wait(Until.gone(By.text(article.title)), UI_TIMEOUT_MS),
        )
    }

    @Test
    fun offline_local_reads_stay_read_after_sync() {
        val article =
            seedUnreadArticle(
                title = "Offline local read article",
                remoteArticleId = "offline-local-read",
                publishedAt = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)),
                remoteUnreadIds = setOf("offline-local-read"),
                remoteReadIds = emptySet(),
            )

        dispatcher.networkAvailable = false
        launchApp()
        waitForText(article.title)

        longClickText(article.title)
        clickText(markAsReadText)
        awaitArticleUnreadState(article.localArticleId, expectedUnread = false)
        assertTrue(
            "Expected offline read article to disappear from unread list immediately",
            device.wait(Until.gone(By.text(article.title)), UI_TIMEOUT_MS),
        )

        SystemClock.sleep(2_500)
        dispatcher.networkAvailable = true

        pullToSyncFromFlow()
        awaitArticleUnreadState(article.localArticleId, expectedUnread = false)
        assertTrue(
            "Expected offline read article to disappear from unread list after sync",
            device.wait(Until.gone(By.text(article.title)), UI_TIMEOUT_MS),
        )
    }

    @Test
    fun offline_local_reads_stay_read_after_app_restart_and_sync() {
        val article =
            seedUnreadArticle(
                title = "Offline local read after restart",
                remoteArticleId = "offline-local-read-restart",
                publishedAt = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)),
                remoteUnreadIds = setOf("offline-local-read-restart"),
                remoteReadIds = emptySet(),
            )

        dispatcher.networkAvailable = false
        launchApp()
        waitForText(article.title)

        longClickText(article.title)
        clickText(markAsReadText)
        awaitArticleUnreadState(article.localArticleId, expectedUnread = false)
        assertTrue(
            "Expected offline read article to disappear from unread list immediately",
            device.wait(Until.gone(By.text(article.title)), UI_TIMEOUT_MS),
        )

        SystemClock.sleep(2_500)
        scenario?.close()
        GoogleReaderAPI.clearInstance()

        dispatcher.networkAvailable = true
        launchApp()
        awaitArticleUnreadState(article.localArticleId, expectedUnread = false)

        pullToSyncFromFlow()
        awaitArticleUnreadState(article.localArticleId, expectedUnread = false)
        assertTrue(
            "Expected offline read article to stay read after app restart and sync",
            device.wait(Until.gone(By.text(article.title)), UI_TIMEOUT_MS),
        )
    }

    private fun seedUnreadArticle(
        title: String,
        remoteArticleId: String,
        publishedAt: Date,
        remoteUnreadIds: Set<String>,
        remoteReadIds: Set<String>,
    ): SeededArticle {
        dispatcher.remoteUnreadIds = remoteUnreadIds
        dispatcher.remoteReadIds = remoteReadIds

        val accountId =
            runBlocking {
                val securityKey =
                    GoogleReaderSecurityKey(
                        server.url("/").toString(),
                        "demo",
                        "demo",
                        null,
                    ).toString()
                database.accountDao()
                    .insert(
                        Account(
                            name = "FreshRSS E2E",
                            type = AccountType.FreshRSS,
                            securityKey = securityKey,
                        )
                    )
                    .toInt()
            }

        val group = Group(id = accountId.getDefaultGroupId(), name = "Defaults", accountId = accountId)
        val feed = Feed(
            id = accountId.spacerDollar(FEED_ID),
            name = "E2E Feed",
            url = "https://example.com/feed",
            groupId = group.id,
            accountId = accountId,
        )
        val article = Article(
            id = accountId.spacerDollar(remoteArticleId),
            date = publishedAt,
            title = title,
            rawDescription = "<p>$title</p>",
            shortDescription = title,
            link = "https://example.com/articles/$remoteArticleId",
            feedId = feed.id,
            accountId = accountId,
            isUnread = true,
        )

        runBlocking {
            database.groupDao().insert(group)
            database.feedDao().insert(feed)
            database.articleDao().insert(article)
            targetContext.dataStore.put(DataStoreKey.isFirstLaunch, false)
            targetContext.dataStore.put(DataStoreKey.currentAccountId, accountId)
            targetContext.dataStore.put(DataStoreKey.currentAccountType, AccountType.FreshRSS.id)
            targetContext.dataStore.put(
                DataStoreKey.initialPage,
                InitialPagePreference.FlowPage.value,
            )
            targetContext.dataStore.put(
                DataStoreKey.initialFilter,
                InitialFilterPreference.Unread.value,
            )
        }

        return SeededArticle(title = title, localArticleId = article.id)
    }

    private fun launchApp() {
        val intent =
            Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        scenario = ActivityScenario.launch(intent)
    }

    private fun pullToSyncFromFlow() {
        repeat(2) {
            val startX = device.displayWidth / 2
            val startY = device.displayHeight / 3
            val endY = (device.displayHeight * 0.8f).toInt()
            device.swipe(startX, startY, startX, endY, 30)
            SystemClock.sleep(1_000)
        }
    }

    private fun awaitArticleUnreadState(localArticleId: String, expectedUnread: Boolean) {
        assertTrue(
            "Expected article $localArticleId unread=$expectedUnread",
            waitForCondition(15_000) {
                runBlocking {
                    database.articleDao().queryById(localArticleId)?.article?.isUnread ==
                        expectedUnread
                }
            },
        )
    }

    private fun waitForText(text: String) {
        device.wait(Until.findObject(By.text(text)), UI_TIMEOUT_MS)
            ?: throw AssertionError("Expected text \"$text\" to appear")
    }

    private fun clickText(text: String) {
        val obj =
            device.wait(Until.findObject(By.text(text)), UI_TIMEOUT_MS)
                ?: throw AssertionError("Expected text \"$text\" to appear")
        obj.click()
    }

    private fun longClickText(text: String) {
        val obj =
            device.wait(Until.findObject(By.text(text)), UI_TIMEOUT_MS)
                ?: throw AssertionError("Expected text \"$text\" to appear")
        obj.longClick()
    }

    private fun clickDescription(description: String) {
        val obj =
            device.wait(Until.findObject(By.desc(description)), UI_TIMEOUT_MS)
                ?: throw AssertionError("Expected description \"$description\" to appear")
        obj.click()
    }

    private fun clearPreferences() {
        runBlocking {
            targetContext.dataStore.edit { it.clear() }
        }
    }

    private fun waitForCondition(
        timeoutMs: Long,
        pollMs: Long = 200,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(pollMs)
        }
        return condition()
    }

    private data class SeededArticle(
        val title: String,
        val localArticleId: String,
    )

    private class FakeFreshRssDispatcher : Dispatcher() {
        @Volatile var networkAvailable: Boolean = true
        @Volatile var remoteUnreadIds: Set<String> = emptySet()
        @Volatile var remoteReadIds: Set<String> = emptySet()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!networkAvailable) {
                return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            }

            return when (request.requestUrl?.encodedPath) {
                "/accounts/ClientLogin" ->
                    jsonResponse(
                        """
                        {"SID":"sid","LSID":"lsid","Auth":"auth-token"}
                        """.trimIndent()
                    )

                "/reader/api/0/token" -> MockResponse().setResponseCode(200).setBody("token")
                "/reader/api/0/subscription/list" ->
                    jsonResponse(
                        """
                        {"subscriptions":[{"id":"feed/$FEED_ID","title":"E2E Feed","url":"https://example.com/feed","htmlUrl":"https://example.com","iconUrl":null,"sortid":"1"}]}
                        """.trimIndent()
                    )

                "/reader/api/0/stream/items/ids" -> itemIdsResponse(request)
                "/reader/api/0/stream/items/contents" ->
                    jsonResponse("""{"id":"contents","updated":0,"items":[]}""")

                "/reader/api/0/edit-tag" ->
                    MockResponse()
                        .setResponseCode(500)
                        .setBody("""{"errors":["forced failure for pending diff e2e coverage"]}""")

                else -> MockResponse().setResponseCode(404).setBody(request.path ?: "")
            }
        }

        private fun itemIdsResponse(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: error("requestUrl == null")
            val ids =
                when {
                    url.queryParameter("xt") == READ_STREAM ->
                        remoteUnreadIds

                    url.queryParameter("s") == STARRED_STREAM ->
                        emptySet()

                    url.queryParameter("it") == READ_STREAM ||
                        url.queryParameter("s") == READ_STREAM -> remoteReadIds

                    else -> emptySet()
                }

            val itemRefs = ids.joinToString(separator = ",") { """{"id":"$it"}""" }
            return jsonResponse("""{"itemRefs":[$itemRefs],"continuation":null}""")
        }

        private fun jsonResponse(body: String): MockResponse {
            return MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body)
        }
    }

    companion object {
        private const val UI_TIMEOUT_MS = 10_000L
        private const val FEED_ID = "e2e-feed"
        private const val READ_STREAM = "user/-/state/com.google/read"
        private const val STARRED_STREAM = "user/-/state/com.google/starred"
    }
}
