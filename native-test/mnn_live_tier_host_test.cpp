// 1.7.5 host measurement + check: live re-pinning of MNN's existing worker threads.
//
// Real Qwen3-0.6B-MNN, fixed prompt, greedy, 64 generated tokens per run. For every configuration:
// process CPU time (utime+stime via CLOCK_PROCESS_CPUTIME_ID, minus the sampler's own CPU time), the
// cores the MNN threads actually ran on (field 39 "processor" of /proc/self/task/<tid>/stat, sampled
// every 2 ms while the thread is in state R), nice read back (field 19 / getpriority) and tok/s.
//
// The host is a homogeneous 2-core aarch64 VM: it can show *where* threads run, how much CPU time a
// reply costs and whether a live switch reaches the existing threads, but not big/little power.
// Run with sudo so that nice can also be lowered (Android apps may do that; see RLIMIT_NICE).
//   c++ -std=c++17 -O2 ... app/src/main/cpp/mnn/mnn_engine.cpp native-test/mnn_live_tier_host_test.cpp ...
#include "../app/src/main/cpp/mnn/mnn_engine.h"
#include "../app/src/main/cpp/thread_policy.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

using namespace iris;
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); failures++; } else { std::printf("ok: %s\n", msg); } } while (0)

static double procCpuMs() {
    timespec ts{};
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}
static double threadCpuMs() {
    timespec ts{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}
static double nowMs() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

static std::string histStr(const std::map<int, int>& h) {
    if (h.empty()) return "-";
    int total = 0;
    for (auto& kv : h) total += kv.second;
    std::string o;
    for (auto& kv : h) {
        char b[48];
        snprintf(b, sizeof b, "%scpu%d %.0f%%", o.empty() ? "" : " ", kv.first, 100.0 * kv.second / total);
        o += b;
    }
    return o;
}
static std::string setStr(const std::set<int>& s) {
    std::string o;
    for (int v : s) { if (!o.empty()) o += ','; o += std::to_string(v); }
    return o.empty() ? "-" : o;
}

/** Samples the MNN threads every 2 ms; histograms of the running core per phase. */
struct Sampler {
    MnnEngine* e;
    std::atomic<bool> stop{false};
    std::atomic<int> phase{0};
    std::mutex mu;
    std::map<int, std::map<int, int>> hist;      // phase → cpu → R samples
    std::map<int, std::set<int>> nices;          // phase → nice values seen (stat field 19)
    double selfCpuMs = 0;
    std::thread th;
    explicit Sampler(MnnEngine* engine) : e(engine) {
        th = std::thread([this] {
            const double c0 = threadCpuMs();
            while (!stop.load()) {
                // 1.7.6: phase -1 = a live switch is in progress; a sweep is only kept when the phase did
                // not change while it was read (otherwise a sample taken across a switch lands in the old phase).
                const int ph = phase.load();
                std::vector<tp::ThreadStat> sweep;
                if (ph >= 0)
                    for (int t : e->threadIds()) {
                        const tp::ThreadStat st = tp::read_stat(t);
                        if (st.ok) sweep.push_back(st);
                    }
                if (ph >= 0 && phase.load() == ph) {
                    std::lock_guard<std::mutex> lk(mu);
                    for (const auto& st : sweep) {
                        nices[ph].insert(st.nice);
                        if (st.state == 'R') hist[ph][st.processor]++;
                    }
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
            selfCpuMs = threadCpuMs() - c0;
        });
    }
    void finish() { stop = true; th.join(); }
};

static const char* kPrompt = "请从 1 开始用阿拉伯数字连续数到 300，用逗号分隔，不要说别的。";

struct Run {
    int tokens = 0;
    double tokS = 0;
    double cpuMs = 0;          // whole call (prefill + decode), sampler CPU subtracted
    double decodeCpuMs = 0;    // first piece → end
    double decodeWallMs = 0;
    std::map<int, int> hist;
    std::set<int> nices;
};

static MnnSampling greedy64(bool applyPolicy, std::vector<int> cpus, int nice) {
    MnnSampling s;
    s.temperature = 0; s.topK = 1; s.maxNewTokens = 64; s.disableThinking = true;
    s.applyPolicy = applyPolicy; s.cpus = std::move(cpus); s.nice = nice;
    return s;
}

static Run runOnce(MnnEngine& e, const MnnSampling& s) {
    e.reset();
    Run r;
    Sampler sm(&e);
    double firstCpu = -1, firstWall = 0;
    const double c0 = procCpuMs();
    auto st = e.generate({{"user", kPrompt}}, s, [&](const std::string&) {
        if (firstCpu < 0) { firstCpu = procCpuMs(); firstWall = nowMs(); }
        return true;
    });
    const double c1 = procCpuMs(), w1 = nowMs();
    sm.finish();
    r.tokens = st.generatedTokens;
    r.tokS = st.generateUs > 0 ? st.generatedTokens * 1e6 / st.generateUs : 0;
    r.cpuMs = c1 - c0 - sm.selfCpuMs;
    r.decodeCpuMs = firstCpu >= 0 ? c1 - firstCpu : 0;
    r.decodeWallMs = firstCpu >= 0 ? w1 - firstWall : 0;
    r.hist = sm.hist[0];
    r.nices = sm.nices[0];
    if (!st.error.empty()) { std::printf("generate error: %s\n", st.error.c_str()); failures++; }
    return r;
}

static bool load(MnnEngine& e, const std::string& dir, int threads, std::vector<int> cpus, int nice) {
    MnnLoadParams p;
    p.configPath = dir + "/config.json";
    p.backend = "cpu|high";   // the app still passes the tier's hint; CPU must ignore it now
    p.threads = threads;
    p.contextLength = 1024;
    p.applyPolicy = true;
    p.cpus = std::move(cpus);
    p.nice = nice;
    const std::string err = e.load(p);
    if (!err.empty()) { std::printf("load error: %s\n", err.c_str()); failures++; return false; }
    return true;
}

struct Row { std::string name; std::string plan; Run med; };

static Run median3(std::vector<Run> v) {
    std::sort(v.begin(), v.end(), [](const Run& a, const Run& b) { return a.decodeCpuMs < b.decodeCpuMs; });
    Run m = v[v.size() / 2];
    // merge core histograms / nices of all repeats for the table
    m.hist.clear(); m.nices.clear();
    for (auto& r : v) { for (auto& kv : r.hist) m.hist[kv.first] += kv.second; m.nices.insert(r.nices.begin(), r.nices.end()); }
    return m;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: %s <Qwen3-0.6B-MNN dir> [repeats]\n", argv[0]); return 2; }
    const std::string dir = argv[1];
    const int reps = argc > 2 ? std::max(1, atoi(argv[2])) : 3;
    std::printf("host cores: %ld, repeats per config: %d\n", sysconf(_SC_NPROCESSORS_ONLN), reps);

    // ---- 0. thread-exit tolerance of apply_to ----
    {
        std::atomic<int> tid{0};
        std::thread t([&] { tid = tp::current_tid(); });
        t.join();
        CHECK(tp::apply_to(tid.load(), {0}, 5) == -1, "apply_to on an exited thread reports gone (-1), no crash");
        CHECK(tp::cores_for({0, 1}, 4).size() == 2 && tp::cores_for({0, 1}, 2).size() == 2,
              "cores_for keeps the pinning it is given (1.7.6: never released onto every core)");
    }

    std::vector<Row> rows;
    auto measure = [&](const std::string& name, int threads, std::vector<int> cpus, int nice) {
        MnnEngine e;
        if (!load(e, dir, threads, cpus, nice)) return;
        runOnce(e, greedy64(true, cpus, nice));   // warm-up (page-in)
        std::vector<Run> v;
        for (int i = 0; i < reps; i++) v.push_back(runOnce(e, greedy64(true, cpus, nice)));
        char plan[96];
        snprintf(plan, sizeof plan, "%d thr, cpus=%s, nice %d", threads, cpus.empty() ? "all" : setStr({cpus.begin(), cpus.end()}).c_str(), nice);
        rows.push_back({name, plan, median3(v)});
        e.unload();
    };

    // ---- 1. Four host tiers (2 identical cores: the host analogue of the app's tiers) ----
    measure("H-低", 1, {0}, 10);
    measure("H-中", 2, {}, 0);
    measure("H-高", 2, {}, -4);
    measure("H-全部", 2, {}, -8);
    // Oversubscription: 3 busy threads on 2 cores (the host analogue of 8 threads on the 6 slower 8 Elite cores).
    measure("超额(3线程/2核)", 3, {}, 0);

    // ---- 2. Spin-wait check: squeeze a 2-thread pool onto 1 core without reloading ----
    {
        MnnEngine e;
        if (load(e, dir, 2, {}, 0)) {
            runOnce(e, greedy64(false, {}, 0));
            auto rep = e.applyLive({0}, 10, /*allowSqueeze=*/true);
            const auto self = tp::capture();
            tp::apply({0}, 10);   // the calling thread runs MNN task 0: squeeze it too
            std::vector<Run> v;
            for (int i = 0; i < reps; i++) v.push_back(runOnce(e, greedy64(false, {}, 0)));
            tp::restore(self);
            rows.push_back({"挤压(2线程→1核)", "2 thr pinned to cpu0, nice 10", median3(v)});
            std::printf("squeeze re-pin: %s\n", rep.toJson().c_str());
            e.unload();
        }
    }

    std::printf("\n| 配置 | 计划 | tokens | tok/s | 进程CPU ms(整条) | 解码CPU ms | CPU ms/token(解码) | 平均忙核数(解码) | 运行核心分布(R 采样) | nice 回读 |\n");
    std::printf("|---|---|---|---|---|---|---|---|---|---|\n");
    for (auto& r : rows) {
        const Run& m = r.med;
        std::printf("| %s | %s | %d | %.1f | %.0f | %.0f | %.1f | %.2f | %s | %s |\n", r.name.c_str(), r.plan.c_str(), m.tokens,
                    m.tokS, m.cpuMs, m.decodeCpuMs, m.tokens > 0 ? m.decodeCpuMs / m.tokens : 0,
                    m.decodeWallMs > 0 ? m.decodeCpuMs / m.decodeWallMs : 0, histStr(m.hist).c_str(), setStr(m.nices).c_str());
    }
    std::printf("\n");

    // ---- 3. Live switch during one reply: existing threads move at the next token, no reload ----
    {
        MnnEngine e;
        if (load(e, dir, 2, {}, 0)) {
            runOnce(e, greedy64(true, {}, 0));
            const auto before = e.threadIds();
            const auto tasksBefore = tp::task_ids();
            e.reset();
            Sampler sm(&e);
            std::atomic<int> tokens{0};
            std::atomic<bool> done{false};
            struct Mark { double cpu, wall; int tok; };
            std::vector<Mark> marks;
            std::mutex mk;
            std::string rb1, rb2;
            MnnLiveReport rep1, rep2;
            std::thread ctl([&] {   // stands in for the UI thread changing the tier
                while (!done && tokens < 16) std::this_thread::sleep_for(std::chrono::microseconds(200));
                { std::lock_guard<std::mutex> lk(mk); marks.push_back({procCpuMs(), nowMs(), tokens.load()}); }
                sm.phase = -1;
                rep1 = e.applyLive({0}, 10, true);
                sm.phase = 1;
                while (!done && tokens < 40) std::this_thread::sleep_for(std::chrono::microseconds(200));
                { std::lock_guard<std::mutex> lk(mk); marks.push_back({procCpuMs(), nowMs(), tokens.load()}); }
                sm.phase = -1;
                rep2 = e.applyLive({1}, 5, true);
                sm.phase = 2;
            });
            double c0 = -1, w0 = 0;
            auto st = e.generate({{"user", kPrompt}}, greedy64(true, {}, 0), [&](const std::string&) {
                if (c0 < 0) { c0 = procCpuMs(); w0 = nowMs(); }
                tokens++;
                return true;
            });
            const double c1 = procCpuMs(), w1 = nowMs();
            done = true;
            ctl.join();
            sm.finish();
            const auto after = e.threadIds();
            std::printf("live switch reply: %d tokens, switch 1 at token %d (%s), switch 2 at token %d (%s)\n", st.generatedTokens,
                        marks.size() > 0 ? marks[0].tok : -1, rep1.toJson().c_str(), marks.size() > 1 ? marks[1].tok : -1, rep2.toJson().c_str());
            const char* names[3] = {"P0 all cores, nice 0", "P1 cpu0, nice 10", "P2 cpu1, nice 5"};
            for (int ph = 0; ph < 3; ph++) {
                double cpu = 0, wall = 0; int tok = 0;
                if (marks.size() == 2) {
                    double cs[4] = {c0, marks[0].cpu, marks[1].cpu, c1}, ws[4] = {w0, marks[0].wall, marks[1].wall, w1};
                    int ts[4] = {1, marks[0].tok, marks[1].tok, st.generatedTokens};
                    cpu = cs[ph + 1] - cs[ph]; wall = ws[ph + 1] - ws[ph]; tok = ts[ph + 1] - ts[ph];
                }
                std::printf("  %s: tokens %d, %.1f tok/s, CPU %.0f ms (%.1f ms/token, %.2f busy cores) | cores %s | nice %s\n", names[ph], tok,
                            wall > 0 ? tok * 1e3 / wall : 0, cpu, tok > 0 ? cpu / tok : 0, wall > 0 ? cpu / wall : 0,
                            histStr(sm.hist[ph]).c_str(), setStr(sm.nices[ph]).c_str());
            }
            auto onlyCpu = [&](int ph, int cpu) {
                if (sm.hist[ph].empty()) return false;
                for (auto& kv : sm.hist[ph]) if (kv.first != cpu) return false;
                return true;
            };
            CHECK(rep1.generating && rep1.affinityOk == rep1.targets && rep1.niceOk == rep1.targets && rep1.targets >= 2,
                  "switch 1 reached every worker + the generating thread (affinity and nice)");
            CHECK(onlyCpu(1, 0), "after switch 1 the MNN threads only ran on cpu0");
            CHECK(sm.nices[1] == std::set<int>{10}, "after switch 1 all MNN threads read back nice 10");
            CHECK(onlyCpu(2, 1), "after switch 2 the MNN threads only ran on cpu1");
            CHECK(sm.nices[2] == std::set<int>{5}, "after switch 2 all MNN threads read back nice 5");
            CHECK(after == before && tp::task_ids().size() == tasksBefore.size(), "no reload: same thread ids, no new threads");
            CHECK(st.generatedTokens == 64 && st.error.empty(), "reply completed all 64 tokens across both switches");

            // Idle, no reply: a pure cores/nice change reaches the workers without a reload.
            auto idle = e.applyLive({}, 3, false);
            CHECK(!idle.generating && idle.affinityOk == idle.targets && idle.niceOk == idle.targets && idle.targets >= 1,
                  "idle live re-pin reaches the pool workers (no reload)");
            CHECK(idle.readback.find("cpus=0-1 nice=3") != std::string::npos, "idle re-pin read back cpus=0-1 nice=3");
            auto widened = e.applyLive({0}, 3, false);
            // 1.7.6: Kotlin already extends the core list (coresFor); native keeps it as given, never widens.
            CHECK(!widened.coresWidened && widened.readback.find("cpus=0 ") != std::string::npos,
                  "app path: 1 core for a 2-thread pool stays pinned to cpu0 (1.7.6: never widened to all cores)");
            e.unload();
        }
    }
    std::printf("%s\n", failures ? "FAILED" : "ALL OK");
    return failures ? 1 : 0;
}
