#!/usr/bin/env python3
# Re-apply the IRIS MNN patch set to a fresh MNN checkout (master after 3.6.1).
# Usage: python3 mnn-port-patch.py /path/to/mnn-src
# Each replacement anchors on unique upstream text; exits non-zero if any anchor is missing.
import sys, io

SRC = sys.argv[1].rstrip('/') + '/'

def rd(p):
    return io.open(SRC + p, encoding='utf-8').read()

# Apache-2.0 section 4(b): every file this script changes gets a one-line "modified" notice at the top.
MODIFIED_NOTE = {
    'source/backend/opencl/core/runtime/OpenCLRuntime.cpp': 'app-selectable Adreno cl_qcom_perf_hint for new OpenCL contexts',
    'transformers/llm/engine/include/llm/llm.hpp': 'added reset_sampler() declaration',
    'transformers/llm/engine/src/llm.cpp': 'reset_sampler(), CPU decrease-rate config, KleidiAI hint, OpenCL perf-hint hookup, load-stage trace hooks',
    'transformers/llm/engine/src/omni.cpp': 'visual/audio module load trace (diagnostics)',
    'express/module/MoEModule.cpp': 'guard expert onForward() against empty/null outputs',
    'express/module/PipelineModule.cpp': 'log which sub-module produced empty/null outputs',
}

def wr(p, s):
    tag = '// Modified by IRIDIS: '
    if not s.startswith(tag):
        s = tag + MODIFIED_NOTE.get(p, 'patched by scripts/mnn-port-patch.py') + '\n' + s
    io.open(SRC + p, 'w', encoding='utf-8').write(s)

def rep(s, old, new, tag):
    if new in s:
        return s  # already applied (idempotent re-run)
    if s.count(old) != 1:
        sys.exit('ANCHOR %s: %s' % ('MISSING' if old not in s else 'AMBIGUOUS(%d)' % s.count(old), tag))
    return s.replace(old, new)

# ---------- OpenCLRuntime.cpp : Adreno perf hint (1.7.9) ----------
p = 'source/backend/opencl/core/runtime/OpenCLRuntime.cpp'
s = rd(p)
s = rep(s,
'#include "backend/opencl/core/runtime/OpenCLRuntime.hpp"\n#include <cstdlib>',
'#include "backend/opencl/core/runtime/OpenCLRuntime.hpp"\n#include <atomic>\n#include <cstdlib>',
'ocl include')
s = rep(s,
'static std::mutex gCLContextMutex;\nstatic std::shared_ptr<::cl::Context> getGlobalContext() {',
'''static std::mutex gCLContextMutex;
// IRIS patch (1.7.9): app-chosen Adreno perf hint (cl_qcom_perf_hint) for the next context MNN creates.
// MNN itself hard-codes CL_PERF_HINT_HIGH_QCOM (and only when the driver lacks cl_khr_priority_hints).
// Request: -1 = MNN default, 0 low, 1 normal, 2 high. Applied: hint of the last context created, -1 = none.
static std::atomic<int> gIrisPerfHintRequest{-1};
static std::atomic<int> gIrisPerfHintApplied{-1};
extern "C" void MNNIrisSetOpenCLPerfHint(int level) { gIrisPerfHintRequest.store(level); }
extern "C" int MNNIrisGetOpenCLPerfHint() { return gIrisPerfHintApplied.load(); }
static cl_context_properties irisPerfHintValue(int level) {
    return level == 0 ? CL_PERF_HINT_LOW_QCOM : (level == 1 ? CL_PERF_HINT_NORMAL_QCOM : CL_PERF_HINT_HIGH_QCOM);
}
static std::shared_ptr<::cl::Context> getGlobalContext() {''',
'ocl globals')
s = rep(s,
'''            std::vector<cl_context_properties> context_properties;
            if (mGpuType == ADRENO && !isPriorityHint) {
                context_properties.push_back(CL_CONTEXT_PERF_HINT_QCOM);
                context_properties.push_back(CL_PERF_HINT_HIGH_QCOM);
                context_properties.push_back(CL_CONTEXT_PRIORITY_HINT_QCOM);
                context_properties.push_back(CL_PRIORITY_HINT_LOW_QCOM);
                mIsDeviceSupportedLowPower = true;
            }''',
'''            std::vector<cl_context_properties> context_properties;
            const int irisHint = gIrisPerfHintRequest.load();
            int irisApplied = -1;
            bool irisAddedHint = false;
            if(mGpuType == ADRENO && !isPriorityHint){
                context_properties.push_back(CL_CONTEXT_PERF_HINT_QCOM);
                context_properties.push_back(irisHint >= 0 ? irisPerfHintValue(irisHint) : CL_PERF_HINT_HIGH_QCOM);
                irisApplied = irisHint >= 0 ? irisHint : 2;
                context_properties.push_back(CL_CONTEXT_PRIORITY_HINT_QCOM);
                context_properties.push_back(CL_PRIORITY_HINT_LOW_QCOM);
                mIsDeviceSupportedLowPower = true;
            } else if (mGpuType == ADRENO && irisHint >= 0
                       && getDeviceSupportsExtension(*(mFirstGPUDevicePtr.get()), "cl_qcom_perf_hint")) {
                // IRIS: drivers with cl_khr_priority_hints get no perf hint from MNN; add only the perf hint.
                context_properties.push_back(CL_CONTEXT_PERF_HINT_QCOM);
                context_properties.push_back(irisPerfHintValue(irisHint));
                irisApplied = irisHint;
                irisAddedHint = true;
            }''',
'ocl adreno')
s = rep(s,
'''                if (mContext == nullptr) {
                    if (context_properties.size() > 0) {
                        context_properties.push_back(0);
                        mContext = std::shared_ptr<cl::Context>(
                            new cl::Context(std::vector<cl::Device>({*mFirstGPUDevicePtr}), context_properties.data(),
                                            nullptr, nullptr, &res));
                    } else {
                        mContext = std::shared_ptr<cl::Context>(new cl::Context(
                            std::vector<cl::Device>({*mFirstGPUDevicePtr}), nullptr, nullptr, nullptr, &res));
                    }
                    setGlobalContext(mContext);
                }''',
'''                if (mContext == nullptr) {
                    if (context_properties.size() > 0) {
                        context_properties.push_back(0);
                        mContext = std::shared_ptr<cl::Context>(
                            new cl::Context(std::vector<cl::Device>({*mFirstGPUDevicePtr}), context_properties.data(),
                                            nullptr, nullptr, &res));
                        if (res != CL_SUCCESS && irisAddedHint) {
                            // IRIS: the driver refused the added perf hint -> MNN's own (empty) properties.
                            mContext = std::shared_ptr<cl::Context>(new cl::Context(
                                std::vector<cl::Device>({*mFirstGPUDevicePtr}), nullptr, nullptr, nullptr, &res));
                            irisApplied = -1;
                        }
                    } else {
                        mContext = std::shared_ptr<cl::Context>(new cl::Context(
                            std::vector<cl::Device>({*mFirstGPUDevicePtr}), nullptr, nullptr, nullptr, &res));
                    }
                    if (res == CL_SUCCESS) gIrisPerfHintApplied.store(irisApplied);
                    setGlobalContext(mContext);
                }''',
'ocl context create')
wr(p, s)

# ---------- llm.hpp : reset_sampler declaration ----------
p = 'transformers/llm/engine/include/llm/llm.hpp'
s = rd(p)
s = rep(s,
'    bool set_config(const std::string& content);',
'''    bool set_config(const std::string& content);
    // IRIS patch: rebuild the sampler from the current config (sampling params changed after load).
    void reset_sampler();''',
'llm.hpp set_config')
wr(p, s)

# ---------- llm.cpp ----------
p = 'transformers/llm/engine/src/llm.cpp'
s = rd(p)
s = rep(s,
'bool Llm::set_config(const std::string& content) {',
'''void Llm::reset_sampler() {
    if (mContext && mConfig) mSampler.reset(Sampler::createSampler(mContext, mConfig));
}

bool Llm::set_config(const std::string& content) {''',
'llm.cpp reset_sampler')
s = rep(s,
'    rtg->setHint(MNN::Interpreter::MEM_ALLOCATOR_TYPE, 0);',
'''    rtg->setHint(MNN::Interpreter::MEM_ALLOCATOR_TYPE, 0);
    // IRIS patch (1.7.5): per-cluster work split weight (MNN default 50). 100 = equal split, used when
    // the app pins all threads to one cluster itself.
    rtg->setHint(MNN::Interpreter::CPU_LITTLECORE_DECREASE_RATE, mConfig->config_.value("cpu_decrease_rate", 50));''',
'llm.cpp decrease rate')
s = rep(s,
'void Llm::initRuntime() {',
'''// IRIS patch (1.7.9): defined in OpenCLRuntime.cpp when MNN_OPENCL is built; weak so CPU-only builds link.
extern "C" __attribute__((weak)) void MNNIrisSetOpenCLPerfHint(int level);

void Llm::initRuntime() {''',
'llm.cpp weak extern')
s = rep(s,
'''    config.backendConfig = &cpuBackendConfig;

    mRuntimeManager.reset(Executor::RuntimeManager::createRuntimeManager(config));''',
'''    config.backendConfig = &cpuBackendConfig;

    if (MNNIrisSetOpenCLPerfHint != nullptr) {
        // IRIS patch (1.7.9): "iris_cl_perf_hint" low/normal/high -> Adreno perf hint of the OpenCL context
        // created below; absent = MNN's own behaviour. Independent of "power" (CPU-runtime core binding).
        const std::string h = mConfig->config_.value("iris_cl_perf_hint", std::string(""));
        MNNIrisSetOpenCLPerfHint(h == "low" ? 0 : (h == "normal" ? 1 : (h == "high" ? 2 : -1)));
    }
    mRuntimeManager.reset(Executor::RuntimeManager::createRuntimeManager(config));''',
'llm.cpp initRuntime hint')

s = rep(s,
'    rtg->setHint(MNN::Interpreter::CPU_SME2_NEON_DIVISION_RATIO, mConfig->config_.value("cpu_sme2_neon_division_ratio", 41));',
'''    rtg->setHint(MNN::Interpreter::CPU_SME2_NEON_DIVISION_RATIO, mConfig->config_.value("cpu_sme2_neon_division_ratio", 41));
    // IRIS patch (2.0.3): "enable_kleidiai" 1 -> Arm KleidiAI int4/int8 matmul kernels (built with MNN_KLEIDIAI=ON,
    // off by default upstream; unsupported layers fall back to MNN's own kernels).
    rtg->setHint(MNN::Interpreter::CPU_ENABLE_KLEIDIAI, mConfig->config_.value("enable_kleidiai", 0));''',
'llm.cpp kleidiai hint')

# ---------- llm.cpp : load-stage trace hooks (IRIS 1.8.0 crash diagnostics; logging only) ----------
HOOKS_DECL = '''
// IRIS patch (1.8.0): MNN load-stage trace. The app installs a callback to timestamp every load
// stage (tokenizer / llm.mnn / weights / visual.mnn / runtime); a SIGSEGV then leaves the last
// stage on disk. Weak symbols: CPU-only host builds and the unpatched engine keep working.
extern "C" { __attribute__((weak)) void irisLlmStageHook(const char*) {} }
#define IRIS_STAGE(name) ::irisLlmStageHook(name)
// IRIS 1.8.0: app-probed "禁用视觉模块" switch (weak: absent/0 = never disabled).
extern "C" { __attribute__((weak)) int irisNoVisual() { return 0; } }
'''
s = rep(s,
'namespace MNN {',
HOOKS_DECL + '\nnamespace MNN {',
'llm.cpp hooks decl2')
s = rep(s,
'''    mModulePool.clear();
    mModule.reset();
    initRuntime();''',
'''    mModulePool.clear();
    mModule.reset();
    IRIS_STAGE("load:create-runtime");
    initRuntime();
    IRIS_STAGE("load:create-runtime ok");''',
'llm.cpp stage create-runtime')
s = rep(s,
'''    mTokenizer.reset(Tokenizer::createTokenizer(tokenizer_path));''',
'''    IRIS_STAGE("load:tokenizer");
    mTokenizer.reset(Tokenizer::createTokenizer(tokenizer_path));
    IRIS_STAGE("load:tokenizer done");''',
'llm.cpp stage tokenizer')
s = rep(s,
'    mDiskEmbedding.reset(new DiskEmbedding(mConfig));',
'''    IRIS_STAGE("load:disk-embedding(embeddings_bf16)");
    mDiskEmbedding.reset(new DiskEmbedding(mConfig));
    IRIS_STAGE("load:disk-embedding done");''',
'llm.cpp stage embeddings')
s = rep(s,
'''    std::shared_ptr<LlmConfig> config(new LlmConfig(config_path));
    Llm* llm = nullptr;
    if (config->is_visual() || config->is_audio() || config->has_talker()) {''',
'''    std::shared_ptr<LlmConfig> config(new LlmConfig(config_path));
    IRIS_STAGE("load:read config.json");
    if (irisNoVisual() != 0) {
        // IRIS 1.8.0 (app setting 禁用视觉模块): treat the model as text-only — Omni skips loading
        // visual.mnn entirely. The inference logic itself is untouched upstream.
        config->config_["is_visual"] = false;
        IRIS_STAGE("load:visual disabled by app setting");
    }
    Llm* llm = nullptr;
    if (config->is_visual() || config->is_audio() || config->has_talker()) {''',
 'llm.cpp stage createLLM config')
s = rep(s,
'''    mRuntimeManager->setExternalFile(weight_path);''',
'''    IRIS_STAGE("load:load llm.mnn graph");
    mRuntimeManager->setExternalFile(weight_path);''',
'llm.cpp stage graph begin')
s = rep(s,
'''    mModule.reset(Module::load(inputNames, outputNames, model_path.c_str(), mRuntimeManager, &module_config));
    mRuntimeManager->setExternalFile("");''',
'''    IRIS_STAGE("load:load llm.mnn weights(map/dq)");
    mModule.reset(Module::load(inputNames, outputNames, model_path.c_str(), mRuntimeManager, &module_config));
    mRuntimeManager->setExternalFile("");
    IRIS_STAGE("load:llm.mnn done");''',
'llm.cpp stage weights')
wr(p, s)

# ---------- omni.cpp : visual/audio module trace ----------
p = 'transformers/llm/engine/src/omni.cpp'
s = rd(p)
s = rep(s,
'namespace MNN {',
'''// IRIS patch (1.8.0): load-stage trace, weak default definition in llm.cpp.
extern "C" void irisLlmStageHook(const char*);
namespace MNN {''',
'omni hook decl')
s = rep(s,
'''    if (mConfig->is_visual()) {
        mVisionModule.reset(Module::load({}, {}, mConfig->visual_model().c_str(), mProcessorRuntimeManager, &module_config));''',
'''    if (mConfig->is_visual()) {
        ::irisLlmStageHook("load:load visual.mnn");
        mVisionModule.reset(Module::load({}, {}, mConfig->visual_model().c_str(), mProcessorRuntimeManager, &module_config));
        ::irisLlmStageHook("load:visual.mnn done");''',
'omni stage visual')
s = rep(s,
'''        mAudioModule.reset(Module::load({}, {}, mConfig->audio_model().c_str(), mProcessorRuntimeManager, &module_config));''',
'''        ::irisLlmStageHook("load:load audio.mnn");
        mAudioModule.reset(Module::load({}, {}, mConfig->audio_model().c_str(), mProcessorRuntimeManager, &module_config));
        ::irisLlmStageHook("load:audio.mnn done");''',
'omni stage audio')
wr(p, s)

# ---------- MoEModule.cpp : guard every mExperts[..]->onForward(..)[0] against empty/null output ----------
# (IRIS 1.8.0: the Qwen3.6 MoE SIGSEGV — expert onForward returned empty and [0] dereferenced null.
#  Instead of crashing, log module idx/inputs and return an error output so Llm::forwardRaw sees
#  INTERNAL_ERROR and Java gets a load/generate error.)
p = 'express/module/MoEModule.cpp'
s = rd(p)
s = rep(s,
'namespace MNN {\nnamespace Express {\n\nstd::vector<Express::VARP> MoEModule::onForward(const std::vector<Express::VARP>& inputs) {',
'''#include <cstdio>
#include <string>
extern "C" void irisLlmStageHook(const char*);
namespace MNN {
namespace Express {
// IRIS 1.8.0: safe expert call. On empty/null output log which expert failed and with what input
// shape, then produce a zero tensor of the expected shape (input hiddenStates) via _Identity-like
// fallback — never dereference [0] of an empty vector again. Returning error upward: nullptr VARP
// makes the downstream compute fail with an express error instead of SIGSEGV.
static Express::VARP irisExpertOut(const std::vector<Express::VARP>& outs, int expertId,
                                   const Express::VARP& in0, int callSite) {
    if (outs.empty() || outs[0].get() == nullptr) {
        std::string shape = "(no input)";
        if (in0.get() != nullptr && in0->getInfo() != nullptr) {
            shape = "[";
            for (auto d : in0->getInfo()->dim) shape += std::to_string(d) + ",";
            shape += "]";
        }
        char msg[224];
        snprintf(msg, sizeof msg, "MoE: expert %d onForward returned %s (site %d) in shape %s",
                 expertId, outs.empty() ? "EMPTY vector" : "nullptr output", callSite, shape.c_str());
        MNN_ERROR("%s\\n", msg);
        ::irisLlmStageHook(msg);
        return nullptr;
    }
    return outs[0];
}
std::vector<Express::VARP> MoEModule::onForward(const std::vector<Express::VARP>& inputs) {
    if (inputs.size() < 3 || inputs[0].get() == nullptr || inputs[2].get() == nullptr) {
        MNN_ERROR("MoE: onForward got %zu inputs, some null\\n", inputs.size());
        return {};
    }''',
'moe guard decl')
s = rep(s,
'''            mHiddenStatesList[i] = mExperts[expertId]->onForward({hiddenStates})[0];
        }
        mHiddenStatesList[topK] = routingWeights;
        auto res = mExperts.back()->onForward(mHiddenStatesList);''',
'''            mHiddenStatesList[i] = irisExpertOut(mExperts[expertId]->onForward({hiddenStates}), expertId, hiddenStates, 1);
            if (mHiddenStatesList[i].get() == nullptr) return {};
        }
        mHiddenStatesList[topK] = routingWeights;
        auto res = mExperts.back()->onForward(mHiddenStatesList);
        if (res.empty()) {
            MNN_ERROR("MoE: reduce module returned EMPTY (decode, topK=%d)\\n", topK);
            ::irisLlmStageHook("MoE: reduce module returned EMPTY (decode)");
        }''',
'moe guard decode')
s = rep(s,
'''            auto curHiddenStates = mExperts[i]->onForward({workHiddenStates})[0];
            VARPS curHiddenStatesList = _Split(curHiddenStates, std::vector<int>(expertWorks[i].size(), 1), 0);''',
'''            auto curHiddenStates = irisExpertOut(mExperts[i]->onForward({workHiddenStates}), i, workHiddenStates, 2);
            if (curHiddenStates.get() == nullptr) return {};
            VARPS curHiddenStatesList = _Split(curHiddenStates, std::vector<int>(expertWorks[i].size(), 1), 0);''',
'moe guard prefill multi')
s = rep(s,
'''            auto output = mExperts[i]->onForward({workHiddenStates})[0];
            auto curHiddenStates = _Multiply(output, _Scalar<float>(scale));''',
'''            auto output = irisExpertOut(mExperts[i]->onForward({workHiddenStates}), i, workHiddenStates, 3);
            if (output.get() == nullptr) return {};
            auto curHiddenStates = _Multiply(output, _Scalar<float>(scale));''',
'moe guard prefill one')
wr(p, s)

# ---------- PipelineModule.cpp : log WHICH sub-module produced empty/null outputs ----------
p = 'express/module/PipelineModule.cpp'
s = rd(p)
s = rep(s,
'''        std::vector<VARP> tempOutputs = std::get<0>(m)->onForward(tempInputs);
        if(tempOutputs.size() != std::get<2>(m).size()) {
            // Execute has error
            return {};
        }
        for (int i = 0; i < tempOutputs.size(); ++i) {
            mStack[std::get<2>(m)[i]] = tempOutputs[i];
            MNN_ASSERT(nullptr != tempOutputs[i]);
        }''',
'''        std::vector<VARP> tempOutputs = std::get<0>(m)->onForward(tempInputs);
        if(tempOutputs.size() != std::get<2>(m).size()) {
            // Execute has error
            // IRIS 1.8.0: identify the failing sub-module + its input shapes before bailing out.
            irisLogEmptySubModule(index, tempInputs, (int)tempOutputs.size(), (int)std::get<2>(m).size(), "PipelineModule");
            return {};
        }
        for (int i = 0; i < tempOutputs.size(); ++i) {
            // IRIS 1.8.0: a present-but-null output used to pass through and crash later.
            if (nullptr == tempOutputs[i]) {
                irisLogEmptySubModule(index, tempInputs, -1, (int)std::get<2>(m).size(), "PipelineModule");
                return {};
            }
            mStack[std::get<2>(m)[i]] = tempOutputs[i];
            MNN_ASSERT(nullptr != tempOutputs[i]);
        }''',
'pipeline guard')
s = rep(s,
'std::vector<VARP> PipelineModule::onForward(const std::vector<VARP>& inputs) {',
'''// IRIS 1.8.0: report the sub-module whose onForward produced empty/null outputs (MoE crash hunt).
extern "C" void irisLlmStageHook(const char*);
static void irisLogEmptySubModule(int index, const std::vector<VARP>& ins, int got, int want, const char* modName) {
    std::string shapes = "[";
    for (auto& iv : ins) {
        if (iv.get() != nullptr && iv->getInfo() != nullptr) {
            shapes += "(";
            for (auto d : iv->getInfo()->dim) shapes += std::to_string(d) + "x";
            shapes += ")";
        } else shapes += "(null)";
    }
    shapes += "]";
    char msg[320];
    snprintf(msg, sizeof msg, "Pipeline: sub-module #%d (%s) outputs=%d want=%d inputs=%s",
             index, modName != nullptr ? modName : "?", got, want, shapes.c_str());
    MNN_ERROR("%s\\n", msg);
    irisLlmStageHook(msg);
}
std::vector<VARP> PipelineModule::onForward(const std::vector<VARP>& inputs) {''',
'pipeline log fn')
wr(p, s)

print('IRIS MNN patch applied OK')
