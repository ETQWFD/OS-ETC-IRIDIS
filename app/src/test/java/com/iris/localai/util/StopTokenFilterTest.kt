package com.iris.localai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTokenFilterTest {
    @Test fun stripsSplitEndToken() {
        val f = StopTokenFilter()
        val out = f.push("你好！<|im") + f.push("_end|>多余") + f.flush()
        assertEquals("你好！", out)
        assertTrue(f.stopped)
    }
    @Test fun keepsNormalAngleBrackets() {
        val f = StopTokenFilter()
        assertEquals("a<b c", f.push("a<b c") + f.flush())
    }
}
