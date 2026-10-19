package com.iris.localai.util

/** 1.10.12: which model files may read images. */
object VisionModels {
    private val LITERT_HINTS = listOf("gemma-3n", "gemma3n", "gemma_3n", "gemma-4", "gemma4", "gemma_4", "vision", "-vl", "_vl", "vlm", "omni", "fastvlm", "smolvlm")
    fun litertMayHaveVision(fileName: String): Boolean {
        val n = fileName.lowercase()
        return LITERT_HINTS.any { n.contains(it) }
    }
}
