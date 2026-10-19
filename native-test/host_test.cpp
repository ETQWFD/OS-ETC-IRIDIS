// Host-side tests for iris_engine (no Android needed).
//   scripts/host-test.sh <model.gguf>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#include "../app/src/main/cpp/iris_engine.h"

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); failures++; } else { std::printf("  ok  %s\n", #cond); } } while (0)

static void unit_tests() {
    std::puts("[unit] utf8_complete_prefix / stop_holdback");
    std::string zh = "你好";              // 6 bytes
    CHECK(iris::utf8_complete_prefix(zh) == 6);
    CHECK(iris::utf8_complete_prefix(zh.substr(0, 4)) == 3);
    CHECK(iris::utf8_complete_prefix(zh.substr(0, 5)) == 3);
    std::string emoji = "a\xF0\x9F\x98\x80";  // a😀
    CHECK(iris::utf8_complete_prefix(emoji) == 5);
    CHECK(iris::utf8_complete_prefix(emoji.substr(0, 3)) == 1);
    CHECK(iris::utf8_complete_prefix(std::string("\x80x")) == 2);  // stray byte never blocks
    std::vector<std::string> stops = {"<|im_end|>"};
    CHECK(iris::stop_holdback("hello <|im", stops) == 4);
    CHECK(iris::stop_holdback("hello", stops) == 0);
    CHECK(iris::stop_holdback("hello <", stops) == 1);
    CHECK(iris::stop_holdback("<|im_end", {"", "<|im_end|>"}) == 8);  // empty stop ignored
    std::vector<std::string> two = {"<|im_end|>", "<|im_start|>"};
    CHECK(iris::find_first_stop("ab<|im_start|>cd<|im_end|>", 0, two) == 2);
    CHECK(iris::find_first_stop("ab<|im_start|>cd<|im_end|>", 3, two) == 16);
    CHECK(iris::find_first_stop("plain", 0, two) == std::string::npos);
}

// Greedy decoding is only bit-exact for an identical batch layout: CPU matmul kernels differ
// between batch sizes, so a reply recomputed after partial KV reuse may drift after a few tokens.
static bool same_start(const std::string &a, const std::string &b, size_t n = 16) {
    return a.size() >= n && b.size() >= n && a.compare(0, n, b, 0, n) == 0;
}

static std::string run(iris::Engine &e, const std::vector<iris::ChatMessage> &msgs,
                       iris::SamplingParams sp, iris::GenerateStats &st) {
    std::string out;
    st = e.generate(msgs, sp, [&](const char *d, size_t n) { out.append(d, n); return true; });
    return out;
}

int main(int argc, char **argv) {
    iris::set_log_sink([](int, const char *t) { std::fputs(t, stderr); }, 4);
    unit_tests();
    if (argc < 2) {
        std::puts("(no model given: skipping engine tests)");
        return failures ? 1 : 0;
    }

    iris::Engine e;
    std::string err;
    float last_progress = 0;
    iris::LoadParams lp;
    lp.n_ctx = 2048;
    lp.n_threads = 2;
    std::puts("[engine] load");
    bool ok = e.load(argv[1], lp, [&](float p) { last_progress = p; return true; }, err);
    CHECK(ok);
    if (!ok) { std::fprintf(stderr, "load error: %s\n", err.c_str()); return 1; }
    CHECK(last_progress > 0.99f);
    auto meta = e.meta();
    std::printf("  model: %s | arch=%s | params=%llu | ctx=%d/%d | template=%d | thinking=%d\n",
                meta.description.c_str(), meta.architecture.c_str(), (unsigned long long) meta.n_params,
                meta.n_ctx, meta.n_ctx_train, meta.has_chat_template, meta.supports_thinking);
    CHECK(meta.n_ctx == 2048);

    std::puts("[engine] prompt uses the model's own template");
    std::vector<iris::ChatMessage> msgs = {{"system", "You are a helpful assistant."},
                                           {"user", "What is the capital of France?"}};
    std::string prompt = e.format_prompt(msgs, false);
    std::printf("  ---prompt---\n%s\n  ------------\n", prompt.c_str());
    CHECK(prompt.find("What is the capital of France?") != std::string::npos);
    auto toks = e.tokenize(prompt);
    CHECK(!toks.empty());

    std::puts("[engine] turn 1 (greedy)");
    iris::SamplingParams sp;
    sp.temperature = 0;
    sp.max_new_tokens = 48;
    iris::GenerateStats st1;
    std::string r1 = run(e, msgs, sp, st1);
    std::printf("  reply: %s\n  prompt=%d reused=%d gen=%d stop=%d  %.1f tok/s\n", r1.c_str(), st1.prompt_tokens,
                st1.reused_tokens, st1.generated_tokens, (int) st1.stop,
                st1.generated_tokens / (st1.generate_us / 1e6 + 1e-9));
    CHECK(!r1.empty());
    CHECK(st1.reused_tokens == 0);
    CHECK(r1.find("<|im_end|>") == std::string::npos);
    CHECK(r1.find("<|im_start|>") == std::string::npos);

    std::puts("[engine] same prompt again → deterministic + full prefix reuse");
    iris::GenerateStats st1b;
    std::string r1b = run(e, msgs, sp, st1b);
    CHECK(r1b == r1);
    CHECK(st1b.reused_tokens == st1b.prompt_tokens - 1);

    std::puts("[engine] turn 2 reuses the KV cache of turn 1");
    msgs.push_back({"assistant", r1});
    msgs.push_back({"user", "And of Germany?"});
    iris::GenerateStats st2;
    std::string r2 = run(e, msgs, sp, st2);
    std::printf("  reply: %s\n  prompt=%d reused=%d gen=%d\n", r2.c_str(), st2.prompt_tokens, st2.reused_tokens,
                st2.generated_tokens);
    CHECK(!r2.empty());
    CHECK(st2.reused_tokens > st1.prompt_tokens / 2);

    std::puts("[engine] editing history invalidates only the diverging suffix");
    std::vector<iris::ChatMessage> other = {{"system", "You are a helpful assistant."},
                                            {"user", "Name three colors."}};
    iris::GenerateStats st3;
    std::string r3 = run(e, other, sp, st3);
    CHECK(!r3.empty());
    CHECK(st3.reused_tokens > 0 && st3.reused_tokens < st3.prompt_tokens);

    std::puts("[engine] cancel from another thread");
    iris::SamplingParams sl = sp;
    sl.max_new_tokens = 1500;
    sl.temperature = 0.8f;
    std::thread killer([&] { std::this_thread::sleep_for(std::chrono::milliseconds(300)); e.cancel(); });
    iris::GenerateStats st4;
    std::string r4 = run(e, {{"user", "Write a very long story about a dragon."}}, sl, st4);
    killer.join();
    CHECK(st4.stop == iris::StopReason::Cancelled || st4.stop == iris::StopReason::EndOfGeneration);
    std::printf("  stop=%d after %d tokens\n", (int) st4.stop, st4.generated_tokens);

    std::puts("[engine] state is consistent after a cancel (same greedy answer as before)");
    iris::GenerateStats st4b;
    std::string r4b = run(e, {{"system", "You are a helpful assistant."}, {"user", "What is the capital of France?"}}, sp, st4b);
    CHECK(same_start(r4b, r1));
    CHECK(st4b.stop != iris::StopReason::Error);

    std::puts("[engine] cancel during prefill");
    std::string big;
    for (int i = 0; i < 120; i++) big += "Lorem ipsum dolor sit amet number " + std::to_string(i) + ". ";
    std::thread k2([&] { std::this_thread::sleep_for(std::chrono::milliseconds(20)); e.cancel(); });
    auto stp = e.generate({{"user", big}}, sp, nullptr);
    k2.join();
    std::printf("  stop=%d prompt=%d\n", (int) stp.stop, stp.prompt_tokens);
    iris::GenerateStats st4c;
    std::string r4c = run(e, {{"system", "You are a helpful assistant."}, {"user", "What is the capital of France?"}}, sp, st4c);
    CHECK(same_start(r4c, r1));

    std::puts("[engine] honor_prior_cancel: a stop issued before the call is not lost");
    e.cancel();
    iris::SamplingParams hp = sp;
    hp.honor_prior_cancel = true;
    auto sth = e.generate({{"user", "Say hi."}}, hp, nullptr);
    CHECK(sth.stop == iris::StopReason::Cancelled);
    CHECK(sth.generated_tokens == 0);
    e.clear_cancel();
    auto sth2 = e.generate({{"user", "Say hi."}}, hp, nullptr);
    CHECK(sth2.stop != iris::StopReason::Cancelled);
    CHECK(sth2.generated_tokens > 0);

    std::puts("[engine] set_threads never blocks while generating");
    {
        long long blocked_ms = -1;
        std::thread t([&] {
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
            auto t0 = std::chrono::steady_clock::now();
            e.set_threads(1);
            blocked_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
            e.cancel();
        });
        iris::SamplingParams st_ = sl;
        auto sts = e.generate({{"user", "Write a long poem."}}, st_, nullptr);
        t.join();
        std::printf("  set_threads returned after %lld ms (stop=%d)\n", blocked_ms, (int) sts.stop);
        CHECK(blocked_ms >= 0 && blocked_ms < 50);
        e.set_threads(2);
    }

    std::puts("[engine] callback returning false stops generation");
    int pieces = 0;
    auto st5 = e.generate({{"user", "Count from 1 to 100."}}, sp,
                          [&](const char *, size_t) { return ++pieces < 3; });
    CHECK(st5.stop == iris::StopReason::Cancelled);
    CHECK(pieces == 3);

    std::puts("[engine] long history is trimmed to fit the context window");
    std::vector<iris::ChatMessage> longh = {{"system", "Be brief."}};
    std::string filler;
    for (int i = 0; i < 60; i++) filler += "This is filler sentence number " + std::to_string(i) + ". ";
    for (int i = 0; i < 12; i++) {
        longh.push_back({"user", filler});
        longh.push_back({"assistant", "ok"});
    }
    longh.push_back({"user", "Say hello."});
    iris::SamplingParams s6 = sp;
    s6.max_new_tokens = 16;
    iris::GenerateStats st6;
    std::string r6 = run(e, longh, s6, st6);
    std::printf("  dropped=%d prompt=%d\n", st6.dropped_messages, st6.prompt_tokens);
    CHECK(st6.dropped_messages > 0);
    CHECK(st6.prompt_tokens <= 2048);
    CHECK(st6.stop != iris::StopReason::Error);

    std::puts("[engine] a single over-long message reports ContextFull instead of crashing");
    std::string huge;
    for (int i = 0; i < 400; i++) huge += filler.substr(0, 40);
    auto st7 = e.generate({{"user", huge}}, sp, nullptr);
    CHECK(st7.stop == iris::StopReason::ContextFull);
    CHECK(!st7.error.empty());

    std::puts("[engine] works again after an error + reset");
    e.reset();
    iris::GenerateStats st8;
    std::string r8 = run(e, {{"system", "You are a helpful assistant."}, {"user", "What is the capital of France?"}}, sp, st8);
    CHECK(r8 == r1);

    std::puts("[engine] unload / reload");
    e.unload();
    CHECK(!e.loaded());
    auto st9 = e.generate(msgs, sp, nullptr);
    CHECK(st9.stop == iris::StopReason::Error);
    CHECK(e.load(argv[1], lp, nullptr, err));

    std::printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "ALL PASSED", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
