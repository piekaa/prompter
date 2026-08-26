package pl.piekoszek.prompter.core

/**
 * Tolerant word similarity in [0, 1] (PROJEKT 5.2):
 *  - exact match            → 1.0
 *  - match after diacritic  → 0.5
 *  - fuzzy (Levenshtein)    → capped at 0.3
 *  - [unk] / low confidence → 0.0 (handled by caller for conf)
 */
object Similarity {

    const val UNK = "[unk]"
    private const val FUZZY_CAP = 0.3

    fun sim(a: String, b: String): Double {
        if (a == UNK || b == UNK) return 0.0
        if (a == b) return 1.0
        val af = Normalizer.foldToken(a)
        val bf = Normalizer.foldToken(b)
        if (af.isEmpty() || bf.isEmpty()) return 0.0
        if (af == bf) return 0.5
        val dist = levenshtein(af, bf)
        val ratio = dist.toDouble() / maxOf(af.length, bf.length)
        // A word differing in half or more of its characters is a different word,
        // not a fuzzy match — otherwise near-identical tokens (w1/w2/w10…) create
        // phantom progress in the alignment.
        if (ratio >= 0.5) return 0.0
        return (1.0 - ratio).coerceIn(0.0, FUZZY_CAP)
    }

    /** Classic Levenshtein distance, single-row DP. */
    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
