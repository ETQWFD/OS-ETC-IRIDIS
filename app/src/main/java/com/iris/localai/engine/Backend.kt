package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.LocalModel
import com.iris.localai.data.ModelFormat

/**
 * 1.7.1: one backend abstraction for all three engines. Every engine declares which of these it
 * can run a given model on ([BackendCapabilities]); [EngineManager] picks one, loads, falls back
 * along [BackendPlanner.chain] (NPU → GPU → CPU), remembers native crashes ([BackendCrashMemory])
 * and reports the backend that is really in use ([ModelDetails.activeBackend]).
 */
/** ETC fork (OS-ETC-IRIDIS): arm64 runs the dotprod/i8mm build; armeabi-v7a runs the NEON build. */
internal val isArm64: Boolean =
    android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true

enum class Backend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU");

    companion object {
        /** Fallback order, most specialised first. */
        val FALLBACK_ORDER = listOf(NPU, GPU, CPU)

        fun parse(s: String?): Backend? = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** One selectable backend for one model, with the reason when it cannot be used. */
data class BackendOption(val backend: Backend, val available: Boolean, val reason: String? = null, val detail: String = "")

/** What the device offers; gathered once per process (see [BackendEnvironment.probe]). */
data class BackendEnvironment(
    val hasOpenCl: Boolean,
    /** A Qualcomm NPU this build can drive (SoC with a matching LiteRT dispatch library). */
    val npuSoc: String?,
    /** QAIRT / dispatch runtime installed (downloaded in settings). */
    val npuRuntimeInstalled: Boolean,
    /** The full flavour ships LiteRT-LM and MNN. */
    val fullFlavor: Boolean,
) {
    companion object {
        private val OPENCL_PATHS = listOf(
            // 64-bit
            "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
            "/vendor/lib64/egl/libGLES_mali.so",
            // ETC fork (OS-ETC-IRIDIS): 32-bit devices keep the vendor libs under /vendor/lib.
            "/vendor/lib/libOpenCL.so", "/system/vendor/lib/libOpenCL.so", "/system/lib/libOpenCL.so",
            "/vendor/lib/egl/libGLES_mali.so",
        )

        /** 2.0: base id (SM8750P / SM8750-AB → sm8750), the same rule the LiteRT engine uses ([NpuIds]). */
        private fun socModel(): String? =
            if (android.os.Build.VERSION.SDK_INT >= 31) NpuIds.baseSocId(android.os.Build.SOC_MODEL) else null

        fun probe(context: Context): BackendEnvironment {
            val npu = runCatching { EngineFactory.npu(context) }.getOrNull()
            return BackendEnvironment(
                hasOpenCl = OPENCL_PATHS.any { java.io.File(it).exists() },
                npuSoc = npu?.takeIf { it.chipLabel != null }?.let { socModel() },
                npuRuntimeInstalled = npu?.state?.value is NpuState.Installed,
                // ETC fork: on 32-bit the full flavour still ships MNN (LiteRT-LM is arm64-only,
                // see the full EngineFactory), so "full" is any extra engine being present.
                fullFlavor = EngineFactory.supportedFormats.any { it != ModelFormat.GGUF },
            )
        }
    }
}

/** Which features an engine supports for a model; the model settings page greys out the rest. */
data class EngineFeatures(
    val contextShift: Boolean = false,
    val flashAttention: Boolean = false,
    val kvQuant: Boolean = false,
    val mmap: Boolean = false,
    val minP: Boolean = true,
    val repeatPenalty: Boolean = true,
    val presenceFrequency: Boolean = false,
    val seed: Boolean = false,
    val batchSize: Boolean = false,
    /** Thread count can change between replies without reloading. */
    val liveThreads: Boolean = false,
    /** Context length is fixed by the model file (LiteRT NPU `_ekvNNNN`). */
    val fixedContext: Int? = null,
)

object BackendCapabilities {
    /** Matches `_sm8750` / `.sm8750.` / `-qcs8550.` in LiteRT-LM file names: AOT-compiled for one Qualcomm NPU ([NpuIds]). */
    fun npuTarget(fileName: String): String? = NpuIds.targetSoc(fileName)
    fun npuKv(fileName: String): Int? = NpuIds.kvCapacity(fileName)

    fun options(format: ModelFormat, fileName: String, env: BackendEnvironment): List<BackendOption> {
        val noCl = "本机没有可用的 OpenCL 驱动"
        return when (format) {
            ModelFormat.GGUF -> listOf(
                BackendOption(Backend.CPU, true, detail = if (isArm64) "llama.cpp · ARM KleidiAI" else "llama.cpp · NEON"),
                BackendOption(Backend.GPU, env.hasOpenCl, if (env.hasOpenCl) null else noCl, "Adreno · OpenCL（Q4_0 / Q8_0 最快）"),
                BackendOption(Backend.NPU, false, "llama.cpp 没有高通 NPU 后端；NPU 请用 _sm8750 编译的 .litertlm 模型"),
            )
            ModelFormat.LITERT -> {
                if (!env.fullFlavor) return listOf(
                    BackendOption(Backend.CPU, false, "此版本不含 LiteRT-LM"),
                    BackendOption(Backend.GPU, false, "此版本不含 LiteRT-LM"),
                    BackendOption(Backend.NPU, false, "此版本不含 LiteRT-LM"),
                )
                val target = npuTarget(fileName)
                if (target != null) {
                    // AOT-compiled for one NPU: the graph cannot run anywhere else.
                    val onlyNpu = "这是 ${target.uppercase()} NPU 专用编译的模型，只能在 NPU 上运行"
                    val npuReason = when {
                        env.npuSoc == null -> "本机芯片没有受支持的高通 NPU"
                        !NpuIds.socMatches(target, env.npuSoc) -> "模型为 ${target.uppercase()} 编译，本机是 ${env.npuSoc.uppercase()}"
                        !env.npuRuntimeInstalled -> "需要先在 设置 › 模型与后端 下载 NPU 运行库"
                        else -> null
                    }
                    listOf(
                        BackendOption(Backend.CPU, false, onlyNpu),
                        BackendOption(Backend.GPU, false, onlyNpu),
                        BackendOption(Backend.NPU, npuReason == null, npuReason, "Hexagon · QNN"),
                    )
                } else listOf(
                    BackendOption(Backend.CPU, true, detail = "XNNPACK"),
                    // 1.10.3: an explicit GPU pick is still honoured, but say what it costs.
                    BackendOption(Backend.GPU, true, detail = "Adreno · ML Drift · $LITERT_GPU_WARNING"),
                    BackendOption(Backend.NPU, false, "通用 .litertlm 不能上 NPU；需要带 _${env.npuSoc ?: "sm8750"} 的 NPU 编译版"),
                )
            }
            ModelFormat.MNN -> {
                if (!env.fullFlavor) return listOf(
                    BackendOption(Backend.CPU, false, "此版本不含 MNN"),
                    BackendOption(Backend.GPU, false, "此版本不含 MNN"),
                    BackendOption(Backend.NPU, false, "此版本不含 MNN"),
                )
                listOf(
                    BackendOption(Backend.CPU, true, detail = if (isArm64) "ARMv8.2 FP16 · KleidiAI" else "NEON"),
                    BackendOption(Backend.GPU, env.hasOpenCl, if (env.hasOpenCl) null else noCl, "OpenCL"),
                    BackendOption(Backend.NPU, false, "未编入 MNN 的 QNN 后端（需按芯片离线转换的专用模型）"),
                )
            }
        }
    }

    fun features(format: ModelFormat, fileName: String, backend: Backend? = null): EngineFeatures = when (format) {
        ModelFormat.GGUF -> EngineFeatures(
            contextShift = true, flashAttention = true, kvQuant = true, mmap = true,
            presenceFrequency = true, seed = true, batchSize = true, liveThreads = true,
        )
        ModelFormat.LITERT -> EngineFeatures(
            minP = false, repeatPenalty = false,
            // 2.0: compiled KV size, else the family default (Gemma 4 NPU 4096, unknown 1024).
            fixedContext = if (npuTarget(fileName) != null) NpuIds.historyBudget(fileName) else null,
        )
        ModelFormat.MNN -> EngineFeatures(mmap = true)
    }

    /** Reason context shift is unavailable for this engine, or null when it is supported. */
    fun contextShiftUnsupported(format: ModelFormat?): String? = when (format) {
        ModelFormat.GGUF, null -> null
        ModelFormat.LITERT -> "LiteRT-LM 的会话 KV 由运行时管理，不支持上下文平移；将按 token 预算截断"
        ModelFormat.MNN -> "MNN 的 prompt cache 不支持位置平移；将按 token 预算截断"
    }

    /**
     * The backend "自动" picks: NPU-only files → NPU; LiteRT → GPU (Qwen3.5 / Agents-A1 → CPU, its GPU path
     * needs fp32 activations and minutes of init); GGUF / MNN → CPU (decode is bandwidth-bound and
     * the CPU path is the most robust). [allowGpu] = false keeps AUTO off the GPU (≤ 1.7.7 the 省电
     * tier did that; 1.7.8 never passes false any more — the tier only changes CPU resources).
     */
    fun autoBackend(format: ModelFormat, fileName: String, options: List<BackendOption>, allowGpu: Boolean = true): Backend {
        val avail = options.filter { it.available }.map { it.backend }.toSet()
        val wanted = when {
            Backend.NPU in avail -> Backend.NPU
            format == ModelFormat.LITERT && allowGpu && !prefersCpu(fileName) && Backend.GPU in avail -> Backend.GPU
            else -> Backend.CPU
        }
        return if (wanted in avail) wanted else options.firstOrNull { it.available }?.backend ?: Backend.CPU
    }

    /**
     * 1.7.8: the backend a load starts with. The user's explicit choice (model setting or the
     * engine's default backend) always wins and is independent of the resource tier; only 自动
     * ([explicit] == null) applies [autoBackend]'s fixed per-format policy.
     */
    fun preferred(explicit: Backend?, format: ModelFormat, fileName: String, options: List<BackendOption>): Backend =
        explicit ?: autoBackend(format, fileName, options, allowGpu = true)

    /** 1.10.3: shown on the LiteRT GPU option (model settings / default backend). */
    const val LITERT_GPU_WARNING = "首次加载需编译，可能要几分钟"

    /**
     * LiteRT models 自动 keeps on the CPU. Qwen3.5 (≤ 1.8.0) and, 1.9.0, Agents-A1 (a Qwen3.5-based
     * tool-calling fine-tune, e.g. `Agents-A1-4B_mixed_int4.litertlm`): on the GPU their first load
     * sat on 「加载中」 for minutes (shader compile + GPU cache, one blocking native call).
     */
    fun prefersCpu(fileName: String): Boolean {
        val n = fileName.lowercase()
        return CPU_FIRST.any { it in n }
    }

    /** Lower-case file-name fragments of [prefersCpu]. */
    val CPU_FIRST = listOf("qwen3.5", "qwen3_5", "qwen35", "agents-a1", "agents_a1")

    /** History budget of an NPU file of an unknown family without `_ekvNNNN` (Gemma 4: [NpuIds.GEMMA4_NPU_KV]). */
    const val NPU_DEFAULT_CONTEXT = NpuIds.UNKNOWN_NPU_KV
}

object BackendPlanner {
    /**
     * Load order for a model: the chosen backend first, then every *available* backend below it in
     * NPU → GPU → CPU order, skipping backends that crashed the process for this model before.
     * An explicitly unavailable choice falls straight down the chain.
     */
    fun chain(preferred: Backend, options: List<BackendOption>, crashed: Set<Backend> = emptySet()): List<Backend> {
        val avail = options.filter { it.available }.map { it.backend }.toSet()
        val start = Backend.FALLBACK_ORDER.indexOf(preferred)
        val out = Backend.FALLBACK_ORDER.drop(start).filter { it in avail && it !in crashed }
        // A crash on every option: still try the most robust one rather than refusing to load.
        return out.ifEmpty { Backend.FALLBACK_ORDER.drop(start).filter { it in avail }.takeLast(1) }
    }
}

/**
 * Remembers that loading / first decode on a backend killed the process (an uncatchable native
 * abort). Armed before the risky step, disarmed after it; a mark still armed at the next start is
 * a crash. Replaces the per-engine NpuCrashGuard / MnnGpuGuard.
 */
class BackendCrashMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iris_backend_crash", Context.MODE_PRIVATE)

    init {
        // A mark left armed means the process died during that step. Only a real crash counts:
        // being swiped away or killed in the background while armed is not the backend's fault.
        prefs.getString(KEY_ARMED, null)?.let { armed ->
            val e = prefs.edit().remove(KEY_ARMED)
            if (lastExitWasCrash(context)) e.putBoolean(BAD_PREFIX + armed, true)
            e.commit()
        }
    }

    private fun lastExitWasCrash(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return true
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return true
            last.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                last.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                last.reason == android.app.ApplicationExitInfo.REASON_SIGNALED ||
                last.reason == android.app.ApplicationExitInfo.REASON_ANR
        }.getOrDefault(true)
    }

    fun arm(modelId: String, backend: Backend) {
        if (backend == Backend.CPU) return
        prefs.edit().putString(KEY_ARMED, key(modelId, backend)).commit()
    }

    fun disarm() { prefs.edit().remove(KEY_ARMED).commit() }

    fun crashed(modelId: String): Set<Backend> =
        Backend.entries.filter { prefs.getBoolean(BAD_PREFIX + key(modelId, it), false) }.toSet()

    fun forget(modelId: String) {
        val e = prefs.edit()
        Backend.entries.forEach { e.remove(BAD_PREFIX + key(modelId, it)) }
        e.apply()
    }

    private fun key(modelId: String, b: Backend) = "${b.name}|$modelId"

    private companion object {
        const val KEY_ARMED = "armed"
        const val BAD_PREFIX = "bad:"
    }
}

/** Helper for the UI: the options of a model, plus whether a backend crashed before. */
fun LocalModel.backendOptions(env: BackendEnvironment): List<BackendOption> =
    BackendCapabilities.options(format, fileName, env)
