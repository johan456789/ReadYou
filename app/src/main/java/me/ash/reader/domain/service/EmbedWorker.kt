package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.ash.reader.domain.model.insights.ArticleVector
import me.ash.reader.domain.model.insights.BertWordPieceTokenizer
import me.ash.reader.domain.model.insights.EmbeddingModelCatalog
import me.ash.reader.domain.model.insights.OnnxTextEmbedder
import me.ash.reader.domain.model.insights.toFloatBytes
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.InsightsDao
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import timber.log.Timber
import java.io.File
import java.util.Date

/**
 * Vectorizes articles for the selected embedding model (M3): loads the
 * downloaded ONNX model + tokenizer from app-private storage, embeds unread
 * articles missing a vector for the active model (or with changed content),
 * and writes [ArticleVector] rows. Centroid/similarity consumption happens in
 * [RankWorker].
 *
 * Gated on: embeddingsEnabled, the model file being downloaded+verified, and
 * the tokenizer file present. Any of these missing → the rank pass still runs
 * (Tier 0 / two-stage serving is unaffected).
 */
@HiltWorker
class EmbedWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val insightsDao: InsightsDao,
    private val articleDao: ArticleDao,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val accountId =
            inputData.getInt("accountId", -1).takeIf { it != -1 } ?: -1
        try {
            embed(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Embedding pass failed")
        }
        // Ranking proceeds regardless: Tier 0 / two-stage serving does not
        // depend on vectors existing.
        RankWorker.enqueueNow(workManager, accountId.takeIf { it != -1 })
        return Result.success()
    }

    private suspend fun embed(accountId: Int) {
        val enabled =
            applicationContext.dataStore.data
                .first()[PreferencesKey.booleanKey(PreferencesKey.embeddingsEnabled)] ?: false
        if (!enabled) return

        val modelId =
            applicationContext.dataStore.data
                .first()[PreferencesKey.stringKey(PreferencesKey.embeddingsModelId)]
                ?: EmbeddingModelCatalog.preset().id
        val entry = EmbeddingModelCatalog.byId(modelId)?.takeIf { it.available } ?: return

        val modelDir = File(applicationContext.filesDir, "models/$modelId")
        val modelFile = File(modelDir, EmbeddingsDownloadWorker.MODEL_FILE)
        val vocabFile = File(modelDir, EmbeddingsDownloadWorker.VOCAB_FILE)
        if (!modelFile.exists() || !vocabFile.exists()) return

        // Unread articles are the rank-candidate pool — the only articles the
        // recommender serves, and therefore the only ones worth embedding.
        val candidates = insightsDao.queryUnreadArticleShallow(accountId, Date(0), 5_000, 0)
        if (candidates.isEmpty()) return
        val ids = candidates.map { it.id }
        val articlesById = articleDao.queryByIds(ids).associateBy { it.id }

        val existing =
            insightsDao.queryVectorsForArticles(accountId, modelId, ids).associateBy { it.articleId }

        val missing =
            ids.mapNotNull { id ->
                val meta = articlesById[id] ?: return@mapNotNull null
                val vector = existing[id]
                val contentHash = (meta.rawDescription ?: "").hashCode().toLong()
                if (vector != null && vector.contentHash == contentHash) null
                else Triple(id, meta.rawDescription ?: "", contentHash)
            }
        if (missing.isEmpty()) {
            Timber.tag(TAG).i("Embeddings up to date for account %d (%d vectors)", accountId, existing.size)
            return
        }

        val vocab = parseVocab(vocabFile)
        val tokenizer = BertWordPieceTokenizer(vocab)
        OnnxTextEmbedder(modelFile, tokenizer).use { embedder ->
            for (chunk in missing.chunked(16)) {
                val vectors = embedder.embed(chunk.map { stripHtml(it.second) })
                val rows =
                    chunk.mapIndexed { k, (id, _, hash) ->
                        ArticleVector(
                            articleId = id,
                            modelId = modelId,
                            accountId = accountId,
                            feedId = articlesById[id]?.feedId ?: "",
                            vector = vectors[k].toFloatBytes(),
                            contentHash = hash,
                            createdAt = Date(),
                        )
                    }
                insightsDao.upsertVectors(rows)
            }
        }
        Timber.tag(TAG).i("Embedded %d/%d articles for model %s", missing.size, ids.size, modelId)
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(1500)

    /** WordPiece vocab from the transformers.js tokenizer.json (model.vocab map). */
    private fun parseVocab(tokenizerFile: File): Map<String, Int> {
        val obj = JsonParser.parseString(tokenizerFile.readText()).asJsonObject
        val vocabObj = obj.getAsJsonObject("model").getAsJsonObject("vocab")
        val map = HashMap<String, Int>(vocabObj.size())
        for ((token, idElem) in vocabObj.entrySet()) {
            map[token] = idElem.asInt
        }
        return map
    }

    companion object {
        private const val TAG = "EmbedWorker"
    }
}
