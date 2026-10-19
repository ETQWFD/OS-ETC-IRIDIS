package com.iris.localai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplySpeedTest {
    @Test fun countsCodePointsWithoutWhitespaceOrThinkTags() {
        assertEquals(2, ReplySpeed.countChars("你好"))
        assertEquals(5, ReplySpeed.countChars("<think>ab</think> c d e\n"))
        assertEquals(1, ReplySpeed.countChars("😀"))
        assertEquals(0, ReplySpeed.countChars("  \n"))
    }

    @Test fun formatsSpeedAndTotal() {
        assertEquals("25 字/秒 · 用时 4.5s", ReplySpeed.format(100, 4000, 4500))
        assertEquals("2.5 字/秒 · 用时 12s", ReplySpeed.format(25, 10_000, 12_000))
        // Too little output for a speed: only the time.
        assertEquals("用时 0.8s", ReplySpeed.format(1, 100, 800))
        assertNull(ReplySpeed.format(0, 0, 0))
    }

    @Test fun timerMeasuresFirstOutputToEnd() {
        var now = 1_000L
        val t = ReplyTimer { now }
        now = 1_600L          // prefill: nothing yet
        t.onText("  ")        // whitespace does not start the clock
        now = 2_000L
        t.onText("你好")       // first output
        now = 3_000L
        t.onText("<think>")   // tag only, no chars
        t.onText("世界!")
        now = 4_000L
        t.finish()
        now = 9_000L          // later calls do not move the end
        t.onText("ignored")
        assertEquals(5, t.chars)
        assertEquals(2_000L, t.outputMs())
        assertEquals(3_000L, t.totalMs())
    }

    @Test fun stoppedBeforeAnyOutput() {
        var now = 0L
        val t = ReplyTimer { now }
        now = 700L
        t.finish()
        assertEquals(0, t.chars)
        assertEquals(0L, t.outputMs())
        assertEquals("用时 0.7s", ReplySpeed.format(t.chars, t.outputMs(), t.totalMs()))
    }
}
