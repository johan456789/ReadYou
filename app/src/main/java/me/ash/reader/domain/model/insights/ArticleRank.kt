package me.ash.reader.domain.model.insights

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Materialized recommender output for one article: the serving layer reads
 * this, it never scores at query time.
 *
 * The two-stage target is expected engaged minutes:
 *   score = pOpen * predictedMinutes
 * where stage A (pOpen) learns "will this be opened given it is shown" from
 * impressions and stage B (predictedMinutes) learns "how long will the open
 * last" from logged dwell. The product punishes clickbait (high pOpen, low
 * dwell) exactly as intended.
 *
 * Score components are stored as explicit columns — queryable, debuggable, and
 * directly usable as explanation values.
 */
@Entity(
    tableName = "article_rank",
    indices = [Index("accountId", "admitted", "score")],
)
data class ArticleRank(
    @PrimaryKey
    val articleId: String,
    val accountId: Int,
    val feedId: String,
    /** Embedding model that contributed `sim`; null for Tier 0/1 scores. */
    val modelId: String? = null,
    val pOpen: Float,
    val predictedMinutes: Float,
    val score: Float,
    /** Profile-centroid cosine similarity; null when no embedding model is active. */
    val sim: Float? = null,
    val feedPrior: Float? = null,
    val freshness: Float? = null,
    val lengthFit: Float? = null,
    /** Exploration slot (Thompson sampling on the feed's posterior) — budget-exempt. */
    val exploration: Boolean = false,
    /** Selected into the For You set under the admission policy. */
    val admitted: Boolean = false,
    /** Snapshot of the content-relative read-time estimate used at scoring time. */
    val expectedReadMs: Long,
    val computedAt: Date,
)

/** Minimal projection of unread articles for scoring. */
data class UnreadArticleMeta(
    val id: String,
    val feedId: String,
    val date: Date,
    val rawDescription: String,
)
