#!/usr/bin/env sh
# Builds MNN (CPU, LLM) for the *host* and runs native-test/mnn_host_test.cpp against a real
# taobao-mnn model folder (e.g. taobao-mnn/Qwen3-0.6B-MNN, ~450 MB). No Android device needed.
#   sh scripts/mnn-host-test.sh /path/to/Qwen3-0.6B-MNN [mnn-src-dir]
set -eu
cd "$(dirname "$0")/.."
MODEL="${1:-}"
SRC="${2:-${MNN_SRC:-/workspace/toolchain/mnn-src}}"
B=build/host-mnn
if [ ! -f "$B/libMNN.a" ]; then
  cmake -S "$SRC" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF \
    -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
    -DMNN_ARM82=ON -DMNN_OPENCL=OFF -DMNN_BUILD_TOOLS=OFF -DMNN_LLM_BUILD_DEMO=OFF -DLLM_SUPPORT_HTTP_RESOURCE=OFF \
    -DMNN_USE_LOGCAT=OFF -DMNN_SME2=OFF >/dev/null
  cmake --build "$B" --target MNN -j"$(nproc 2>/dev/null || echo 2)"
fi
c++ -std=c++17 -O2 -Wall -I "$SRC/include" -I "$SRC/transformers/llm/engine/include" \
  app/src/main/cpp/mnn/mnn_engine.cpp native-test/mnn_host_test.cpp \
  -Wl,--whole-archive "$B/libMNN.a" -Wl,--no-whole-archive -lpthread -ldl -o build/mnn_host_test
./build/mnn_host_test $MODEL
