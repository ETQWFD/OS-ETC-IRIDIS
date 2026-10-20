#!/usr/bin/env bash
# Rebuilds third_party/mnn (MNN master + LLM, static) used by libiris_mnn.so (full flavour).
#   NDK=/path/to/android-ndk ANDROID_ABI=arm64-v8a|armeabi-v7a sh scripts/build-mnn-android.sh
# CPU + OpenCL. No QNN/Hexagon (needs Qualcomm's QNN / Hexagon SDKs and per-SoC converted models).
# Takes ~15-30 min per ABI depending on the host.
# ETC fork (OS-ETC-IRIDIS): ANDROID_ABI selects the target ABI — arm64-v8a keeps ARMv8.2 FP16 +
# KleidiAI; armeabi-v7a (32-bit) disables ARM82/KleidiAI (arm64-only) and uses NEON instead.
# 1.8.0: moved 3.6.1 → master (pinned MNN_COMMIT) for qwen3_5_moe (Qwen3.6 MNN exports); the IRIS
# patch set is applied by scripts/mnn-port-patch.py (anchor-based, idempotent), not a .patch file.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
NDK=${NDK:-/workspace/toolchain/android-ndk-r30}
SRC=${MNN_SRC:-$ROOT/build/mnn-src}
ABI=${ANDROID_ABI:-arm64-v8a}
MNN_COMMIT=${MNN_COMMIT:-9f097c5fdd48e0cac4468d4cc53a828794d96888}
if [ ! -d "$SRC" ]; then
  git clone https://github.com/alibaba/MNN.git "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "$MNN_COMMIT"
git -C "$SRC" checkout -f FETCH_HEAD
python3 "$ROOT/scripts/mnn-port-patch.py" "$SRC"
if [ "$ABI" = "armeabi-v7a" ]; then
  MNN_ARM82=OFF; MNN_KLEIDIAI=OFF
  CXXFLAGS="-ffunction-sections -fdata-sections -Wno-narrowing"
else
  MNN_ARM82=ON; MNN_KLEIDIAI=ON
  CXXFLAGS="-ffunction-sections -fdata-sections"
fi
B=$ROOT/build/mnn-android-$ABI
mkdir -p "$B" && cd "$B"
cmake -G Ninja "$SRC" \
 -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
 -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
 -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF \
 -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
 -DMNN_BUILD_LLM_OMNI=ON \
 -DMNN_ARM82=$MNN_ARM82 -DMNN_KLEIDIAI=$MNN_KLEIDIAI -DMNN_OPENCL=ON -DMNN_USE_LOGCAT=ON -DMNN_QNN=OFF -DMNN_HEXAGON=OFF \
 -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
 -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" -DCMAKE_CXX_FLAGS="$CXXFLAGS" \
 -DCMAKE_INSTALL_PREFIX="$B/install"
ninja -j"$(nproc)"
ninja install
OUT=$ROOT/third_party/mnn
mkdir -p "$OUT/lib/$ABI"
rm -rf "$OUT/include" && cp -r "$B/install/include" "$OUT/"
"$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)" --strip-debug -o "$OUT/lib/$ABI/libMNN.a" "$B/install/lib/libMNN.a"
echo "MNN master @ $MNN_COMMIT + scripts/mnn-port-patch.py" > "$OUT/VERSION"
echo done ($ABI)
