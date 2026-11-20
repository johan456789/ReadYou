package me.ash.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.insights.ReadingEventType
import me.ash.reader.domain.repository.InsightsDao
import me.ash.reader.infrastructure.android.MainActivity
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import me.ash.reader.infrastructure.preference.InitialPagePreference
import me.ash.reader.infrastructure.preference.InitialFilterPreference
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.spacerDollar

/**
 * Regression test for a silent production bug: the app manifest removed the
 * androidx.startup InitializationProvider (an old WorkManager scheduling
 * decision), which in lifecycle-process 2.9+ means ProcessLifecycleOwner
 * is never attached — so sessions and impressions were never recorded,
 * silently. Seeds a local account with unread articles, backgrounds and
 * re-launches the app twice, then asserts sessions and impressions exist.
 * If this fails, check that ProcessLifecycleInitializer is merged back into
 * the manifest (see the InitializationProvider comment in AndroidManifest).
 */
@RunWith(AndroidJUnit4::class)
class InsightsSessionLifecycleTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val database by lazy { AndroidDatabase.getInstance(targetContext) }

    private val accountId get() = runBlocking {
        targetContext.dataStore.data
            .first()[PreferencesKey.intKey(PreferencesKey.currentAccountId)] ?: -1
    }

    @Before
    fun setUp() {
        database.clearAllTables()
        runBlocking {
            val accountId =
                database.accountDao()
                    .insert(
                        Account(name = "Repro", type = AccountType.Local),
                    )
                    .toInt()
            val group = Group(id = accountId.getDefaultGroupId(), name = "Defaults", accountId = accountId)
            val feed = Feed(
                id = accountId.spacerDollar("repro-feed"),
                name = "Repro Feed",
                url = "https://example.com/feed",
                groupId = group.id,
                accountId = accountId,
            )
            database.groupDao().insert(group)
            database.feedDao().insert(feed)
            repeat(10) { i ->
                database.articleDao().insert(
                    Article(
                        id = accountId.spacerDollar("repro-$i"),
                        date = Date(System.currentTimeMillis() - i * 3_600_000L),
                        title = "Repro article $i",
                        rawDescription = "<p>Repro body $i</p>",
                        shortDescription = "Repro article $i",
                        link = "https://example.com/$i",
                        feedId = feed.id,
                        accountId = accountId,
                        isRead = false,
                    )
                )
            }
            targetContext.dataStore.put(PreferencesKey.isFirstLaunch, false)
            targetContext.dataStore.put(PreferencesKey.currentAccountId, accountId)
            targetContext.dataStore.put(PreferencesKey.currentAccountType, AccountType.Local.id)
            targetContext.dataStore.put(
                PreferencesKey.initialPage,
                InitialPagePreference.FlowPage.value,
            )
            targetContext.dataStore.put(
                PreferencesKey.initialFilter,
                InitialFilterPreference.Unread.value,
            )
        }
    }

    private fun launchApp() {
        ActivityScenario.launch<MainActivity>(
            Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Test
    fun sessionsAndImpressionsRecordedAcrossBackgroundCycles() {
        val insightsDao: InsightsDao = database.insightsDao()

        launchApp()
        device.wait(Until.hasObject(By.textContains("Repro article")), 15_000)
        device.pressHome()
        Thread.sleep(3_000)

        launchApp()
        device.wait(Until.hasObject(By.textContains("Repro article")), 10_000)
        device.pressHome()
        Thread.sleep(3_000)

        // Bring the app back so WorkManager-flushed inserts complete.
        launchApp()
        Thread.sleep(3_000)

        val sessions = runBlocking { insightsDao.queryAllSessionsForExport(accountId) }
        val events = runBlocking { insightsDao.queryAllEventsForExport(accountId) }
        val impressions = events.count { it.type == ReadingEventType.IMPRESSION }

        WorkManager.getInstance(targetContext).cancelAllWorkByTag("INSIGHTS_WORK_TAG")
        Thread.sleep(1_000)
        val finalSessions = runBlocking { insightsDao.queryAllSessionsForExport(accountId) }
        val finalEvents = runBlocking { insightsDao.queryAllEventsForExport(accountId) }
        val finalImpressions = finalEvents.count { it.type == ReadingEventType.IMPRESSION }

        assertTrue(
            "expected >=1 session, got ${sessions.size} then ${finalSessions.size}",
            finalSessions.isNotEmpty() || sessions.isNotEmpty(),
        )
        assertTrue(
            "expected impressions, got $impressions then $finalImpressions",
            finalImpressions > 0 || impressions > 0,
        )
    }
}
