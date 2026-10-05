package me.ash.reader

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.preference.InitialFilterPreference
import me.ash.reader.infrastructure.preference.InitialPagePreference
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.spacerDollar
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchmarkSeedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val database by lazy { AndroidDatabase.getInstance(targetContext) }

    @Before
    fun setUp() {
        runBlocking { database.clearAllTables() }
    }

    @Test
    fun seedArticleList() {
        val accountId =
            runBlocking {
                database
                    .accountDao()
                    .insert(Account(name = "Benchmark", type = AccountType.Local))
                    .toInt()
            }

        val group = Group(id = accountId.getDefaultGroupId(), name = "Defaults", accountId = accountId)
        val feed =
            Feed(
                id = accountId.spacerDollar(FEED_ID),
                name = "Benchmark Feed",
                url = "https://example.com/feed",
                groupId = group.id,
                accountId = accountId,
            )

        runBlocking {
            database.groupDao().insert(group)
            database.feedDao().insert(feed)
        }

        val titles = sampleTitles()
        titles.forEachIndexed { index, title ->
            val summary = summaryFor(index)
            val article =
                Article(
                    id = accountId.spacerDollar("bench-$index"),
                    date = Date(System.currentTimeMillis() - index * 3_600_000L),
                    title = title,
                    rawDescription = "<p>$summary</p>",
                    shortDescription = summary,
                    link = "https://example.com/articles/$index",
                    feedId = feed.id,
                    accountId = accountId,
                    isUnread = index % 3 != 0,
                )
            runBlocking { database.articleDao().insert(article) }
        }

        runBlocking {
            targetContext.dataStore.put(PreferencesKey.isFirstLaunch, false)
            targetContext.dataStore.put(PreferencesKey.currentAccountId, accountId)
            targetContext.dataStore.put(PreferencesKey.currentAccountType, AccountType.Local.id)
            targetContext.dataStore.put(
                PreferencesKey.initialPage,
                InitialPagePreference.FlowPage.value,
            )
            targetContext.dataStore.put(
                PreferencesKey.initialFilter,
                InitialFilterPreference.All.value,
            )
        }
    }

    private fun sampleTitles(): List<String> {
        val base =
            "A quiet morning in the city brings small discoveries and long walks through the rain"
        return (0 until 60).map { index ->
            val repeat = when (index % 5) {
                0 -> 1
                1 -> 2
                2 -> 3
                3 -> 4
                else -> 6
            }
            "$index: " + List(repeat) { base }.joinToString(" ")
        }
    }

    private fun summaryFor(index: Int): String {
        val sentence =
            "This is a short description used by the flow list to show the first lines of the article. "
        return when (index % 4) {
            0 -> sentence
            1 -> sentence.repeat(2)
            2 -> sentence.repeat(4)
            else -> sentence.repeat(8)
        }
    }

    private companion object {
        const val FEED_ID = "benchmark-feed"
    }
}
