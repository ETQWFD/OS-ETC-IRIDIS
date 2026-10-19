// Checks reasoning-model handling with a Qwen3 GGUF: template detection and the
// "deep thinking off" pre-fill.   ./think_test Qwen3-0.6B-Q4_K_M.gguf
#include <cstdio>
#include <string>
#include "../app/src/main/cpp/iris_engine.h"
int main(int argc, char **argv) {
    if (argc < 2) return 0;
    iris::set_log_sink([](int, const char *t) { std::fputs(t, stderr); }, 4);
    iris::Engine e; std::string err; iris::LoadParams lp; lp.n_ctx = 2048; lp.n_threads = 2;
    if (!e.load(argv[1], lp, nullptr, err)) { std::printf("load failed: %s\n", err.c_str()); return 1; }
    auto m = e.meta();
    std::printf("thinking=%d prefilled=%d template=%d\n", m.supports_thinking, m.thinking_prefilled, m.has_chat_template);
    int fails = 0;
    if (!m.supports_thinking) { std::puts("FAIL supports_thinking"); fails++; }
    std::vector<iris::ChatMessage> msgs = {{"user", "What is 2+3? Answer briefly."}};
    std::printf("--- prompt (thinking off) ---\n%s\n---\n", e.format_prompt(msgs, true).c_str());
    for (int off = 0; off <= 1; off++) {
        iris::SamplingParams sp; sp.temperature = 0; sp.max_new_tokens = off ? 60 : 400; sp.disable_thinking = off;
        std::string out;
        auto st = e.generate(msgs, sp, [&](const char *d, size_t n) { out.append(d, n); return true; });
        std::printf("[thinking %s] stop=%d tokens=%d %.1f tok/s\n%s\n\n", off ? "OFF" : "ON", (int) st.stop, st.generated_tokens,
                    st.generated_tokens / (st.generate_us / 1e6 + 1e-9), out.c_str());
        bool has = out.find("</think>") != std::string::npos;
        if (off && has) { std::puts("FAIL: reasoning emitted while disabled"); fails++; }
        if (!off && !has && st.stop == iris::StopReason::EndOfGeneration) { std::puts("FAIL: no reasoning while enabled"); fails++; }
    }
    std::puts(fails ? "FAILED" : "ALL PASSED");
    return fails;
}
