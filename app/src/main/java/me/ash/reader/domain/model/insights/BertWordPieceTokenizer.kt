package me.ash.reader.domain.model.insights

/**
 * Minimal BERT WordPiece tokenizer for bge-small-zh-v1.5, matching the
 * standard BERT preprocessing pipeline:
 *
 * 1. clean: drop control chars, normalize whitespace
 * 2. basic tokenize: split on whitespace + punctuation, with CJK
 *    characters split out per character (they carry meaning per glyph)
 * 3. lowercase where the vocab is cased accordingly — bge-small-zh keeps
 *    English case, so no lowercasing is applied
 * 4. WordPiece: greedy longest-match-first against the vocab, continuing
 *    pieces with the "##" prefix, unknowns mapped to [UNK]
 *
 * Internal consistency is what matters for the recommender: every vector
 * comes from this same tokenizer, so small deviations from the reference
 * Python implementation cancel out in similarity comparisons. The output is
 * [CLS] + pieces (+ [SEP]), truncated to [maxPieces].
 */
class BertWordPieceTokenizer(
    vocab: Map<String, Int>,
    private val maxPieces: Int = 510, // 512 minus [CLS] and [SEP]
    private val unkToken: String = "[UNK]",
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
) {

    private val vocabId: Map<String, Int> = vocab

    /** Vocab id of a token, or null when out-of-vocabulary. */
    fun tokenId(token: String): Int? = vocabId[token]
    private val vocabChars: Set<Char> =
        vocab.keys.filter { it.length == 1 && !it.startsWith("[") }.map { it[0] }.toSet()

    fun encode(text: String): List<Int> {
        val pieces = wordPiece(basicTokenize(text))
        val ids = ArrayList<Int>(pieces.size + 2)
        vocabId[clsToken]?.let { ids.add(it) }
        for (piece in pieces) ids.add(vocabId[piece] ?: vocabId[unkToken] ?: return emptyList())
        vocabId[sepToken]?.let { ids.add(it) }
        return ids
    }

    /** WordPiece over pre-split basic tokens; visible for unit tests. */
    internal fun wordPiece(basicTokens: List<String>): List<String> {
        val out = ArrayList<String>()
        for (token in basicTokens) {
            if (token.length <= maxPieces && vocabId.containsKey(token)) {
                out.add(token)
                continue
            }
            var start = 0
            var isUnknown = false
            while (start < token.length) {
                var end = minOf(token.length, start + maxPieces)
                var match: String? = null
                while (start < end) {
                    val piece = (if (start > 0) "##" else "") + token.substring(start, end)
                    if (vocabId.containsKey(piece)) {
                        match = piece
                        break
                    }
                    end--
                }
                if (match == null) {
                    isUnknown = true
                    break
                }
                out.add(match)
                start = end
            }
            if (isUnknown) out.add(unkToken)
        }
        return out
    }

    /** BERT basic tokenizer: whitespace split + punctuation isolation + CJK spacing. */
    internal fun basicTokenize(text: String): List<String> {
        val spaced = StringBuilder(text.length + 16)
        for (ch in text) {
            when {
                isCjk(ch) -> {
                    spaced.append(' ').append(ch).append(' ')
                }
                isPunctuation(ch) -> {
                    spaced.append(' ').append(ch).append(' ')
                }
                ch.isISOControl() || ch.isWhitespace() -> spaced.append(' ')
                else -> spaced.append(ch)
            }
        }
        return spaced.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF ||
            c in 0x3400..0x4DBF ||
            c in 0x3000..0x303F || // CJK punctuation
            c in 0xFF00..0xFFEF || // fullwidth forms
            c in 0x3040..0x30FF || // kana
            c in 0xAC00..0xD7AF // hangul
    }

    private fun isPunctuation(ch: Char): Boolean {
        // ASCII punctuation per the BERT reference + general punctuation ranges.
        if (ch.code in 33..47 || ch.code in 58..64 || ch.code in 91..96 || ch.code in 123..126) return true
        val type = Character.getType(ch)
        return type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }
}
