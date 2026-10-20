package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.ModelFormat
import com.iris.localai.engine.litert.LiteRtEngine
import com.iris.localai.engine.llama.LlamaEngine
import com.iris.localai.engine.mnn.MnnEngine

/** "full" flavour: GGUF (llama.cpp) + LiteRT-LM 0.18 + MNN-LLM 3.6.1. */
object EngineFactory {
    /**
     * ETC fork (OS-ETC-IRIDIS): LiteRT-LM's AAR ships only arm64-v8a / x86_64 — there is no
     * armeabi-v7a build. On 32-bit devices we therefore exclude LITERT so the model list,
     * settings and picker degrade gracefully instead of crashing on load. MNN is built for both
     * ABIs, so it stays. (SUPPORTED_ABIS[0] is the ABI the system actually runs.)
     */
    private val litertAvailable: Boolean =
        (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").let {
            it == "arm64-v8a" || it == "x86_64"
        }

    val supportedFormats: Set<ModelFormat> = buildSet {
        add(ModelFormat.GGUF)
        add(ModelFormat.MNN)
        if (litertAvailable) add(ModelFormat.LITERT)
    }

    fun npu(context: Context): NpuSupport = com.iris.localai.engine.litert.QualcommNpuRuntime.get(context)

    fun create(context: Context, format: ModelFormat): InferenceEngine = when (format) {
        ModelFormat.GGUF -> LlamaEngine()
        ModelFormat.LITERT -> if (litertAvailable) LiteRtEngine(context)
        else throw EngineException("LiteRT-LM 仅支持 64 位（arm64-v8a）设备；32 位手机请使用 GGUF 或 MNN 模型")
        ModelFormat.MNN -> MnnEngine(context)
    }
}
