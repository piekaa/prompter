package pl.piekoszek.prompter.core

/**
 * Builds Vosk grammar (dynamic vocabulary) from the target text (PROJEKT 3.2).
 *
 * A Vosk grammar is a JSON array of phrases; effectively it restricts the
 * vocabulary to the words appearing in those phrases. Words outside the
 * vocabulary land in `[unk]`. We therefore emit the individual words of the
 * (windowed) text plus a single `"[unk]"` phrase.
 *
 * Windowing keeps the grammar small for long scripts: the recognizer is
 * reconfigured live via `Recognizer.setGrammar()` as the speaker moves
 * (PROJEKT 8: "okienkowanie ±150 słów przez setGrammar() w locie").
 */
object GrammarBuilder {

    const val UNK = "[unk]"

    /** Default window radius around the current position (words, each side). */
    const val DEFAULT_RADIUS = 150

    /** All unique words of the text + `[unk]`. */
    fun full(words: List<String>): List<String> {
        val out = LinkedHashSet(words.filter { it.isNotBlank() })
        out.add(UNK)
        return out.toList()
    }

    /**
     * Words within `radius` of the current position + `[unk]`, de-duplicated.
     *
     * `position` is the committed word count (words spoken, `p ∈ [0, n]` —
     * same semantics as `AlignmentEngine.position()`), so the window is
     * centred on `words[position-1]`, the most recently spoken word.
     */
    fun window(words: List<String>, position: Int, radius: Int = DEFAULT_RADIUS): List<String> {
        if (words.isEmpty()) return listOf(UNK)
        val center = (position - 1).coerceIn(0, words.size - 1)
        val lo = (center - radius).coerceAtLeast(0)
        val hi = (center + radius).coerceAtMost(words.size - 1)
        if (lo == 0 && hi == words.size - 1) return full(words)
        val out = LinkedHashSet<String>()
        for (i in lo..hi) {
            val w = words[i]
            if (w.isNotBlank()) out.add(w)
        }
        out.add(UNK)
        return out.toList()
    }

    /** Serializes phrases to the JSON array format Vosk expects. */
    fun toJson(phrases: List<String>): String =
        phrases.joinToString(separator = ",", prefix = "[", postfix = "]") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}
