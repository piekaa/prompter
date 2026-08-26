package pl.piekoszek.prompter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    private val words = (1..100).map { "word$it" }

    @Test
    fun `full contains all unique words plus unk`() {
        val g = GrammarBuilder.full(listOf("a", "b", "a", "  ", "c"))
        assertEquals(listOf("a", "b", "c", GrammarBuilder.UNK), g)
    }

    @Test
    fun `window is bounded around position`() {
        val g = GrammarBuilder.window(words, 50, radius = 5)
        assertTrue(g.contains("word45"))
        assertTrue(g.contains("word50"))
        assertTrue(g.contains("word55"))
        assertFalse("outside the window", g.contains("word44"))
        assertFalse("outside the window", g.contains("word56"))
        assertTrue(g.contains(GrammarBuilder.UNK))
    }

    @Test
    fun `window clamps at text boundaries`() {
        val g = GrammarBuilder.window(words, 0, radius = 5)
        assertTrue(g.contains("word1"))
        assertFalse(g.contains("word8"))
        val tail = GrammarBuilder.window(words, 99, radius = 5)
        assertTrue(tail.contains("word100"))
        assertTrue(tail.contains(GrammarBuilder.UNK))
    }

    @Test
    fun `window covering whole text degrades to full`() {
        val small = listOf("a", "b", "c")
        assertEquals(GrammarBuilder.full(small), GrammarBuilder.window(small, 1, radius = 10))
    }

    @Test
    fun `toJson emits a valid json array`() {
        val json = GrammarBuilder.toJson(listOf("hello", "world", "[unk]"))
        assertEquals("""["hello","world","[unk]"]""", json)
    }

    @Test
    fun `toJson escapes quotes and backslashes`() {
        val json = GrammarBuilder.toJson(listOf("say \"hi\"", "a\\b"))
        assertEquals("""["say \"hi\"","a\\b"]""", json)
    }

    @Test
    fun `empty input yields just unk`() {
        assertEquals(listOf(GrammarBuilder.UNK), GrammarBuilder.full(emptyList()))
        assertEquals(listOf(GrammarBuilder.UNK), GrammarBuilder.window(emptyList(), 0))
    }
}
