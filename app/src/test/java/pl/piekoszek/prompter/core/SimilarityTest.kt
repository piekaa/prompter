package pl.piekoszek.prompter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityTest {

    @Test
    fun `exact match is one`() {
        assertEquals(1.0, Similarity.sim("kot", "kot"), 1e-9)
    }

    @Test
    fun `diacritic difference scores half`() {
        assertEquals(0.5, Similarity.sim("sa", "są"), 1e-9)
        assertEquals(0.5, Similarity.sim("slowo", "słowo"), 1e-9)
    }

    @Test
    fun `unk always scores zero`() {
        assertEquals(0.0, Similarity.sim("[unk]", "kot"), 1e-9)
        assertEquals(0.0, Similarity.sim("kot", "[unk]"), 1e-9)
    }

    @Test
    fun `fuzzy is capped at point three`() {
        // "kot" vs "koty" — close, but must stay under the cap
        assertTrue(Similarity.sim("kot", "koty") <= 0.3 + 1e-9)
        assertTrue(Similarity.sim("kot", "koty") > 0.0)
    }

    @Test
    fun `half-different words score zero`() {
        // Near-identical tokens (differing in ≥50% of chars) are a mismatch,
        // otherwise they create phantom progress in alignment.
        assertEquals(0.0, Similarity.sim("w1", "w2"), 1e-9)
        assertEquals(0.0, Similarity.sim("skok", "taca"), 1e-9)
    }

    @Test
    fun `dissimilar words stay under the fuzzy cap`() {
        // Capped at 0.3, and below the 0.5 "diacritic match" tier.
        assertTrue(Similarity.sim("kot", "qwerty") <= 0.3 + 1e-9)
        assertTrue(Similarity.sim("kot", "qwerty") < 0.5)
    }

    @Test
    fun `levenshtein basics`() {
        assertEquals(3, Similarity.levenshtein("kitten", "sitting"))
        assertEquals(0, Similarity.levenshtein("abc", "abc"))
        assertEquals(3, Similarity.levenshtein("", "abc"))
    }

    @Test
    fun `punctuation difference is fuzzy not exact`() {
        // "kot." vs "kot" → after folding equal → 0.5
        assertEquals(0.5, Similarity.sim("kot.", "kot"), 1e-9)
    }
}
