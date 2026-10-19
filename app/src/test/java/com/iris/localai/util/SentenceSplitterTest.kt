package com.iris.localai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {
    private fun stream(text: String): List<String> {
        val s = SentenceSplitter()
        val out = ArrayList<String>()
        text.forEach { out += s.push(it.toString()) }
        out += s.flush()
        return out
    }

    @Test fun firstClauseCutEarly() {
        val out = stream("好的，没问题，我们先来看看今天的天气情况，然后再决定要不要出门散步。")
        assertTrue(out.first().length <= 12)
        assertEquals("好的，没问题，我们先来看看今天的天气情况，然后再决定要不要出门散步。".replace(" ", ""), out.joinToString("").replace(" ", ""))
    }

    @Test fun longRunWithoutPunctuationStillCut() {
        val out = stream("一".repeat(200))
        assertTrue(out.size >= 2)
    }

    @Test fun decimalsNotSplit() {
        val out = stream("圆周率约等于3.14。下一句")
        assertEquals("圆周率约等于3.14。", out.first())
    }
}
