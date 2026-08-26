package pl.piekoszek.prompter.core

/**
 * Position tracking: (target text, noisy transcript) → how many words of the
 * target have been spoken (PROJEKT 5.2).
 *
 * Pure JVM logic — no Android dependencies, unit-tested with fake transcripts.
 *
 * Model:
 *  - `position` p ∈ [0, n] — number of target words considered spoken.
 *  - For a candidate p=i we score the last W transcript words against
 *    target[i-W+1 .. i]:  score(i) = Σ sim(s[j], t[...]).
 *  - Candidate search is local: p_prev ± searchRadius (cheap, matches speech rate).
 *  - Position only moves forward (monotonic alignment).
 *  - Hysteresis: move only if the best score beats the current position's score
 *    by margin = factor · W.
 *  - Forward bias: small bonus for i ≥ p_prev (speech flows forward).
 */
class AlignmentEngine(
    private val config: AlignmentConfig = AlignmentConfig(),
) {

    data class AlignmentConfig(
        /** Words compared per score (W). */
        val windowSize: Int = 6,
        /** Local search radius around current position (words). */
        val searchRadius: Int = 40,
        /** Margin factor: move when best > current + factor·W. */
        val forwardMargin: Double = 0.15,
        /** Words with conf below this contribute nothing to the score. */
        val confThreshold: Double = 0.5,
        /** Small per-word bonus for forward candidates (monotonic bias). */
        val forwardBias: Double = 0.02,
    )

    data class Update(
        val position: Int,
        val moved: Boolean,
        val bestScore: Double,
        val prevScore: Double,
    )

    private var target: List<String> = emptyList()
    private var position: Int = 0
    private var transcript: List<TranscriptWord> = emptyList()

    val targetSize: Int get() = target.size

    /** Committed position (words spoken). */
    fun position(): Int = position

    val progress: Float
        get() = if (target.isEmpty()) 0f
        else (position.toFloat() / target.size).coerceIn(0f, 1f)

    /** Sets the target text; words are normalized (lowercase, punctuation-free). */
    fun setTarget(words: List<String>) {
        target = words.map { Normalizer.foldToken(it) }
            .filter { it.isNotEmpty() }
        position = 0
        transcript = emptyList()
    }

    // For tests: track cumulatively fed words
    internal var testFedWords: List<String> = emptyList()
        private set

    /**
     * Update position based on partial transcript (all words spoken so far).
     * The partial is live and may still change, but we track position based
     * on the best alignment found. Position only moves forward.
     *
     * This is the main entry point for alignment in partial-only mode.
     */
    fun updateForPartial(words: List<TranscriptWord>): Int {
        if (target.isEmpty()) return position

        // Store the full transcript for scoring
        transcript = if (words.size > MAX_TRANSCRIPT_WORDS)
            words.takeLast(MAX_TRANSCRIPT_WORDS) else words

        // Find best position using hysteresis (forward-only with margin)
        val (best, bestScore) = bestPositionForward(transcript, position)
        val prevScore = scoreAt(position, transcript)
        val margin = config.forwardMargin * windowOf(best)
        val moved = best != position && bestScore > prevScore + margin
        if (moved) position = best

        return position
    }

    /**
     * Alias for updateForPartial - used by tests for backwards compatibility.
     * In the old code, this was append-only; now we feed all words cumulatively.
     */
    fun updateFinal(words: List<TranscriptWord>): Int {
        // For tests: track words cumulatively to simulate old behavior
        testFedWords = testFedWords + words.map { it.text }
        val allWords = testFedWords.map { TranscriptWord(it) }
        return updateForPartial(allWords)
    }

    /**
     * Tentative position including the live partial (for a soft UI preview).
     * Does NOT update internal state - pure calculation.
     */
    fun preview(partialWords: List<TranscriptWord>): Int {
        if (partialWords.isEmpty() || target.isEmpty()) return position
        return bestPositionForward(transcript + partialWords, position).first
    }

    /**
     * Jumps the committed position (manual override) and clears the transcript,
     * so alignment resumes fresh from the new spot (PROJEKT 5.4).
     */
    fun resetTo(wordIndex: Int) {
        position = wordIndex.coerceIn(0, target.size)
        transcript = emptyList()
    }

    fun reset() {
        position = 0
        transcript = emptyList()
    }

    // -- internals ---------------------------------------------------------

    private fun windowOf(i: Int): Int = if (i <= 0) 0 else minOf(config.windowSize, i)

    private fun scoreAt(i: Int, words: List<TranscriptWord>): Double {
        if (i <= 0 || words.isEmpty() || target.isEmpty()) return 0.0
        val w = minOf(windowOf(i), words.size)
        if (w <= 0) return 0.0
        val start = words.size - w
        var sum = 0.0
        for (j in 0 until w) {
            val s = words[start + j]
            if (s.conf < config.confThreshold) continue
            sum += Similarity.sim(s.text, target[i - w + j])
        }
        return sum
    }

    private fun bestPositionForward(words: List<TranscriptWord>, from: Int): Pair<Int, Double> {
        // Only search forward from current position (position only moves forward).
        val hi = (from + config.searchRadius).coerceAtMost(target.size)
        var bestI = from.coerceIn(0, target.size)
        var bestS = scoreAt(bestI, words)
        for (i in from..hi) {
            if (i == bestI) continue
            var s = scoreAt(i, words)
            s += config.forwardBias * windowOf(i)
            if (s > bestS) {
                bestS = s
                bestI = i
            }
        }
        return bestI to bestS
    }

    companion object {
        /** Memory cap on the kept transcript (words). */
        const val MAX_TRANSCRIPT_WORDS = 4000
    }
}
