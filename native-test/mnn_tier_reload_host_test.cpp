// 1.7.4 host check: a reload with a new thread count really gets a new MNN worker pool, and the
// workers inherit the loading thread's nice value; the loading thread is restored afterwards.
//   c++ ... app/src/main/cpp/mnn/mnn_engine.cpp native-test/mnn_tier_reload_host_test.cpp ...
#include "../app/src/main/cpp/mnn/mnn_engine.h"
#include "../app/src/main/cpp/thread_policy.h"

#include <cstdio>
#include <string>

using namespace iris;
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); failures++; } else { std::printf("ok: %s\n", msg); } } while (0)

static size_t loadAndCount(MnnEngine& e, const std::string& dir, int threads, int nice, std::vector<int>* fresh) {
    MnnLoadParams p;
    p.configPath = dir + "/config.json";
    p.backend = "cpu|normal";
    p.threads = threads;
    p.contextLength = 1024;
    p.applyPolicy = true;
    p.nice = nice;
    auto before = tp::task_ids();
    std::string err = e.load(p);
    if (!err.empty()) { std::printf("load error: %s\n", err.c_str()); failures++; return 0; }
    auto now = tp::task_ids();
    *fresh = tp::new_tasks(before, now);
    return fresh->size();
}

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: %s <Qwen3-0.6B-MNN dir>\n", argv[0]); return 2; }
    const std::string dir = argv[1];
    const auto self0 = tp::capture();
    MnnEngine e;
    std::vector<int> fresh;
    // nice may only be raised without privileges: 5 then 10.
    size_t n2 = loadAndCount(e, dir, 2, 5, &fresh);
    std::printf("threads=2 → %zu new threads: %s\n", n2, tp::summarize(fresh).c_str());
    CHECK(n2 >= 1, "2-thread load creates a worker");
    bool nice5 = !fresh.empty() && tp::describe(fresh[0]).find("nice=5") != std::string::npos;
    CHECK(nice5, "workers inherit nice=5 from the loading thread");
    const auto self1 = tp::capture();
    CHECK(self1.nice == self0.nice, "loading thread nice restored after load");

    e.unload();
    size_t n4 = loadAndCount(e, dir, 4, 10, &fresh);
    std::printf("threads=4 → %zu new threads: %s\n", n4, tp::summarize(fresh).c_str());
    CHECK(n4 >= 3, "reload with 4 threads gets a fresh 4-thread pool (not the old 2-thread one)");
    bool nice10 = !fresh.empty() && tp::describe(fresh[0]).find("nice=10") != std::string::npos;
    CHECK(nice10, "new workers inherit nice=10");

    MnnSampling s; s.temperature = 0; s.topK = 1; s.maxNewTokens = 8; s.disableThinking = true;
    s.applyPolicy = true; s.nice = 12;
    std::string out;
    auto st = e.generate({{"user", "1+1="}}, s, [&](const std::string& p) { out += p; return true; });
    CHECK(st.error.empty() && st.generatedTokens > 0, "generate after tier reload works");
    CHECK(tp::capture().nice == self0.nice, "generating thread nice restored after generate");
    e.unload();
    std::printf("%s\n", failures ? "FAILED" : "ALL OK");
    return failures ? 1 : 0;
}
