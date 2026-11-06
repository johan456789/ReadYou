package me.ash.reader.domain.model.insights

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Append-only interaction log feeding the local reading recommender.
 *
 * Raw signals only: derived quantities (bounce flags, dwell ratios, labels)
 * are computed at training time, never at logging time, so label logic can be
 * improved retroactively without having collected garbage in the meantime.
 */
@Entity(
    tableName = "reading_event",
    indices = [
        Index("accountId", "createdAt"),
        Index("articleId"),
        Index("type"),
    ],
)
data class ReadingEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Int,
    /** Null for events not tied to a single article. */
    val articleId: String? = null,
    /** Null when not trivially available at the capture site; joined at training time. */
    val feedId: String? = null,
    /** One of [ReadingEventType] constants. */
    val type: Int,
    val createdAt: Date,
    /** DWELL: foreground-gated reading time. LINK_OUT: elapsed away time once reconciled. */
    val durationMs: Long? = null,
    /** SCROLL: final visible fraction 0..1 for the flushed dwell. */
    val depth: Float? = null,
    /** IMPRESSION: list position at exposure (training-only position-bias feature). */
    val position: Int? = null,
    /** Bitmask of [ReadingEventContext] flags; meaning depends on [type]. */
    val context: Int? = null,
    /** LINK_OUT: target url. */
    val url: String? = null,
    /** DWELL truncated by the app leaving foreground (user may have kept reading elsewhere). */
    val censored: Boolean = false,
)

/**
 * Event vocabulary. Kept as Int constants (not enums) so new values can be
 * added without type-converter churn; `type` is an open vocabulary.
 */
object ReadingEventType {
    const val IMPRESSION = 0
    const val OPEN = 1
    const val DWELL = 2
    const val SCROLL = 3
    const val LINK_OUT = 4
    const val STAR = 5
    const val UNSTAR = 6
    const val READ_LATER = 7
    const val MARK_READ_MANUAL = 8
    const val MARK_READ_BULK = 9
}

/**
 * Context bit flags for [ReadingEvent.context]. Meaning is per event type.
 *
 * LINK_OUT: [LINK_SELF] when the article's own link was opened (full away-time
 * attribution), [LINK_INLINE] for links inside the article body (discounted).
 * MARK_READ_BULK: one [MARK_SCOPE_*] flag plus optionally a [MARK_SOURCE_*].
 */
object ReadingEventContext {
    // Link-out attribution
    const val LINK_SELF = 1
    const val LINK_INLINE = 2

    // Bulk mark scope
    const val MARK_SCOPE_FEED = 4
    const val MARK_SCOPE_GROUP = 8
    const val MARK_SCOPE_ALL = 16
    const val MARK_SCOPE_BATCH = 32

    // Where a mark action came from
    const val MARK_SOURCE_LIST = 64
    const val MARK_SOURCE_READER = 128
}
