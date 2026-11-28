package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.ash.reader.domain.model.insights.EmbeddingModelCatalog
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Acquires the selected embedding model + its WordPiece vocab (M3):
 * WiFi-only, sha256-verified against [EmbeddingModelCatalog], stored
 * app-privately under filesDir/models/<modelId>/. NOT bundled in the APK.
 *
 * Triggered by the embeddingsEnabled preference flipping on (AndroidApp
 * observer) or the model picker changing. On success: marks the downloaded
 * model, then chains the embedding + rank pass so vectors land immediately.
 *
 * Failure handling: any verification failure deletes partial files and the
 * work result retries (WorkManager backoff while the WiFi constraint holds);
 * the embeddingsEnabled preference is untouched so the UI keeps reflecting
 * user intent and retry stays possible.
 */
@HiltWorker
class EmbeddingsDownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelId =
            inputData.getString(KEY_MODEL_ID)
                ?: applicationContext.dataStore.data.first()[PreferencesKey.stringKey(
                    PreferencesKey.embeddingsModelId
                )]
                ?: EmbeddingModelCatalog.preset().id
        return tryDownload(modelId)
    }

    private suspend fun tryDownload(modelId: String): Result {
        val entry = EmbeddingModelCatalog.byId(modelId)
        if (entry == null || !entry.available) {
            Timber.tag(TAG).w("Model %s unavailable — skipping download", modelId)
            return Result.failure()
        }

        val modelDir = File(applicationContext.filesDir, "models/$modelId").apply { mkdirs() }
        val modelFile = File(modelDir, MODEL_FILE)
        val vocabFile = File(modelDir, VOCAB_FILE)

        return try {
            if (!verify(modelFile, entry.modelSha256)) {
                downloadTo(entry.modelUrl, modelFile)
                check(verify(modelFile, entry.modelSha256)) { "sha256 mismatch for ${modelFile.name}" }
            }
            if (!verify(vocabFile, entry.vocabSha256)) {
                downloadTo(entry.vocabUrl, vocabFile)
                check(verify(vocabFile, entry.vocabSha256)) { "sha256 mismatch for ${vocabFile.name}" }
            }

            applicationContext.dataStore.put(
                PreferencesKey.embeddingsDownloadedModelId,
                modelId,
            )
            Timber.tag(TAG).i("Embedding model %s downloaded and verified", modelId)

            // Vectors + rank pass immediately: the user sees the tier working
            // without waiting for a sync.
            RankWorker.enqueueNow(workManager, accountId = null)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Embedding model download failed for %s", modelId)
            modelFile.delete()
            vocabFile.delete()
            Result.retry()
        }
    }

    private fun verify(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) } == expectedSha256
    }

    private fun downloadTo(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            check(tmp.length() > 0) { "Empty download: $url" }
            check(tmp.renameTo(target)) { "Could not move ${tmp.name} into place" }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "EmbeddingsDownload"
        const val MODEL_FILE = "model.onnx"
        const val VOCAB_FILE = "tokenizer.json"
        const val KEY_MODEL_ID = "modelId"

        /**
         * Called by the embeddingsEnabled preference observer (and the model
         * picker after switching). Unique name keyed by model: a re-enable or
         * a model switch re-downloads; a duplicate request coalesces.
         */
        fun enqueue(workManager: WorkManager, modelId: String) {
            workManager.enqueueUniqueWork(
                "EMBEDDINGS_DOWNLOAD_$modelId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EmbeddingsDownloadWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .build()
                    )
                    .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                    .addTag(RankWorker.INSIGHTS_WORK_TAG)
                    .build(),
            )
        }
    }
}
