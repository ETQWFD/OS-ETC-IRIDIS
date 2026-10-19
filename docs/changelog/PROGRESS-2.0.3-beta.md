# 2.0.3-beta

## 提速（CPU · MNN）
- MNN 重新编译，带上 Arm KleidiAI 矩阵乘加速库（`-DMNN_KLEIDIAI=ON`）。之前的构建脚本注释写了 KleidiAI，实际没开。CPU 后端加载时传 `"enable_kleidiai":1`（IRIS MNN 补丁新增的配置项，对应 `CPU_ENABLE_KLEIDIAI`），不支持的层自动退回 MNN 自己的内核。
- 「极速」档（高）在两簇芯片上默认从「2 线程 · 只用 2 个超大核」改成「核心数−2 个线程（最多 6）· 超大核加最快的性能核」。骁龙 8 Elite：6 线程 · cpu2–7。起因：Qwen3.5 9B 只有 6–7 tok/s，2 个核吃不满内存带宽；真机手动改成 6 线程后明显变快。已经手动改过档位的用户保持自己的设置。

## 改动文件
- scripts/build-mnn-android.sh：加 `-DMNN_KLEIDIAI=ON`；修复 NDK 里有两套 prebuilt 时 llvm-strip 报错。
- scripts/mnn-port-patch.py：llm.cpp 新增 `enable_kleidiai` 提示。
- third_party/mnn：重新编译的 libMNN.a 和头文件（VERSION 标注 + KleidiAI）。
- app/src/main/cpp/mnn/mnn_engine.cpp：CPU 后端开 KleidiAI。
- engine/resources/TierProfiles.kt：高档默认值。
- 测试：V178Test、V171/V175/V176（高档默认值相关断言）、新增 V203SpeedTest。
- versionCode 175，versionName 2.0.3-beta。
