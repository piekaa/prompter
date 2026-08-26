package pl.piekoszek.prompter.asr

/**
 * Accumulates recognized words (PROJEKT 4: TranscriptBuf).
 *
 * Confirmed words come from `onResult`/`onFinalResult` (final, append-only);
 * the partial string is live and may change. SpeechService delivers plain
 * strings, so words are a plain whitespace split.
 *
 * Pure JVM — unit-testable without Android.
 */
class TranscriptBuf {

    private val confirmed = ArrayList<String>()
    private var partialText: String = ""

    /** Final words so far (append-only). */
    val confirmedWords: List<String> get() = confirmed

    /** Live partial words (may still change). */
    val partialWords: List<String>
        get() = if (partialText.isEmpty()) emptyList()
        else partialText.split(WHITESPACE).filter { it.isNotEmpty() }

    val hasPartial: Boolean get() = partialText.isNotEmpty()

    /** True while no sentence has been finalized (partial doesn't count). */
    val isEmpty: Boolean get() = confirmed.isEmpty()

    /** Sets the live partial (replaces the previous one). */
    fun onPartial(text: String) {
        partialText = text.trim()
    }

    /** Commits a finalized sentence (clears the live partial). */
    fun onFinal(text: String) {
        for (w in text.split(WHITESPACE)) {
            if (w.isNotEmpty()) confirmed.add(w)
        }
        partialText = ""
    }

    /** Clears everything (new session / manual jump). */
    fun reset() {
        confirmed.clear()
        partialText = ""
    }

    /**
     * A short displayable view of the transcript: the last [maxWords]
     * confirmed words plus the live partial. For the "żywy transcript" UI.
     */
    fun recent(maxWords: Int = 16): String {
        val tail = confirmed.takeLast(maxWords)
        return if (hasPartial) tail.joinToString(" ") + " " + partialText
        else tail.joinToString(" ")
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
