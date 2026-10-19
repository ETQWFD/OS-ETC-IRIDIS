// Platform-independent chat engine on top of MNN-LLM (taobao-mnn model folders).
// Kept free of JNI so it can be exercised on a Linux host (native-test/mnn_host_test.cpp).
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace MNN { namespace Transformer { class Llm; } }

namespace iris {

struct MnnLoadParams {
    std::string configPath;   // <model dir>/config.json
    std::string backend;      // "cpu" | "opencl"
    int threads = 4;
    int contextLength = 4096;
    std::string tmpDir;       // OpenCL kernel cache / mmap dir (may be empty)
    bool useMmap = false;
    // 1.7.4: resource-tier cores + nice for the loading thread. MNN creates its worker pool during
    // load and the workers inherit both; the loading thread itself is restored afterwards.
    bool applyPolicy = false;
    std::vector<int> cpus;    // empty = every core
    int nice = 0;
};

struct MnnSampling {
    float temperature = 0.7f;
    float topP = 0.95f;
    float minP = 0.05f;
    float repeatPenalty = 1.1f;
    int topK = 40;
    int maxNewTokens = 2048;
    bool disableThinking = false;
    // 1.7.4: same policy for the calling thread while it generates (it runs MNN's task index 0).
    bool applyPolicy = false;
    std::vector<int> cpus;
    int nice = 0;
};

/** 1.7.5: outcome of re-pinning MNN's existing threads (MnnEngine::applyLive). */
struct MnnLiveReport {
    int targets = 0;          // threads we tried (pool workers + the generating thread if a reply runs)
    int affinityOk = 0;       // sched_setaffinity succeeded
    int niceOk = 0;           // setpriority succeeded
    int gone = 0;             // threads that no longer exist (dropped from the list)
    bool generating = false;  // the generating thread was included
    bool coresWidened = false;// fewer cores than threads → kept every core (spin-wait, see thread_policy.h)
    std::string readback;     // kernel read-back, grouped ("6 threads: [cpus=0-5 nice=10 x6]")
    std::string toJson() const;
};

enum MnnStop { MNN_STOP_END = 0, MNN_STOP_MAX = 1, MNN_STOP_CANCEL = 2, MNN_STOP_CONTEXT = 3, MNN_STOP_ERROR = 4 };

struct MnnStats {
    int promptTokens = 0;
    int reusedTokens = 0;
    int generatedTokens = 0;
    int64_t prefillUs = 0;
    int64_t generateUs = 0;
    int droppedMessages = 0;
    int64_t visionUs = 0;     // 2.1.1: time in the visual encoder (0 = no picture was read)
    int stop = MNN_STOP_ERROR;
    std::string error;
    std::string toJson() const;
};

using ChatMsgs = std::vector<std::pair<std::string, std::string>>;
/** Receives UTF-8 text that always ends on a code-point boundary. Return false to stop. */
using PieceFn = std::function<bool(const std::string&)>;

class MnnEngine {
public:
    MnnEngine();
    ~MnnEngine();

    /** Returns "" on success, otherwise a message. */
    std::string load(const MnnLoadParams& p);
    void unload();
    bool loaded() const { return llm_ != nullptr; }

    /** JSON: {supportsThinking, thinkingPrefilled, hasChatTemplate, nCtx, backend} */
    std::string meta() const;

    MnnStats generate(const ChatMsgs& msgs, const MnnSampling& s, const PieceFn& onPiece);

    /**
     * 1.7.5: moves MNN's *existing* worker threads (recorded at load) and, while a reply runs, the
     * generating thread to [cpus] (empty = all) with nice [nice]. Takes effect from the next token;
     * no reload, the thread count stays. Safe to call from any thread, during or between replies.
     */
    /// [allowSqueeze] (host experiment only) pins even when that leaves fewer cores than threads.
    MnnLiveReport applyLive(const std::vector<int>& cpus, int nice, bool allowSqueeze = false);
    /** Recorded pool workers that still exist, plus the last generating thread (0 if none yet). */
    std::vector<int> threadIds();
    /** Total MNN threads doing work in a reply: workers + the calling thread. */
    int poolThreads() const { return threadsUsed_; }

    void cancel() { cancel_.store(true); }
    void clearCancel() { cancel_.store(false); }
    void reset();

    // Exposed for tests.
    static std::string stripThink(const std::string& reply);
    /** 2.1.1: [text] with every <img>…</img> removed; [images] receives how many there were. Pure. */
    static std::string stripImageTags(const std::string& text, int* images);
    /** 2.1.1: context tokens budgeted per picture when fitting the window (vision tokens are not text). */
    static constexpr int kImageTokenAllowance = 512;
    /** Bytes of [s] that form complete UTF-8 code points (the rest must wait for more bytes). */
    static size_t utf8CompleteLen(const std::string& s);

private:
    std::string render(const ChatMsgs& msgs, bool addGen) const;
    void applySampling(const MnnSampling& s);
    void setThinking(bool enabled);
    int countTokens(const std::string& text) const;

    MNN::Transformer::Llm* llm_ = nullptr;
    std::atomic<bool> cancel_{false};
    MnnLoadParams params_;
    bool supportsThinking_ = false;
    bool thinkingPrefilled_ = false;
    bool thinkingSwitch_ = false;   // template honours enable_thinking
    bool hasTemplate_ = false;
    bool haveSampling_ = false;
    MnnSampling lastSampling_;
    int thinkingState_ = -1;        // -1 unknown, 0 off, 1 on
    std::vector<int> poolTids_;     // threads created during load (MNN's pool workers), for logging
    // 1.7.5: live re-pinning. Guarded by policyMu_ (applyLive runs on the UI side, generate on iris-mnn).
    std::mutex policyMu_;
    std::vector<int> workerTids_;   // pool workers: new threads during load with the loading thread's name
    int callerTid_ = 0;             // generating thread while a reply runs, else 0
    int lastCallerTid_ = 0;
    int threadsUsed_ = 1;
    std::string power_ = "normal";  // power hint actually written into the config
    std::string gpuHint_ = "-";      // 1.7.9: Adreno perf hint of the OpenCL context in use ("-" = CPU)
    bool loggedGenPolicy_ = false;  // log the generate-time read-back once per load
};

/** Loaded MnnEngine instances in this process (MNN's thread pools are process-global). */
int mnnLoadedCount();

/** 1.8.0 crash diagnostics: directory for iris_mnn_load.log ("" = off); stage lines append+fflush. */
void setDiagDir(const std::string& dir);

} // namespace iris