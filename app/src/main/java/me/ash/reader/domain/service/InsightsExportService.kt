package me.ash.reader.domain.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import me.ash.reader.domain.repository.InsightsDao

/**
 * Builds the JSON export of the reading-insights data (event log, sessions,
 * materialized ranks, and the articles the eval needs features for).
 *
 * Dates are serialized as ISO-8601 explicitly — Gson's default Date format is
 * locale-dependent and would make the export unparseable off-device, where
 * the replay evaluation consumes it.
 */
@Singleton
class InsightsExportService
@Inject
constructor(
    private val insightsDao: InsightsDao,
    private val accountService: AccountService,
) {

    suspend fun buildExportJson(): String {
        val accountId =
            accountService.currentAccountIdFlow.value ?: error("No active account to export")

        val events = insightsDao.queryAllEventsForExport(accountId)
        val sessions = insightsDao.queryAllSessionsForExport(accountId)
        val ranks = insightsDao.queryAllRanksForExport(accountId)
        val articles = insightsDao.queryArticlesForInsightsExport(accountId)

        val payload =
            linkedMapOf<String, Any?>(
                "exportVersion" to 1,
                "exportedAt" to Date().iso(),
                "accountId" to accountId,
                "events" to
                    events.map {
                        linkedMapOf(
                            "id" to it.id,
                            "articleId" to it.articleId,
                            "feedId" to it.feedId,
                            "type" to it.type,
                            "createdAt" to it.createdAt.iso(),
                            "durationMs" to it.durationMs,
                            "depth" to it.depth,
                            "position" to it.position,
                            "context" to it.context,
                            "url" to it.url,
                            "censored" to it.censored,
                        )
                    },
                "sessions" to
                    sessions.map {
                        linkedMapOf(
                            "id" to it.id,
                            "startedAt" to it.startedAt.iso(),
                            "endedAt" to it.endedAt.iso(),
                            "durationMs" to it.durationMs,
                            "entryPoint" to it.entryPoint,
                        )
                    },
                "ranks" to
                    ranks.map {
                        linkedMapOf(
                            "articleId" to it.articleId,
                            "feedId" to it.feedId,
                            "modelId" to it.modelId,
                            "pOpen" to it.pOpen,
                            "predictedMinutes" to it.predictedMinutes,
                            "score" to it.score,
                            "sim" to it.sim,
                            "feedPrior" to it.feedPrior,
                            "freshness" to it.freshness,
                            "lengthFit" to it.lengthFit,
                            "exploration" to it.exploration,
                            "admitted" to it.admitted,
                            "expectedReadMs" to it.expectedReadMs,
                            "computedAt" to it.computedAt.iso(),
                        )
                    },
                "articles" to
                    articles.map {
                        linkedMapOf(
                            "id" to it.id,
                            "feedId" to it.feedId,
                            "date" to it.date.iso(),
                            "title" to it.title,
                            "rawDescription" to it.rawDescription,
                        )
                    },
            )

        val gson =
            com.google.gson.GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create()
        return gson.toJson(payload)
    }

    private fun Date.iso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(this)
}
