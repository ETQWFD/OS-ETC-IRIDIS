package com.iris.localai.v1109

import com.iris.localai.util.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class V1109Test {
    @Test fun phases() {
        assertEquals("思考中", Activity.phaseOf("<think>let me see"))
        assertEquals("生成回答", Activity.phaseOf("<think>a</think>好的，"))
        assertEquals("编写工具调用", Activity.phaseOf("<think>a</think>```tool\n{\"tool\": \"list_dir\""))
        assertEquals("编写工具调用", Activity.phaseOf("<tool_call>{\"name\""))
        assertEquals("编写工具调用", Activity.phaseOf("tool_call:list_dir{"))
        assertEquals("生成回答", Activity.phaseOf("这是一段代码：\n```kotlin\nval a = 1"))
    }

    @Test fun line() {
        assertEquals("思考中", Activity.line("思考中", 1))
        assertEquals("思考中 · 12 秒", Activity.line("思考中", 12))
    }

    @Test fun cancelTexts() {
        assert(com.iris.localai.engine.LoadCancel.NOTICE.contains("取消"))
    }
}
