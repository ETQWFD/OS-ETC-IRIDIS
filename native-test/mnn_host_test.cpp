// Host smoke test for app/src/main/cpp/mnn/mnn_engine.cpp against a real taobao-mnn model folder.
//   sh scripts/mnn-host-test.sh /path/to/Qwen3-0.6B-MNN
#include "../app/src/main/cpp/mnn/mnn_engine.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <chrono>

using namespace iris;

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); failures++; } else { std::printf("ok: %s\n", msg); } } while (0)

static MnnSampling greedy(int n, bool noThink) {
    MnnSampling s;
    s.temperature = 0.0f;
    s.topK = 1;
    s.maxNewTokens = n;
    s.disableThinking = noThink;
    return s;
}

int main(int argc, char** argv) {
    // ---- pure helpers ----
    CHECK(MnnEngine::stripThink("<think>abc</think>\n\nHello ") == "Hello", "stripThink removes reasoning");
    CHECK(MnnEngine::stripThink("reasoning...</think>Answer") == "Answer", "stripThink prefilled reasoning");
    CHECK(MnnEngine::stripThink("<think>only thinking") == "", "stripThink unfinished reasoning");
    CHECK(MnnEngine::stripThink("  plain ") == "plain", "stripThink plain");
    std::string zh = "\xE4\xBD\xA0\xE5\xA5\xBD"; // 你好
    CHECK(MnnEngine::utf8CompleteLen(zh) == 6, "utf8 complete");
    CHECK(MnnEngine::utf8CompleteLen(zh.substr(0, 4)) == 3, "utf8 holds partial");
    CHECK(MnnEngine::utf8CompleteLen("ab") == 2, "utf8 ascii");
    std::string emoji = "\xF0\x9F\x98\x80";
    CHECK(MnnEngine::utf8CompleteLen(emoji.substr(0, 3)) == 0, "utf8 partial 4-byte");

    if (argc < 2) { std::printf("no model dir given: model tests skipped\n"); return failures ? 1 : 0; }
    std::string dir = argv[1];

    MnnEngine e;
    MnnLoadParams p;
    p.configPath = dir + "/config.json";
    p.backend = "cpu";
    p.threads = 2;
    p.contextLength = 2048;
    auto t0 = std::chrono::steady_clock::now();
    std::string err = e.load(p);
    auto t1 = std::chrono::steady_clock::now();
    std::printf("load: %s (%.1fs)\nmeta: %s\n", err.empty() ? "ok" : err.c_str(),
                std::chrono::duration<double>(t1 - t0).count(), e.meta().c_str());
    CHECK(err.empty(), "load model");
    if (!err.empty()) return 1;
    CHECK(e.meta().find("\"supportsThinking\":true") != std::string::npos, "Qwen3 template supports thinking");

    // ---- turn 1 ----
    ChatMsgs msgs{{"system", "You are a helpful assistant."}, {"user", "What is 2+3? Answer with just the number."}};
    std::string out;
    MnnStats st = e.generate(msgs, greedy(32, true), [&](const std::string& s) { out += s; std::fputs(s.c_str(), stdout); std::fflush(stdout); return true; });
    std::printf("\nstats1: %s\n", st.toJson().c_str());
    CHECK(st.stop == MNN_STOP_END || st.stop == MNN_STOP_MAX, "turn 1 finished");
    CHECK(out.find('5') != std::string::npos, "turn 1 answers 5");
    CHECK(out.find("<think>") == std::string::npos || out.find("</think>") != std::string::npos, "no open think with thinking off");
    double tps = st.generateUs > 0 ? st.generatedTokens * 1e6 / st.generateUs : 0;
    std::printf("decode %.1f tok/s, prefill %d tok in %.0f ms\n", tps, st.promptTokens - st.reusedTokens, st.prefillUs / 1000.0);

    // ---- determinism: same request again from scratch gives the same text ----
    e.reset();
    std::string out1b;
    MnnStats st1b = e.generate(msgs, greedy(32, true), [&](const std::string& s) { out1b += s; return true; });
    CHECK(out1b == out, "greedy decode is deterministic after reset");
    (void)st1b;

    // ---- turn 2 reuses the KV cache of turn 1 ----
    ChatMsgs msgs2 = msgs;
    msgs2.emplace_back("assistant", MnnEngine::stripThink(out));
    msgs2.emplace_back("user", "Now multiply that by 2. Just the number.");
    std::string out2;
    MnnStats st2 = e.generate(msgs2, greedy(32, true), [&](const std::string& s) { out2 += s; return true; });
    std::printf("turn2: %s\nstats2: %s\n", out2.c_str(), st2.toJson().c_str());
    CHECK(st2.reusedTokens > 0, "turn 2 reuses prompt-cache KV");
    CHECK(out2.find("10") != std::string::npos, "turn 2 answers 10");

    // ---- cancel from the callback ----
    std::string out3;
    int pieces = 0;
    ChatMsgs m3{{"user", "Write a long story about a cat."}};
    MnnStats st3 = e.generate(m3, greedy(400, true), [&](const std::string& s) { out3 += s; return ++pieces < 5; });
    CHECK(st3.stop == MNN_STOP_CANCEL, "callback stop → cancelled");
    CHECK(pieces == 5, "stopped promptly");

    // ---- cancel from another thread ----
    std::atomic<int> got{0};
    std::thread killer([&] { while (got.load() < 3) std::this_thread::sleep_for(std::chrono::milliseconds(5)); e.cancel(); });
    MnnStats st4 = e.generate(m3, greedy(400, true), [&](const std::string&) { got++; return true; });
    killer.join();
    CHECK(st4.stop == MNN_STOP_CANCEL, "cross-thread cancel");
    e.clearCancel();

    // ---- after cancel a fresh conversation still works ----
    std::string out5;
    MnnStats st5 = e.generate(msgs, greedy(32, true), [&](const std::string& s) { out5 += s; return true; });
    CHECK(st5.stop != MNN_STOP_ERROR && out5.find('5') != std::string::npos, "works after cancel");

    // ---- thinking on: reply contains a <think> block that closes ----
    std::string out6;
    MnnStats st6 = e.generate({{"user", "Is 7 prime? Answer yes or no."}}, greedy(512, false), [&](const std::string& s) { out6 += s; return true; });
    std::printf("thinking reply (%d tok): %.200s...\n", st6.generatedTokens, out6.c_str());
    CHECK(out6.find("</think>") != std::string::npos, "thinking mode produces </think>");

    // ---- max tokens ----
    MnnStats st7 = e.generate(m3, greedy(8, true), [&](const std::string&) { return true; });
    CHECK(st7.stop == MNN_STOP_MAX && st7.generatedTokens == 8, "max_new_tokens honoured");

    // ---- context overflow handling: long history gets trimmed ----
    ChatMsgs longH{{"system", "sys"}};
    std::string filler;
    for (int i = 0; i < 120; i++) filler += "lorem ipsum dolor sit amet ";
    for (int i = 0; i < 6; i++) { longH.emplace_back("user", filler); longH.emplace_back("assistant", "ok"); }
    longH.emplace_back("user", "Say hi.");
    MnnStats st8 = e.generate(longH, greedy(16, true), [&](const std::string&) { return true; });
    std::printf("trim: %s\n", st8.toJson().c_str());
    CHECK(st8.droppedMessages > 0 && st8.stop != MNN_STOP_ERROR, "old turns dropped to fit context");

    e.unload();
    CHECK(!e.loaded(), "unload");
    std::printf("\n%s (%d failures)\n", failures ? "FAILED" : "ALL PASSED", failures);
    return failures ? 1 : 0;
}
