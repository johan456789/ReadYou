package me.ash.reader.domain.model.insights

/**
 * Content-relative expected read time. Labels are ratios against this, so it
 * must reflect what the user will actually see: call it on the rendered
 * content (the feed-provided description by default; full text once a full
 * content parse exists). It is deliberately NOT computed from a readability
 * extraction of the linked page.
 *
 * CJK text is counted per character (whitespace tokenization silently zeroes
 * out Chinese), with per-script speeds: ~220 wpm alphabetic, ~400 chars/min
 * CJK. Constants are placeholders pending per-user calibration — labels derive
 * at training time from raw dwell, so improving this later re-labels history.
 */
object ExpectedReadTime {

    private const val ALPHABETIC_WPM = 220.0
    private const val CJK_CPM = 400.0
    private const val FLOOR_MS = 1_500L
    private const val CAP_MS = 90 * 60_000L

    fun estimateMs(html: String?): Long {
        if (html.isNullOrBlank()) return FLOOR_MS
        var alphabeticWords = 0
        var cjkChars = 0
        var inTag = false
        var currentWordLen = 0
        for (ch in html) {
            when {
                ch == '<' -> inTag = true
                ch == '>' -> inTag = false
                inTag -> Unit
                ch.isWhitespace() -> {
                    if (currentWordLen > 0) {
                        alphabeticWords++
                        currentWordLen = 0
                    }
                }
                isCjk(ch) -> {
                    if (currentWordLen > 0) {
                        alphabeticWords++
                        currentWordLen = 0
                    }
                    cjkChars++
                }
                else -> currentWordLen++
            }
        }
        if (currentWordLen > 0) alphabeticWords++
        val ms =
            (alphabeticWords / ALPHABETIC_WPM * 60_000.0) +
                (cjkChars / CJK_CPM * 60_000.0)
        return ms.toLong().coerceIn(FLOOR_MS, CAP_MS)
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || // CJK unified ideographs
            c in 0x3400..0x4DBF || // CJK extension A
            c in 0x3040..0x30FF || // hiragana + katakana
            c in 0xAC00..0xD7AF // hangul syllables
    }
}
