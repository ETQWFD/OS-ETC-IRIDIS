package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.litert.LiteRtEngine
import com.iris.localai.engine.llama.LlamaEngine
import com.iris.localai.engine.mnn.MnnEngine

/** "full" flavour: GGUF (llama.cpp) + LiteRT-LM 0.18 + MNN-LLM 3.6.1. */
object EngineFactory {
    val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF, ModelFormat.LITERT, ModelFormat.MNN)

    fun npu(context: Context): NpuSupport = com.iris.localai.engine.litert.QualcommNpuRuntime.get(context)

    fun create(context: Context, format: ModelFormat): InferenceEngine = when (format) {
        ModelFormat.GGUF -> LlamaEngine()
        ModelFormat.LITERT -> LiteRtEngine(context)
        ModelFormat.MNN -> MnnEngine(context)
    }
}
