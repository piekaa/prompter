package pl.piekoszek.prompter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GrammarBuilderTest {

    @Test
    fun `full contains all unique words plus unk`() {
        val g = GrammarBuilder.full(listOf("a", "b", "a", "  ", "c"))
        assertEquals(listOf("a", "b", "c", GrammarBuilder.UNK), g)
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
    }
}
