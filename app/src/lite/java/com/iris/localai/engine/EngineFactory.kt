package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.llama.LlamaEngine

/**
 * "lite" flavour (default, ~26 MB smaller): GGUF only. Google's LiteRT-LM runtime is the
 * single largest native library in the app, and every model it runs is also available as GGUF.
 */
object EngineFactory {
    val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF)

    fun npu(context: Context): NpuSupport? = null

    fun create(context: Context, format: ModelFormat): InferenceEngine = when (format) {
        ModelFormat.GGUF -> LlamaEngine()
        ModelFormat.LITERT -> throw EngineException("此版本仅支持 GGUF 模型。LiteRT-LM (.litertlm) 需安装 full 版本。")
        ModelFormat.MNN -> throw EngineException("此版本仅支持 GGUF 模型。MNN 模型需安装 full 版本。")
    }
}
