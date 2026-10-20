cmake_minimum_required(VERSION 3.22.1)
project(iris_llama LANGUAGES C CXX)

# llama.cpp is vendored as a pinned git submodule (see scripts/fetch-llama.sh).
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp" CACHE PATH "llama.cpp source dir")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Run: sh scripts/fetch-llama.sh")
endif ()

# ---- llama.cpp configuration: static, CPU only, tuned for Snapdragon 8 Elite (Oryon, ARMv8.7) ----
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)          # ggml's own thread pool; no libomp to ship
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)          # cross-compiling: never probe the build host
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
# dotprod + i8mm + fp16 are present on every Snapdragon 8 Gen 1 and newer (incl. SM8750).
# The app checks /proc/cpuinfo before loading this library, so unsupported CPUs get a clear
# message instead of SIGILL.
# ETC fork (OS-ETC-IRIDIS): on armeabi-v7a (32-bit) those are arm64-only features — compile for
# armv7-a (NEON is on by default on NDK armv7) and disable KleidiAI; the Kotlin CpuFeatures
# check is ABI-aware (see LlamaNative).
if (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_CPU_ARM_ARCH "armv7-a" CACHE STRING "" FORCE)
    set(IRIS_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    # llamafile's SGEMM needs FP16 vector arithmetic (armv8.2+); 32-bit falls back to
    # llama.cpp's own NEON kernels.
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    # 32-bit: size_t is 32-bit and llama.cpp's OpenCL backend narrows int64_t in braced
    # initializer lists (fine on arm64 where size_t is 64-bit).
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-narrowing")
else()
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
    option(IRIS_KLEIDIAI "Use Arm KleidiAI micro-kernels (downloads at configure time)" ON)
    set(GGML_CPU_KLEIDIAI ${IRIS_KLEIDIAI} CACHE BOOL "" FORCE)
endif()

# ---- Adreno GPU via ggml's OpenCL backend ----
# The app never links the vendor libOpenCL.so directly: opencl/cl_shim.c forwards every cl* call
# to it through dlopen, so a device without an OpenCL driver simply reports "no GPU" and llama.cpp
# stays on the CPU instead of the whole library failing to load.
option(IRIS_OPENCL "Build ggml's OpenCL (Adreno) backend" ON)
if (IRIS_OPENCL)
    add_library(iris_cl_shim STATIC opencl/cl_shim.c)
    target_include_directories(iris_cl_shim PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/opencl)
    target_compile_options(iris_cl_shim PRIVATE -O2 -fvisibility=hidden)
    set(OpenCL_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY iris_cl_shim CACHE STRING "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
endif ()

# ---- Whole-program optimisation ----
# ThinLTO across iris_llama + llama + ggml (+ KleidiAI): lets the linker inline across the static
# libraries and drop everything the JNI surface never reaches. Must be set before add_subdirectory
# so llama.cpp's targets are compiled to bitcode too. Assembly sources are left alone.
option(IRIS_LTO "ThinLTO for the native library" ON)
if (IRIS_LTO)
    add_compile_options($<$<COMPILE_LANGUAGE:C,CXX>:-flto=thin>)
endif ()

add_subdirectory(${LLAMA_DIR} llama.cpp EXCLUDE_FROM_ALL)

add_library(iris_llama SHARED
        iris_engine.cpp
        jni_bridge.cpp)

target_compile_features(iris_llama PRIVATE cxx_std_17)
target_compile_options(iris_llama PRIVATE -Wall -Wextra -O3 -fvisibility=hidden)
target_link_libraries(iris_llama PRIVATE llama ggml android log)
if (IRIS_OPENCL)
    target_link_libraries(iris_llama PRIVATE iris_cl_shim dl)
endif ()
if (IRIS_LTO)
    # Keep -O3 code generation at link time (lld defaults to O2 for LTO codegen).
    target_link_options(iris_llama PRIVATE -flto=thin -Wl,--lto-O3)
endif ()
target_link_options(iris_llama PRIVATE
        -Wl,--gc-sections
        # Fold identical functions whose address is never taken (clang emits address-significance
        # tables by default, so this is the safe variant).
        -Wl,--icf=safe
        -Wl,--exclude-libs,ALL
        # 16 KB page-size alignment (required by Google Play for Android 15+ devices)
        -Wl,-z,max-page-size=16384)

# ---- MNN-LLM engine (full flavour: -DIRIS_MNN=ON) ----
# libMNN.a is MNN 3.6.1 cross-compiled by scripts/build-mnn-android.sh (CPU + ARMv8.2 FP16 +
# KleidiAI + OpenCL, LLM module, transformer fuse, low-memory weight dequant). It is linked
# whole-archive into one libiris_mnn.so: MNN registers its backends/ops from static initialisers,
# which a plain static link would drop.
option(IRIS_MNN "Build libiris_mnn.so (MNN-LLM engine)" OFF)
if (IRIS_MNN)
    set(IRIS_MNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mnn" CACHE PATH "prebuilt MNN")
    if (NOT EXISTS "${IRIS_MNN_DIR}/lib/${ANDROID_ABI}/libMNN.a")
        message(FATAL_ERROR "MNN not found at ${IRIS_MNN_DIR}. Run: sh scripts/build-mnn-android.sh")
    endif ()
    add_library(iris_mnn SHARED mnn/mnn_engine.cpp mnn/mnn_jni.cpp)
    target_compile_features(iris_mnn PRIVATE cxx_std_17)
    target_compile_options(iris_mnn PRIVATE -O2 -fvisibility=hidden -Wall)
    target_include_directories(iris_mnn PRIVATE ${IRIS_MNN_DIR}/include)
    target_link_libraries(iris_mnn PRIVATE
            -Wl,--whole-archive ${IRIS_MNN_DIR}/lib/${ANDROID_ABI}/libMNN.a -Wl,--no-whole-archive
            android log dl)
    target_link_options(iris_mnn PRIVATE
            -Wl,--gc-sections -Wl,--icf=safe -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
endif ()
