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

    /**
     * Feed new words to the engine. Appends to the transcript (old-style API).
     * Now implemented via updateForPartial with cumulative tracking.
     */
    private fun feed(engine: AlignmentEngine, vararg words: String) {
        engine.updateFinal(words.map { TranscriptWord(it) })
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

    /**
     * Regression (user report: a few spoken words highlighting ~20 words,
     * around 1/4 of a 2336-word script): the speaker says "domu lasu" and
     * ASR drops the inflections ("dom las"). The exact forms "dom las"
     * appear 20 words AHEAD in the text, so with a flat hysteresis margin
     * the later occurrence wins the scan (2.0 > 0.6 + 0.9) and ~20 unspoken
     * words commit at once. The distance-scaled margin must keep the
     * position local.
     */
    @Test
    fun `exact phrase 20 words ahead does not jump past fuzzy local match`() {
        val engine = AlignmentEngine()
        val fillers = listOf(
            "okno", "drzwi", "chleb", "sól", "ogień", "woda", "niebo", "ziemia",
            "kolej", "list", "nuta", "pasek", "radość", "skok", "taca",
            "uniesienie", "wzrok", "zgoda",
        )
        // 6 + 2 (fuzzy spot) + 18 fillers + 2 (exact spot, 20 ahead) + 2
        val t = listOf("kot", "dom", "las", "ryba", "jabłko", "stół") +
            listOf("domu", "lasu") + fillers + listOf("dom", "las", "mno", "pqr")
        engine.setTarget(t)
        feed(engine, "kot", "dom", "las", "ryba", "jabłko", "stół")
        assertEquals(6L, engine.position().toLong())
        // Fresh endpoint after a silence (VM replaces the transcript with the
        // new partial): speaker said "domu lasu" at the CURRENT spot, ASR
        // gave "dom las". The exact "dom las" sits 20 words ahead.
        engine.updateForPartial(listOf(TranscriptWord("dom"), TranscriptWord("las")))
        assertTrue(
            "must not jump to the exact phrase 20 words ahead: pos=${engine.position()}",
            engine.position() <= 12,
        )
    }

    /** A bulk feed larger than one score window still catches up fully. */
    @Test
    fun `bulk feed catches up fully`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, *target.subList(0, 16).toTypedArray())
        assertEquals(16L, engine.position().toLong())
        feed(engine, *target.subList(16, 32).toTypedArray())
        assertEquals(32L, engine.position().toLong())
    }

    /**
     * Preview was the most permissive path: best score wins, no margin,
     * full 40-word radius — a 2-word partial matching a word near the end
     * previewed a +26 jump. Now distance-margin-checked like the commit.
     */
    @Test
    fun `preview with weak partial does not jump far ahead`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        feed(engine, "kot", "dom", "las", "ryba", "jabłko", "stół")
        assertEquals(6L, engine.position().toLong())
        val preview = engine.preview(listOf(TranscriptWord("qzxw"), TranscriptWord("vwx")))
        assertTrue("preview must not jump far on weak evidence: $preview", preview <= 12)
        assertEquals("preview must not commit", 6, engine.position())
    }

    /**
     * Catch-up (user report: ASR missed a chunk, speaker read much further —
     * the engine no longer caught up). The speaker has read past the first
     * 6 words and is now on the last 5 words (a strong 5-word match ~26 words
     * ahead). A multi-word match is decisive evidence of position, so the
     * engine must jump there even though it is far — the weak/far case above
     * stays blocked, this strong/far case must commit.
     */
    @Test
    fun `strong phrase match ahead catches up after missed chunk`() {
        val engine = AlignmentEngine()
        engine.setTarget(target)
        // speaker reads the first 6 words
        feed(engine, *target.subList(0, 6).toTypedArray())
        assertEquals(6L, engine.position().toLong())
        // ASR missed the middle chunk; the speaker is now on the last 5 words.
        // (The VM replaces the transcript with the new partial.)
        engine.updateForPartial(target.subList(27, 32).map { TranscriptWord(it) })
        assertEquals(
            "strong 5-word match ahead must commit despite distance: pos=${engine.position()}",
            32L, engine.position().toLong(),
        )
    }

    /**
     * Contrast guard: a strong match far ahead commits, but a weak match far
     * ahead (only 2 words) must not — even though both are the same distance
     * from the current position. This is the line between legitimate catch-up
     * and a coincidental far jump.
     */
    @Test
    fun `weak match far ahead does not commit even at same distance`() {
        val engine = AlignmentEngine()
        // P(6) + 21 fillers + 2-word tail (target[30]=stu, target[31]=vwx)
        engine.setTarget(target)
        feed(engine, *target.subList(0, 6).toTypedArray())
        assertEquals(6L, engine.position().toLong())
        // speaker (per ASR) only produces 2 words that match the far tail;
        // not enough evidence to jump ~25 words.
        engine.updateForPartial(listOf(TranscriptWord("stu"), TranscriptWord("vwx")))
        assertTrue(
            "weak 2-word match must not commit a ~25-word jump: pos=${engine.position()}",
            engine.position() <= 12,
        )
    }
}
