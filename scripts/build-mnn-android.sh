#!/bin/sh
# Rebuilds third_party/mnn (MNN master + LLM, static, arm64-v8a) used by libiris_mnn.so (full flavour).
#   NDK=/path/to/android-ndk sh scripts/build-mnn-android.sh
# CPU (ARMv8.2 FP16 + KleidiAI) + OpenCL. No QNN/Hexagon (needs Qualcomm's QNN / Hexagon SDKs and
# per-SoC converted models). Takes ~15-30 min depending on the host.
# 1.8.0: moved 3.6.1 → master (pinned MNN_COMMIT) for qwen3_5_moe (Qwen3.6 MNN exports); the IRIS
# patch set is applied by scripts/mnn-port-patch.py (anchor-based, idempotent), not a .patch file.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
NDK=${NDK:-/workspace/toolchain/android-ndk-r30}
SRC=${MNN_SRC:-$ROOT/build/mnn-src}
MNN_COMMIT=${MNN_COMMIT:-9f097c5fdd48e0cac4468d4cc53a828794d96888}
if [ ! -d "$SRC" ]; then
  git clone https://github.com/alibaba/MNN.git "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "$MNN_COMMIT"
git -C "$SRC" checkout -f FETCH_HEAD
python3 "$ROOT/scripts/mnn-port-patch.py" "$SRC"
B=$ROOT/build/mnn-android
mkdir -p "$B" && cd "$B"
cmake -G Ninja "$SRC" \
 -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
 -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
 -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF \
 -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
 -DMNN_BUILD_LLM_OMNI=ON \
 -DMNN_ARM82=ON -DMNN_KLEIDIAI=ON -DMNN_OPENCL=ON -DMNN_USE_LOGCAT=ON -DMNN_QNN=OFF -DMNN_HEXAGON=OFF \
 -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
 -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections" \
 -DCMAKE_INSTALL_PREFIX="$B/install"
ninja -j"$(nproc)"
ninja install
OUT=$ROOT/third_party/mnn
mkdir -p "$OUT/lib/arm64-v8a"
rm -rf "$OUT/include" && cp -r "$B/install/include" "$OUT/"
"$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)" --strip-debug -o "$OUT/lib/arm64-v8a/libMNN.a" "$B/install/lib/libMNN.a"
echo "MNN master @ $MNN_COMMIT + scripts/mnn-port-patch.py" > "$OUT/VERSION"
echo done
