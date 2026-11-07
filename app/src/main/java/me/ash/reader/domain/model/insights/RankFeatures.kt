package me.ash.reader.domain.model.insights

import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Single source of truth for ranker features. Training ([TrainWorker]) and
 * serving ([RankWorker]) MUST both compute features through this object so
 * the two never drift; [FEATURE_VERSION] is stamped on every stored model and
 * invalidates it on any feature change.
 *
 * Features are kept roughly within [0, 2] so plain gradient descent trains
 * stably without a separate scaler. The position feature is a training-only
 * device against position bias: impressions know their list position, serving
 * always passes 0 so the fitted position coefficient contributes nothing at
 * inference time.
 */
object RankFeatures {

    const val FEATURE_VERSION = 1

    /** Shared dwell-ratio clamp: training target and serving prediction agree. */
    const val DWELL_RATIO_MIN = 0.05
    const val DWELL_RATIO_MAX = 3.0

    /**
     * Stage A (open model), dimension 9:
     * [bias, feedOpenRate, freshness, expectedNorm, hourSin, hourCos,
     *  weekdaySin, weekdayCos, positionNorm].
     */
    const val OPEN_FEATURE_COUNT = 9

    /**
     * Stage B (dwell model), dimension 8:
     * [bias, feedDwellPriorLog, freshness, expectedNorm, hourSin, hourCos,
     *  weekdaySin, weekdayCos].
     */
    const val DWELL_FEATURE_COUNT = 8

    fun openFeatures(
        feedOpenRate: Float,
        freshness: Float,
        expectedMinutes: Float,
        atMillis: Long,
        position: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FloatArray =
        floatArrayOf(
            1f,
            feedOpenRate,
            freshness,
            normalizeExpected(expectedMinutes),
            *cyclicalTime(atMillis, zone),
            normalizePosition(position),
        )

    fun dwellFeatures(
        feedDwellPriorLog: Float,
        freshness: Float,
        expectedMinutes: Float,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FloatArray =
        floatArrayOf(
            1f,
            feedDwellPriorLog,
            freshness,
            normalizeExpected(expectedMinutes),
            *cyclicalTime(atMillis, zone),
        )

    fun sigmoid(x: Float): Float =
        when {
            x >= 0f -> 1f / (1f + exp(-x))
            else -> {
                val e = exp(x)
                e / (1f + e)
            }
        }

    /** ln(1 + minutes) / ln(241), capped at 240 min → [0, 1]. */
    private fun normalizeExpected(minutes: Float): Float {
        val capped = min(minutes, 240f).coerceAtLeast(0f)
        return (ln(1.0 + capped) / ln(241.0)).toFloat()
    }

    /**
     * ln(1 + position) / ln(51), capped at position 50 → [0, 1].
     *
     * Known approximation: the list position comes from the lazy-list index,
     * which counts sticky date-header rows too, so the exact value depends on
     * a display preference. Fine for a bias-absorbing feature; revisit if
     * position ever gets a user-visible explanation.
     */
    private fun normalizePosition(position: Int): Float {
        val capped = min(position, 50).coerceAtLeast(0)
        return (ln(1.0 + capped) / ln(51.0)).toFloat()
    }

    /** Time of day and day of week as smooth sin/cos pairs (no midnight cliff). */
    private fun cyclicalTime(atMillis: Long, zone: ZoneId): FloatArray {
        val zoned = Instant.ofEpochMilli(atMillis).atZone(zone)
        val hourFraction =
            (zoned.hour + zoned.minute / 60.0 + zoned.second / 3600.0) / 24.0
        val weekFraction = (zoned.dayOfWeek.value - 1) / 7.0
        return floatArrayOf(
            (2.0 * Math.PI * hourFraction).let { sin(it).toFloat() },
            (2.0 * Math.PI * hourFraction).let { cos(it).toFloat() },
            (2.0 * Math.PI * weekFraction).let { sin(it).toFloat() },
            (2.0 * Math.PI * weekFraction).let { cos(it).toFloat() },
        )
    }

    /** Freshness decay shared by training and serving (72-hour time constant). */
    fun freshness(articleAtMillis: Long, atMillis: Long): Float {
        val ageHours = ((atMillis - articleAtMillis).coerceAtLeast(0)) / 3_600_000.0
        return exp(-ageHours / 72.0).toFloat()
    }
}

/** float32 <-> packed bytes for [RankerModel.weights] storage. */
fun FloatArray.toModelBytes(): ByteArray {
    val buffer = java.nio.ByteBuffer.allocate(size * 4)
    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.asFloatBuffer().put(this)
    return buffer.array()
}

fun ByteArray.toModelFloats(): FloatArray {
    require(size % 4 == 0) { "Model weights must be float32-packed" }
    val buffer = java.nio.ByteBuffer.wrap(this)
    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val floats = FloatArray(size / 4)
    buffer.asFloatBuffer().get(floats)
    return floats
}
