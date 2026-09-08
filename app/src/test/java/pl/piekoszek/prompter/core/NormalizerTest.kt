package pl.piekoszek.prompter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerTest {

    @Test
    fun `words lowercases strips punctuation and collapses whitespace`() {
        val words = Normalizer.words("  Witam, Świecie!   To   jest\ttest. ")
        assertEquals(listOf("witam", "świecie", "to", "jest", "test"), words)
    }

    @Test
    fun `words drops tokens that are pure punctuation`() {
        assertEquals(listOf("a", "b"), Normalizer.words("a — b"))
    }

    @Test
    fun `displayWords keeps case and trailing punctuation`() {
        assertEquals(
            listOf("Hello,", "świecie!", "it'", "s", "2026!"),
            Normalizer.displayWords("Hello, świecie! it's 2026!"),
        )
        assertEquals(listOf("well-", "known"), Normalizer.displayWords("well-known"))
        assertEquals(listOf("a", "b"), Normalizer.displayWords("a — b"))
        assertTrue(Normalizer.displayWords("   ").isEmpty())
    }

    @Test
    fun `displayWords has same count and order as words`() {
        for (t in listOf(
            "  Witam, Świecie!   To   jest\ttest. ",
            "a — b",
            "Hello, it's 2026! (ok)",
            "2026 roku",
            "well-known feature; it's a test.",
            "",
            "   ",
        )) {
            assertEquals("count parity for «$t»", Normalizer.words(t).size, Normalizer.displayWords(t).size)
        }
    }

    @Test
    fun `fold handles polish diacritics`() {
        assertEquals("lacznie", Normalizer.fold("łącznie"))
        assertEquals("czesc", Normalizer.fold("część"))
        assertEquals("zycze", Normalizer.fold("życze"))
    }

    @Test
    fun `foldToken strips punctuation and folds`() {
        assertEquals("sa", Normalizer.foldToken("Są,"))
        assertEquals("slowo", Normalizer.foldToken("słowo"))
    }

    @Test
    fun `foldToken keeps empty for pure noise`() {
        assertTrue(Normalizer.foldToken("---").isEmpty())
    }
}
