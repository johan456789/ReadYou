package me.ash.reader.domain.model.insights

/**
 * Catalog of embeddable models for the reading recommender (M3). The UI
 * renders this list — never hardcode model entries in composables — and may
 * only write [EmbeddingModelCatalog.Entry.id] values into
 * `PreferencesKey.embeddingsModelId` for entries with [Entry.available].
 *
 * The model + tokenizer files are NOT bundled in the APK: the download worker
 * fetches them (WiFi-only, sha256-verified) into app-private storage on first
 * enable. Available URLs point at the transformer.js ONNX export of the
 * sentence-transformers model, quantized to int8 for the ~25 MB target.
 */
object EmbeddingModelCatalog {

    data class Entry(
        val id: String,
        /** UI-facing name. */
        val label: String,
        /** UI-facing description (languages + size). */
        val description: String,
        /** Download URL of the ONNX model file. */
        val modelUrl: String,
        /** sha256 hex of the model file — the download worker verifies it. */
        val modelSha256: String,
        /** Download URL of the WordPiece vocab file. */
        val vocabUrl: String,
        /** sha256 hex of the vocab file. */
        val vocabSha256: String,
        /** Whether this model can actually be downloaded and used today. */
        val available: Boolean,
        /** The preset used until the user picks otherwise. */
        val preset: Boolean = false,
    )

    val entries: List<Entry> =
        listOf(
            Entry(
                id = "bge-small-zh-v1.5",
                label = "BGE small zh v1.5",
                description = "Chinese + English (~25 MB)",
                modelUrl = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx",
                modelSha256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc",
                vocabUrl = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json",
                vocabSha256 = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26",
                available = true,
                preset = true,
            ),
            Entry(
                id = "multilingual-minilm-l12",
                label = "Multilingual MiniLM L12",
                description = "50+ languages — coming soon",
                modelUrl = "",
                modelSha256 = "",
                vocabUrl = "",
                vocabSha256 = "",
                available = false,
            ),
        )

    fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }

    fun preset(): Entry = entries.first { it.preset }
}
