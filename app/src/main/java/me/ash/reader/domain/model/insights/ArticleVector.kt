package me.ash.reader.domain.model.insights

import androidx.room.Entity
import androidx.room.Index
import java.util.Date

/**
 * Cached embedding of an article's content. The composite key lets several
 * embedding models coexist (e.g. after a model swap) without migration; vectors
 * from different models must never be mixed in one profile, which the
 * [modelId] stamp on every consumer enforces.
 *
 * Not populated in M1 (Tier 0/1 scoring needs no embeddings); the writing path
 * arrives with the Tier 2 embedding worker.
 */
@Entity(
    tableName = "article_vector",
    primaryKeys = ["articleId", "modelId"],
    indices = [Index("accountId")],
)
data class ArticleVector(
    val articleId: String,
    val modelId: String,
    val accountId: Int,
    val feedId: String,
    /** Packed (float16) embedding values. */
    val vector: ByteArray,
    /** Hash of the exact text embedded; a mismatch after content re-parse triggers re-embedding. */
    val contentHash: Long,
    val createdAt: Date,
) {
    override fun equals(other: Any?): Boolean =
        other is ArticleVector &&
            articleId == other.articleId &&
            modelId == other.modelId &&
            accountId == other.accountId &&
            feedId == other.feedId &&
            contentHash == other.contentHash &&
            createdAt == other.createdAt &&
            vector.contentEquals(other.vector)

    override fun hashCode(): Int = articleId.hashCode() * 31 + modelId.hashCode()
}
