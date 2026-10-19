package com.iris.localai.engine

/**
 * 1.9.0: what the chat screen says while a GPU load takes long.
 *
 * Why a first GPU load "never ends": LiteRT-LM's `Engine.initialize()` is one blocking native call
 * (shader compile + writing the GPU program cache to filesDir/litert-cache) with no progress
 * callback and no way to cancel it. LiteRtEngine only creeps its bar toward 90 % so it never looks
 * frozen, and EngineManager holds its load lock for the whole call, so nothing else can load
 * meanwhile. There is no timeout in the engine (and native code is not changed in 1.9.0), so the
 * app explains the wait after [NOTICE_SECS] and offers to switch the model to the CPU, which takes
 * effect by reloading as soon as the running load returns. Pure, unit-tested.
 */
object SlowLoadHint {
    const val NOTICE_SECS = 45L
    const val LONG_SECS = 180L

    /** Subtitle under the model name: 「加载中 · GPU · 63 秒」. */
    fun subtitle(backend: Backend?, secs: Long): String {
        val b = backend?.takeIf { it != Backend.CPU }?.let { " · ${it.label}" } ?: ""
        return if (secs > 0) "加载中$b · $secs 秒" else "加载中$b"
    }

    /** The explanation card, or null while the wait is still normal / not on the GPU. */
    fun notice(backend: Backend?, secs: Long): String? = when {
        backend != Backend.GPU || secs < NOTICE_SECS -> null
        secs < LONG_SECS -> "GPU 首次加载要编译着色器并写入缓存，可能需要几分钟，之后再加载会快很多。这一步无法中途取消。"
        else -> "GPU 已加载 ${secs / 60} 分 ${secs % 60} 秒仍未完成。部分模型（如 Qwen3.5、Agents-A1）在 GPU 上初始化很慢，建议改用 CPU。"
    }

    const val ACTION = "改用 CPU"
    const val SWITCHED = "已把这个模型的后端设为 CPU：当前这次加载一结束，就会自动用 CPU 重新加载"
}
