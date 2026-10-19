package com.iris.localai.core

import com.iris.localai.data.MnnModels
import com.iris.localai.data.ModelNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnModelsTest {
    @Test fun recognisesTaobaoMnnFolders() {
        assertTrue(MnnModels.isModelFileSet(setOf("config.json", "llm.mnn", "llm.mnn.weight", "llm_config.json", "tokenizer.txt")))
        assertFalse(MnnModels.isModelFileSet(setOf("llm.mnn", "llm.mnn.weight")))
        assertFalse(MnnModels.isModelFileSet(setOf("config.json", "model.safetensors")))
    }

    @Test fun skipsRepoClutter() {
        assertFalse(MnnModels.shouldCopy(".gitattributes"))
        assertFalse(MnnModels.shouldCopy("README.md"))
        assertTrue(MnnModels.shouldCopy("llm.mnn.weight"))
        assertTrue(MnnModels.shouldCopy("visual.mnn"))
    }

    @Test fun parsesRepoIds() {
        assertEquals("taobao-mnn/Qwen3-1.7B-MNN", MnnModels.parseRepoId("taobao-mnn/Qwen3-1.7B-MNN"))
        assertEquals("taobao-mnn/Qwen3-1.7B-MNN", MnnModels.parseRepoId(" https://huggingface.co/taobao-mnn/Qwen3-1.7B-MNN/tree/main "))
        assertEquals("taobao-mnn/Qwen3-0.6B-MNN", MnnModels.parseRepoId("https://hf-mirror.com/taobao-mnn/Qwen3-0.6B-MNN"))
        assertEquals("taobao-mnn/Qwen3-4B-MNN", MnnModels.parseRepoId("Qwen3-4B-MNN"))
        assertNull(MnnModels.parseRepoId("a/b/c"))
        assertNull(MnnModels.parseRepoId("bad name/x"))
    }

    @Test fun displayName() {
        assertEquals("Qwen3 1.7B", ModelNames.displayMnn("Qwen3-1.7B-MNN"))
        assertEquals("Qwen3.5 2B", ModelNames.displayMnn("Qwen3.5-2B-MNN"))
    }
}
