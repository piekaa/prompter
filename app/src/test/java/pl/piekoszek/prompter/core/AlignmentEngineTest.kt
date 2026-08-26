package pl.piekoszek.prompter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentEngineTest {

    /**
     * 32 distinct Polish words, pairwise dissimilar (sim == 0 for any two),
     * so test expectations are exact under the scoring rules.
     */
    private val target = listOf(
        "kot", "dom", "las", "ryba", "jabłko", "stół", "okno", "drzwi",
        "chleb", "sól", "ogień", "woda", "niebo", "ziemia", "kolej", "list",
        "nuta", "pasek", "radość", "skok", "taca", "uniesienie", "wzrok", "zgoda",
        "abc", "def", "ghi", "jkl", "mno", "pqr", "stu", "vwx",
    )

    private fun feed(engine: AlignmentEngine, vararg chunk: String) {
        engine.updateFinal(chunk.map { TranscriptWord(it) })
    }

    @Test
    fun `tracks a clean transcript word by word`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        var prev = 0
        for (start in 0 until 32 step 4) {
            feed(engine, *target.subList(start, start + 4).toTypedArray())
            val pos = engine.position()
            assertTrue("position should not go backwards at chunk ${start / 4 + 1}: $pos < $prev", pos >= prev)
            assertEquals((start + 4).toLong(), pos.toLong())
            prev = pos
        }
        assertEquals(1f, engine.progress, 1e-6f)
    }

    @Test
    fun `tolerates word errors from ASR`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        // 2 out of 4 words replaced by garbage (small-model ASR misses)
        feed(engine, target[0], "xkcd", target[2], "qwerty")
        feed(engine, target[4], "zzzz", target[6], target[7])
        for (start in 8 until 32 step 8) {
            feed(engine, *target.subList(start, start + 8).toTypedArray())
        }
        assertEquals(32L, engine.position().toLong())
    }

    @Test
    fun `filler words like mmm do not derail tracking`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, "mmm", "mmm", "kot", "dom", "las", "ryba", "jabłko", "stół")
        assertTrue(engine.position() >= 5)
        feed(engine, *target.subList(6, 14).toTypedArray())
        assertEquals(14L, engine.position().toLong())
    }

    @Test
    fun `repetition does not pull position backwards`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, *target.subList(0, 12).toTypedArray())
        assertEquals(12L, engine.position().toLong())
        // speaker stumbles and repeats the last phrase
        feed(engine, target[10], target[11], target[10], target[11])
        assertTrue("repetition must not cause backward jump", engine.position() >= 12)
        // continuing should resume forward motion and settle exactly
        feed(engine, *target.subList(12, 16).toTypedArray())
        assertEquals(16L, engine.position().toLong())
    }

    @Test
    fun `position does not move backwards even with stronger backward evidence`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, *target.subList(0, 20).toTypedArray())
        assertEquals(20L, engine.position().toLong())
        // speaker rewinds and repeats an earlier part
        feed(engine, *target.subList(14, 18).toTypedArray())
        // position must not go below current - stays at 20 since no forward progress
        assertTrue("position must not move backwards", engine.position() >= 20)
    }

    @Test
    fun `unknown word stream does not drift position`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, *target.subList(0, 8).toTypedArray())
        assertEquals(8L, engine.position().toLong())
        // pure [unk] tokens (out-of-vocabulary speech) must not move anything:
        // best candidate scores 0, forward bias (≈0.12) is below the 0.9 margin
        feed(engine, Similarity.UNK, Similarity.UNK, Similarity.UNK,
            Similarity.UNK, Similarity.UNK, Similarity.UNK)
        assertEquals(8L, engine.position().toLong())
        feed(engine, Similarity.UNK, Similarity.UNK, Similarity.UNK,
            Similarity.UNK, Similarity.UNK, Similarity.UNK)
        assertEquals(8L, engine.position().toLong())
    }

    @Test
    fun `garbage word causes at most one word of temporary overshoot then recovers`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, *target.subList(0, 8).toTypedArray())
        assertEquals(8L, engine.position().toLong())
        // target[8] is completely garbled by ASR
        feed(engine, "qzxw")
        // overshoot by at most one (garbage shifts the window by one slot)
        assertEquals(9L, engine.position().toLong())
        // speaker then says target[8]..target[17] correctly → 18 words total spoken
        feed(engine, *target.subList(8, 18).toTypedArray())
        assertEquals(18L, engine.position().toLong())
    }

    @Test
    fun `low confidence words contribute nothing`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        engine.updateFinal(
            listOf(
                TranscriptWord("kot", 1.0), TranscriptWord("dom", 1.0),
                TranscriptWord("las", 1.0), TranscriptWord("ryba", 1.0),
                TranscriptWord("jabłko", 1.0), TranscriptWord("stół", 0.3),
            )
        )
        // still locks onto position 6 — a low-conf word must not block tracking
        assertEquals(6L, engine.position().toLong())
    }

    @Test
    fun `diacritic differences in transcript still align`() {
        val engine = AlignmentEngine()
        engine.setTarget(listOf("są", "wspaniale", "dziś", "pogoda", "jest", "słoneczna", "iść", "do", "pracy", "teraz"))
        feed(engine, "sa", "wspaniale", "dzis", "pogoda", "jest", "sloneczna", "isc", "do", "pracy", "teraz")
        assertEquals(10L, engine.position().toLong())
    }

    @Test
    fun `preview includes partial words but does not commit`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, "kot", "dom", "las", "ryba")
        assertEquals(4L, engine.position().toLong())
        val preview = engine.preview(listOf(TranscriptWord("jabłko"), TranscriptWord("stół")))
        assertEquals(6L, preview.toLong())
        assertEquals("preview must not commit", 4, engine.position())
        feed(engine, "jabłko", "stół")
        assertEquals(6L, engine.position().toLong())
    }

    @Test
    fun `resetTo jumps position and clears transcript`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, *target.subList(0, 12).toTypedArray())
        assertEquals(12L, engine.position().toLong())
        engine.resetTo(3)
        assertEquals(3L, engine.position().toLong())
        // fresh alignment from the new spot, old transcript ignored
        feed(engine, "ryba", "jabłko", "stół", "okno")
        assertEquals(7L, engine.position().toLong())
    }

    @Test
    fun `unknown word tokens never match`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, Similarity.UNK, Similarity.UNK, "kot", "dom", "las", "ryba")
        assertEquals(4L, engine.position().toLong())
    }

    @Test
    fun `empty transcript yields zero position`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        assertEquals(0L, engine.position().toLong())
        engine.updateFinal(emptyList())
        assertEquals(0L, engine.position().toLong())
    }
}
