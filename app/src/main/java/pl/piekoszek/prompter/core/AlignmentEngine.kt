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
 *  - Candidate search is local: p_prev .. p_prev + searchRadius.
 *  - Position only moves forward (monotonic alignment).
 *  - A candidate commits only if it beats the current position's score by the
 *    base margin (forwardMargin·W) AND it is either:
 *      · STRONG — ≥ strongMatchMin real (exact/diacritic) matches in the
 *        window: may sit up to searchRadius ahead. This is catch-up: when the
 *        speaker reads ahead or ASR misses a chunk, a multi-word match is
 *        decisive evidence of where the speaker actually is.
 *      · NEAR — within nearRadius of the current position: normal tracking.
 *    Weak matches (a few words) are distance-gated, so 1-2 coincidental words
 *    can't commit a large jump to a repeated/similar phrase ahead (observed
 *    bug: 1-2 spoken words highlighting ~20 target words).
 *  - Among committable candidates the highest score wins; ties keep the
 *    nearest occurrence (a repeated phrase resolves to the closest one).
 */
class AlignmentEngine(
    private val config: AlignmentConfig = AlignmentConfig(),
) {

    data class AlignmentConfig(
        /** Words compared per score (W). */
        val windowSize: Int = 6,
        /** Max forward distance (words) a candidate may be. Strong matches can
         *  commit up to here (catch-up after the speaker reads ahead). */
        val searchRadius: Int = 80,
        /** Base hysteresis factor: a candidate must beat the current position's
         *  score by factor·W to commit. Small, because the anti-jump protection
         *  is structural (see strongMatchMin / nearRadius), not margin-based. */
        val forwardMargin: Double = 0.15,
        /** "Strong match": at least this many window words must be real
         *  (exact/diacritic) matches. Strong candidates commit at any distance
         *  within searchRadius — this is what lets the engine catch up. */
        val strongMatchMin: Int = 4,
        /** Weak candidates (fewer than strongMatchMin real matches) may only
         *  commit within this many words of the current position. */
        val nearRadius: Int = 12,
        /** Words with conf below this contribute nothing to the score. */
        val confThreshold: Double = 0.5,
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

        // Find the best committable position ahead (strong or near, and
        // beating the current position). See committable()/bestPositionForward.
        val best = bestPositionForward(transcript, position, scoreAt(position, transcript))
        if (best != null) position = best

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
        val words = transcript + partialWords
        // Same committable rule as updateForPartial — a preview jump must be
        // evidence-backed too (strong or near, and beating the current position).
        val best = bestPositionForward(words, position, scoreAt(position, words))
        return best ?: position
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

    /** Base hysteresis margin a candidate must beat (forwardMargin·W). */
    private val baseMargin: Double get() = config.forwardMargin * config.windowSize

    /** Score + number of "real" (exact/diacritic) matches for candidate [i]. */
    private fun scoreAndMatch(i: Int, words: List<TranscriptWord>): Pair<Double, Int> {
        if (i <= 0 || words.isEmpty() || target.isEmpty()) return 0.0 to 0
        val w = minOf(windowOf(i), words.size)
        if (w <= 0) return 0.0 to 0
        val start = words.size - w
        var sum = 0.0
        var real = 0
        for (j in 0 until w) {
            val s = words[start + j]
            if (s.conf < config.confThreshold) continue
            val sim = Similarity.sim(s.text, target[i - w + j])
            sum += sim
            if (sim >= REAL_MATCH) real++
        }
        return sum to real
    }

    /** Score for candidate [i] (the [scoreAndMatch] sum). */
    private fun scoreAt(i: Int, words: List<TranscriptWord>): Double =
        scoreAndMatch(i, words).first

    /**
     * Can candidate [to] commit? It must beat the current position's score by
     * the base margin (anti-jitter + repeated-phrase overshoot guard), and be
     * either a strong match (any distance within the radius) or near.
     */
    private fun committable(
        to: Int,
        score: Double,
        real: Int,
        from: Int,
        prevScore: Double,
    ): Boolean {
        if (score <= prevScore + baseMargin) return false
        val distance = to - from
        return real >= config.strongMatchMin || distance <= config.nearRadius
    }

    /**
     * Best committable position ahead of [from] (null if none). Scans forward
     * only (position never moves back); highest score wins, ties keep the
     * nearest occurrence so a repeated phrase resolves to the closest one.
     */
    private fun bestPositionForward(
        words: List<TranscriptWord>,
        from: Int,
        prevScore: Double,
    ): Int? {
        val hi = (from + config.searchRadius).coerceAtMost(target.size)
        var bestI: Int? = null
        var bestS = -1.0
        for (i in (from + 1)..hi) {
            val (s, real) = scoreAndMatch(i, words)
            if (!committable(i, s, real, from, prevScore)) continue
            if (s > bestS) {
                bestS = s
                bestI = i
            }
        }
        return bestI
    }

    companion object {
        /** Memory cap on the kept transcript (words). */
        const val MAX_TRANSCRIPT_WORDS = 4000
        /** A word counts as a "real" match (toward strongMatchMin) if its
         *  similarity is at least this (exact = 1.0, diacritic = 0.5). Fuzzy
         *  matches (≤ 0.3) add score but don't count toward the strong signal. */
        private const val REAL_MATCH = 0.5
    }
}
