package me.ash.reader.domain.model.insights

import androidx.room.Entity
import java.util.Date

/**
 * Per-feed engagement accumulators with exponential decay (60-day half-life),
 * recomputed from the event log on every ranking pass. Feeds drift; all-time
 * averages would fossilize stale tastes.
 *
 * Dilution is deliberately NOT compensated here: the recommender optimizes
 * per-article predicted dwell, so a high-volume feed's prior is the average
 * over all of its articles, filler included. Volume only matters at admission
 * time (per-feed caps), not in the label.
 */
@Entity(
    tableName = "feed_prior",
    primaryKeys = ["accountId", "feedId"],
)
data class FeedPrior(
    val accountId: Int,
    val feedId: String,
    val decayedImpressions: Double = 0.0,
    val decayedOpens: Double = 0.0,
    val decayedEngagedOpens: Double = 0.0,
    /** Σ log(dwellMinutes) over engaged opens — mean exponentiates back to a geometric mean dwell. */
    val decayedLogDwellSum: Double = 0.0,
    val updatedAt: Date,
)
