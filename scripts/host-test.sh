#!/usr/bin/env sh
# Builds the platform-independent native engine against llama.cpp for the *host* and runs its
# test-suite against a real GGUF model. No Android device needed.
#   sh scripts/host-test.sh path/to/model.gguf
# A tiny model is enough, e.g. SmolLM2-135M-Instruct-Q4_K_M.gguf (~100 MB).
set -eu
cd "$(dirname "$0")/.."
MODEL="${1:-}"
L=third_party/llama.cpp
B=build/host-llama
cmake -S "$L" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_APP=OFF >/dev/null
cmake --build "$B" --target llama -j"$(nproc 2>/dev/null || echo 4)"
c++ -std=c++17 -O2 -Wall -Wextra -I "$L/include" -I "$L/ggml/include" \
  app/src/main/cpp/iris_engine.cpp native-test/host_test.cpp \
  "$B/src/libllama.a" "$B/ggml/src/libggml.a" "$B/ggml/src/libggml-cpu.a" "$B/ggml/src/libggml-base.a" \
  -lpthread -ldl -o build/iris_host_test
./build/iris_host_test $MODEL
