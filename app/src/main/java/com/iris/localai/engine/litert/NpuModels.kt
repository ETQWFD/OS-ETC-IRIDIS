package com.iris.localai.engine.litert

import com.iris.localai.engine.NpuIds

/**
 * Recognises LiteRT-LM files AOT-compiled for a Qualcomm NPU, e.g. `gemma-4-E2B-it_qualcomm_sm8750.litertlm`
 * or `….qualcomm.sm8750.litertlm`. 2.0: thin wrapper over [NpuIds], the one rule the backend picker uses too.
 */
object NpuModels {
    /** Lower-case SoC id the file targets ("sm8750"), or null for a portable CPU/GPU model. */
    fun targetSoc(fileName: String): String? = NpuIds.targetSoc(fileName)

    /**
     * NPU graphs are compiled with a fixed KV-cache size (`_ekv1280_` = 1280 tokens). Asking LiteRT-LM
     * for a bigger context makes tensor allocation fail ("Failed to allocate tensors"), so NPU files
     * must use exactly this size, or the model's own default when the name doesn't say.
     */
    fun kvCapacity(fileName: String): Int? = NpuIds.kvCapacity(fileName)
}
