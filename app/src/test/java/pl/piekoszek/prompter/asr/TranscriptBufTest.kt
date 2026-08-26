package pl.piekoszek.prompter.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptBufTest {

    @Test
    fun `starts empty`() {
        val buf = TranscriptBuf()
        assertTrue(buf.isEmpty)
        assertTrue(buf.confirmedWords.isEmpty())
        assertTrue(buf.partialWords.isEmpty())
        assertFalse(buf.hasPartial)
    }

    @Test
    fun `partial replaces previous partial`() {
        val buf = TranscriptBuf()
        buf.onPartial("kot")
        buf.onPartial("kot dom")
        assertEquals(listOf("kot", "dom"), buf.partialWords)
        assertTrue("nothing confirmed yet", buf.isEmpty)
    }

    @Test
    fun `final commits words and clears partial`() {
        val buf = TranscriptBuf()
        buf.onPartial("kot dom")
        buf.onFinal("kot dom")
        assertEquals(listOf("kot", "dom"), buf.confirmedWords)
        assertFalse(buf.hasPartial)
        buf.onFinal("las")
        assertEquals(listOf("kot", "dom", "las"), buf.confirmedWords)
    }

    @Test
    fun `splits on any whitespace and drops empties`() {
        val buf = TranscriptBuf()
        buf.onFinal("  kot \t dom\n las  ")
        assertEquals(listOf("kot", "dom", "las"), buf.confirmedWords)
    }

    @Test
    fun `recent shows last words plus partial`() {
        val buf = TranscriptBuf()
        repeat(20) { buf.onFinal("slovo") }
        buf.onPartial("zywe")
        assertEquals("slovo slovo slovo slovo slovo zywe", buf.recent(5))
    }

    @Test
    fun `final after partial commits and clears partial`() {
        val buf = TranscriptBuf()
        buf.onFinal("a")
        buf.onPartial("b")
        buf.onFinal("b")
        assertEquals(listOf("a", "b"), buf.confirmedWords)
        assertFalse(buf.hasPartial)
    }

    @Test
    fun `reset clears everything`() {
        val buf = TranscriptBuf()
        buf.onFinal("a b")
        buf.onPartial("c")
        buf.reset()
        assertTrue(buf.isEmpty)
    }
}
