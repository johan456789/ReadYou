package me.ash.reader.domain.model.insights

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Trained weights for one stage of the two-stage ranker, per account. Rows
 * appear once TrainWorker fits a stage past its sample thresholds (before
 * that, Tier 0 heuristic constants serve); a new fit is a new row, and
 * retention always keeps each stage's newest.
 */
@Entity(
    tableName = "ranker_model",
    indices = [Index("accountId", "stage")],
)
data class RankerModel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Int,
    /** [RankerStage.STAGE_OPEN] or [RankerStage.STAGE_DWELL]. */
    val stage: Int,
    /** Bump on any feature change; consumers ignore/retrain on mismatch. */
    val featureVersion: Int,
    val weights: ByteArray,
    val trainSamples: Int,
    val trainedAt: Date,
) {
    override fun equals(other: Any?): Boolean =
        other is RankerModel &&
            id == other.id &&
            weights.contentEquals(other.weights)

    override fun hashCode(): Int = id.hashCode() * 31 + weights.contentHashCode()
}

object RankerStage {
    const val STAGE_OPEN = 0
    const val STAGE_DWELL = 1
}
