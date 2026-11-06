package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Vectorizes articles for the (opt-in, downloaded) embedding model and writes
 * [me.ash.reader.domain.model.insights.ArticleVector] rows. Chained between
 * full-content fetch and ranking so embeddings always see the richest
 * available text.
 *
 * M1 pass-through: Tier 0/1 scoring needs no embeddings. The real
 * implementation (model download manager, content-hash-driven re-embedding)
 * lands with Tier 2; this stub keeps the sync chain shape stable.
 */
@HiltWorker
class EmbedWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = Result.success()
}
