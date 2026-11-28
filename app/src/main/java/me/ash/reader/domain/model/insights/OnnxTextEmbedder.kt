package me.ash.reader.domain.model.insights

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * ONNX-backed sentence embedder for the bge-small-zh-v1.5 export: BERT-style
 * inputs (input_ids / attention_mask / token_type_ids), last-hidden-state
 * output, CLS-free mean pooling over the attention mask, L2 normalization —
 * matching the sentence-transformers export's pooling config.
 *
 * Sessions are created per instance with a couple of threads: embedding a few
 * hundred articles at sync time is an occasional background task, not a
 * latency-sensitive path.
 */
class OnnxTextEmbedder(
    modelFile: File,
    private val tokenizer: BertWordPieceTokenizer,
) : AutoCloseable {

    private companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val PAD = "[PAD]"
        const val UNK = "[UNK]"
    }

    private val clsId = tokenizer.tokenId(CLS)?.toLong() ?: 101L
    private val sepId = tokenizer.tokenId(SEP)?.toLong() ?: 102L
    private val padId = tokenizer.tokenId(PAD)?.toLong() ?: 0L
    private val unkId = tokenizer.tokenId(UNK)?.toLong() ?: 100L

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        })

    private val inputNames: List<String> = session.inputNames.toList().sorted()
    private val needsTokenTypeIds = inputNames.any { it.contains("token_type") }
    private val idInputName = inputNames.first { it.contains("input_ids") }
    private val maskInputName = inputNames.first { it.contains("attention") }
    private val typeInputName = inputNames.firstOrNull { it.contains("token_type") }
    private val outputName: String =
        session.outputNames.toList().firstOrNull { it.contains("last_hidden") }
            ?: session.outputNames.toList().first()

    fun encode(text: String): LongArray {
        val pieces = tokenizer.wordPiece(tokenizer.basicTokenize(text)).take(510)
        val ids = ArrayList<Long>(pieces.size + 2)
        ids.add(clsId)
        for (piece in pieces) ids.add(tokenizer.tokenId(piece)?.toLong() ?: unkId)
        ids.add(sepId)
        return ids.toLongArray()
    }

    /** Embeds each text; returns L2-normalized vectors, one per input text. */
    fun embed(texts: List<String>): Array<FloatArray> {
        if (texts.isEmpty()) return arrayOf()
        val encoded = texts.map { encode(it) }
        val batchLen = encoded.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val dim = probeDim(batchLen)

        val results = Array(texts.size) { FloatArray(dim) }
        val queue = ArrayDeque(encoded.indices.toList())
        while (queue.isNotEmpty()) {
            val batchRows = IntArray(minOf(16, queue.size)) { queue.removeFirst() }
            val flatIds = LongArray(batchRows.size * batchLen)
            val flatMask = LongArray(batchRows.size * batchLen)
            val flatTypes = LongArray(batchRows.size * batchLen)
            for (row in batchRows.indices) {
                val ids0 = encoded[batchRows[row]]
                for (p in 0 until batchLen) {
                    val idx = row * batchLen + p
                    flatIds[idx] = ids0.getOrNull(p) ?: padId
                    flatMask[idx] = if (p < ids0.size) 1L else 0L
                }
            }
            val shape = longArrayOf(batchRows.size.toLong(), batchLen.toLong())
            val inputs = buildInputs(shape, flatIds, flatMask, flatTypes)
            val output = session.run(inputs)
            try {
                @Suppress("UNCHECKED_CAST")
                val hidden = output[0].value as Array<Array<FloatArray>>
                for (row in batchRows.indices) {
                    val ids0 = encoded[batchRows[row]]
                    results[batchRows[row]] = meanPool(hidden[row], ids0.size)
                }
            } finally {
                output.close()
                for (t in inputs.values) t.close()
            }
        }
        return results
    }

    private fun buildInputs(shape: LongArray, ids: LongArray, mask: LongArray, types: LongArray): Map<String, OnnxTensor> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        inputs[idInputName] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        inputs[maskInputName] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
        if (needsTokenTypeIds && typeInputName != null) {
            inputs[typeInputName] = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)
        }
        return inputs
    }

    private fun probeDim(batchLen: Int): Int {
        val inputs = buildInputs(
            longArrayOf(1, batchLen.toLong()),
            LongArray(batchLen),
            LongArray(batchLen) { 1L },
            LongArray(batchLen),
        )
        val output = session.run(inputs)
        try {
            @Suppress("UNCHECKED_CAST")
            val hidden = output[0].value as Array<Array<FloatArray>>
            return hidden[0][0].size
        } finally {
            output.close()
            for (t in inputs.values) t.close()
        }
    }

    /** Mean pooling over attention positions, then L2 normalization. */
    private fun meanPool(hidden: Array<FloatArray>, maskLen: Int): FloatArray {
        val dim = if (hidden.isNotEmpty()) hidden[0].size else return FloatArray(0)
        val out = FloatArray(dim)
        var count = 0f
        for (p in 0 until minOf(maskLen, hidden.size)) {
            count += 1f
            val h = hidden[p]
            for (i in 0 until dim) out[i] += h[i]
        }
        if (count == 0f) return out
        for (i in 0 until dim) out[i] /= count
        var norm = 0.0
        for (i in 0 until dim) norm += out[i].toDouble() * out[i]
        norm = sqrt(norm)
        if (norm > 0) for (i in 0 until dim) out[i] = (out[i] / norm).toFloat()
        return out
    }

    override fun close() {
        session.close()
    }
}
