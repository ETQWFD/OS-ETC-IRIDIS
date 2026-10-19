package com.iris.localai.data

import kotlinx.serialization.Serializable

/**
 * 1.7.8: the user's own setting for one resource tier on one backend kind (设置 › 性能与资源 ›
 * 资源档配置). [cpus] are core ids (cpu0 …); [nice] is the Linux nice value of the inference threads.
 * Stored in [AppSettings.tierProfiles] only after the user changed it; a missing entry means
 * "the default generated from this phone's core clusters".
 */
@Serializable
data class TierProfile(
    val threads: Int,
    val cpus: List<Int>,
    val nice: Int,
    /**
     * 1.7.9: GPU 性能提示 (GPU set only; null on the CPU set). Older stored entries have no such
     * field → null, filled with the tier's default by TierProfiles.sanitize / resolve.
     */
    val gpuHint: GpuPerfHint? = null,
)

/** 1.7.9: Adreno OpenCL perf hint (cl_qcom_perf_hint) — 省电 low / 普通 normal / 高 high. */
@Serializable
enum class GpuPerfHint(val label: String, val native: String) {
    LOW("省电", "low"),
    NORMAL("普通", "normal"),
    HIGH("高", "high"),
}
