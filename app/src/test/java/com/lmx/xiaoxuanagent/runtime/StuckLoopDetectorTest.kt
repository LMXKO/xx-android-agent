package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckLoopDetectorTest {
    @Test
    fun `short history is not stuck`() {
        assertFalse(StuckLoopDetector.detect(emptyList()).stuck)
        assertFalse(StuckLoopDetector.detect(listOf("a|click(e1)")).stuck)
        assertFalse(StuckLoopDetector.detect(listOf("a|click(e1)", "b|click(e2)")).stuck)
    }

    @Test
    fun `three identical fingerprints is identical_repeat`() {
        val verdict = StuckLoopDetector.detect(listOf("x|other()", "s1|click(e1)", "s1|click(e1)", "s1|click(e1)"))
        assertTrue(verdict.stuck)
        assertEquals("identical_repeat", verdict.pattern)
    }

    @Test
    fun `A-B-A-B is oscillation`() {
        val verdict = StuckLoopDetector.detect(listOf("A|click(e1)", "B|click(e2)", "A|click(e1)", "B|click(e2)"))
        assertTrue(verdict.stuck)
        assertEquals("oscillation", verdict.pattern)
    }

    @Test
    fun `dominant fingerprint over window is stuck`() {
        // one fingerprint appears 5 times interleaved with others, no clean repeat/oscillation tail
        val fps = listOf("A|x()", "B|y()", "A|x()", "C|z()", "A|x()", "D|w()", "A|x()", "A|x()")
        val verdict = StuckLoopDetector.detect(fps)
        assertTrue(verdict.stuck)
    }

    @Test
    fun `bouncing between two states over full window is low_diversity`() {
        val fps = listOf("A|a()", "A|a()", "B|b()", "B|b()", "A|a()", "A|a()", "B|b()", "B|b()")
        val verdict = StuckLoopDetector.detect(fps)
        assertTrue(verdict.stuck)
    }

    @Test
    fun `scrolling with changing signatures is not stuck`() {
        // same action label but every signature differs -> genuine progress, must NOT be flagged
        val fps = (1..StuckLoopDetector.WINDOW).map { "sig$it|scroll(screen,down)" }
        assertFalse(StuckLoopDetector.detect(fps).stuck)
    }

    @Test
    fun `diverse progress is not stuck`() {
        val fps = (1..StuckLoopDetector.WINDOW).map { "sig$it|action$it()" }
        assertFalse(StuckLoopDetector.detect(fps).stuck)
    }
}
