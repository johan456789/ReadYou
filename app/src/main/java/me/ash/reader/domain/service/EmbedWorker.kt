package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Vectorizes articles for the (opt-in, downloaded) embedding model and writes
 * [me.ash.reader.domain.model.insights.ArticleVector] rows. Chained after
 * full-content fetch so embeddings always see the richest available text.
 *
 * M1 pass-through: Tier 0/1 scoring needs no embeddings. The real
 * implementation (model download manager, content-hash-driven re-embedding)
 * lands with Tier 2; this stub keeps the sync chain shape stable.
 *
 * On completion it triggers the rank pass — outside the sync chain so ranking
 * neither delays the widget refresh nor dies with a cancelled sync.
 */
@HiltWorker
class EmbedWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val workManager: WorkManager,
    private val accountService: AccountService,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val accountId =
            inputData.getInt("accountId", -1).takeIf { it != -1 }
                ?: accountService.currentAccountIdFlow.value

        // TODO(Tier 2): vectorize articles missing a vector for the active
        //  embedding model (contentHash mismatch → re-embed).

        if (accountId != null) {
            RankWorker.enqueueNow(workManager, accountId)
        }
        return Result.success()
    }
}
