# 2.0.4-beta

## GPU 回退原因可见
- MNN 选 GPU 时，如果打不开 OpenCL 驱动，回退提示里会带上每个候选库的 dlerror 原因（以前只写"本机没有可用的 OpenCL"）。探测多试 /vendor/lib64/libOpenCL_adreno.so 和 /system/lib64/libOpenCL.so。
- 起因：骁龙 8 Elite 真机上 Qwen3.5 9B 选 GPU 后被回退到 CPU，看不出原因。
- 文件：mnn_jni.cpp（nativeOpenClProbe）、MnnNative.kt、MnnEngine.kt。versionCode 176。
