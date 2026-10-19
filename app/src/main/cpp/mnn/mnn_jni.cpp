// Thin JNI adapter for MnnEngine (libiris_mnn.so, full flavour only).
// Strings cross the boundary as UTF-8 byte arrays (Java's "modified UTF-8" mangles emoji).
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <algorithm>
#include <new>
#include <string>
#include <vector>

#include "mnn_engine.h"

#define LOG_TAG "IrisMnn"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using iris::MnnEngine;

namespace {

std::string bytesToString(JNIEnv* env, jbyteArray a) {
    if (a == nullptr) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

jstring toJString(JNIEnv* env, const std::string& s) {
    // Only ASCII/JSON-escaped text is returned through jstring; messages with CJK are fine because
    // NewStringUTF accepts standard UTF-8 for BMP characters (no 4-byte sequences in our messages).
    return env->NewStringUTF(s.c_str());
}

MnnEngine* E(jlong h) { return reinterpret_cast<MnnEngine*>(h); }

std::vector<int> intsOf(JNIEnv* env, jintArray a) {
    std::vector<int> out;
    const jsize n = a != nullptr ? env->GetArrayLength(a) : 0;
    if (n > 0) {
        std::vector<jint> raw(static_cast<size_t>(n));
        env->GetIntArrayRegion(a, 0, n, raw.data());
        out.assign(raw.begin(), raw.end());
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeCreate(JNIEnv*, jclass) {
    auto* e = new (std::nothrow) MnnEngine();
    return reinterpret_cast<jlong>(e);
}

// 1.8.0 crash diagnostics: where to keep iris_mnn_load.log ("" = off). The settings button exports it.
extern "C" void irisSetNoVisual(int enabled);
JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeSetNoVisual(JNIEnv*, jclass, jboolean enabled) {
    irisSetNoVisual(enabled == JNI_TRUE ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeSetDiagDir(JNIEnv* env, jclass, jbyteArray dir) {
    if (dir == nullptr) { iris::setDiagDir(""); return; }
    jsize n = env->GetArrayLength(dir);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(dir, 0, n, reinterpret_cast<jbyte*>(&s[0]));
    iris::setDiagDir(s);
}

JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete E(h);
}

JNIEXPORT jboolean JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeHasOpenCl(JNIEnv*, jclass) {
    // Same candidates MNN's OpenCL wrapper tries first; the app manifest declares them as
    // uses-native-library so the vendor driver is visible to us on Android 12+.
    static const char* kLibs[] = {"libOpenCL.so", "libOpenCL_adreno.so", "/vendor/lib64/libOpenCL.so",
                                  "/system/vendor/lib64/libOpenCL.so"};
    for (const char* l : kLibs) {
        void* h = dlopen(l, RTLD_NOW | RTLD_LOCAL);
        if (h != nullptr) {
            bool ok = dlsym(h, "clGetPlatformIDs") != nullptr;
            dlclose(h);
            if (ok) return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

// 2.0.4: same probe, but returns why it failed ("" = usable) so the fallback note can say it.
JNIEXPORT jstring JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeOpenClProbe(JNIEnv* env, jclass) {
    static const char* kLibs[] = {"libOpenCL.so", "libOpenCL_adreno.so", "/vendor/lib64/libOpenCL.so",
                                  "/vendor/lib64/libOpenCL_adreno.so", "/system/vendor/lib64/libOpenCL.so",
                                  "/system/lib64/libOpenCL.so"};
    std::string why;
    for (const char* l : kLibs) {
        void* h = dlopen(l, RTLD_NOW | RTLD_LOCAL);
        if (h != nullptr) {
            bool ok = dlsym(h, "clGetPlatformIDs") != nullptr;
            dlclose(h);
            if (ok) return env->NewStringUTF("");
            why += std::string(l) + ": no clGetPlatformIDs; ";
        } else {
            const char* e = dlerror();
            if (why.size() < 600) why += std::string(e != nullptr ? e : l) + "; ";
        }
    }
    return toJString(env, why.empty() ? std::string("unknown") : why);
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeLoad(JNIEnv* env, jclass, jlong h, jbyteArray configPath,
                                                      jstring backend, jint threads, jint nCtx,
                                                      jbyteArray tmpDir, jboolean useMmap,
                                                      jintArray cpus, jint nice, jboolean applyPolicy) {
    if (h == 0) return toJString(env, "engine not created");
    iris::MnnLoadParams p;
    p.configPath = bytesToString(env, configPath);
    const char* b = backend != nullptr ? env->GetStringUTFChars(backend, nullptr) : nullptr;
    p.backend = b != nullptr ? b : "cpu";
    if (b != nullptr) env->ReleaseStringUTFChars(backend, b);
    p.threads = threads;
    p.contextLength = nCtx;
    p.tmpDir = bytesToString(env, tmpDir);
    p.useMmap = useMmap == JNI_TRUE;
    p.applyPolicy = applyPolicy == JNI_TRUE;
    p.cpus = intsOf(env, cpus);
    p.nice = nice;
    std::string err = E(h)->load(p);
    if (err.empty()) return nullptr;
    LOGW("load failed: %s", err.c_str());
    return toJString(env, err);
}

JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeUnload(JNIEnv*, jclass, jlong h) {
    if (h != 0) E(h)->unload();
}

JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeCancel(JNIEnv*, jclass, jlong h) {
    if (h != 0) E(h)->cancel();
}

JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeClearCancel(JNIEnv*, jclass, jlong h) {
    if (h != 0) E(h)->clearCancel();
}

JNIEXPORT void JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeReset(JNIEnv*, jclass, jlong h) {
    if (h != 0) E(h)->reset();
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeMeta(JNIEnv* env, jclass, jlong h) {
    return toJString(env, h != 0 ? E(h)->meta() : "{}");
}

// 1.7.5: re-pin MNN's existing threads (any thread, during or between replies). JSON MnnLiveReport.
JNIEXPORT jstring JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeApplyLive(JNIEnv* env, jclass, jlong h, jintArray cpus, jint nice) {
    if (h == 0) return toJString(env, "{\"targets\":0}");
    return toJString(env, E(h)->applyLive(intsOf(env, cpus), nice).toJson());
}

// 1.7.5: pool worker tids still alive + the last generating thread (for the IrisResource CPU report).
JNIEXPORT jintArray JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeThreadIds(JNIEnv* env, jclass, jlong h) {
    std::vector<int> ids;
    if (h != 0) ids = E(h)->threadIds();
    jintArray out = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (out != nullptr && !ids.empty()) {
        std::vector<jint> raw(ids.begin(), ids.end());
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(raw.size()), raw.data());
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativePoolThreads(JNIEnv*, jclass, jlong h) {
    return h != 0 ? E(h)->poolThreads() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_iris_localai_engine_mnn_MnnNative_nativeGenerate(JNIEnv* env, jclass, jlong h, jobjectArray roles,
                                                          jobjectArray contents, jfloatArray floats,
                                                          jintArray ints, jintArray cpus, jobject sink) {
    iris::MnnStats err;
    if (h == 0 || roles == nullptr || contents == nullptr || floats == nullptr || ints == nullptr) {
        err.error = "bad arguments";
        return toJString(env, err.toJson());
    }
    iris::ChatMsgs msgs;
    jsize n = env->GetArrayLength(roles);
    if (env->GetArrayLength(contents) != n) {
        err.error = "bad arguments";
        return toJString(env, err.toJson());
    }
    msgs.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto r = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
        auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        msgs.emplace_back(bytesToString(env, r), bytesToString(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    float f[4] = {0.7f, 0.95f, 0.05f, 1.1f};
    jint iv[5] = {40, 2048, 0, 0, 0};
    if (env->GetArrayLength(floats) >= 4) env->GetFloatArrayRegion(floats, 0, 4, f);
    const jsize ni = env->GetArrayLength(ints);
    if (ni >= 3) env->GetIntArrayRegion(ints, 0, std::min<jsize>(ni, 5), iv);
    iris::MnnSampling s;
    s.temperature = f[0];
    s.topP = f[1];
    s.minP = f[2];
    s.repeatPenalty = f[3];
    s.topK = iv[0];
    s.maxNewTokens = iv[1];
    s.disableThinking = iv[2] != 0;
    // 1.7.4: ints[3] = nice, ints[4] = 1 → apply the tier's cores/nice to this thread while generating.
    s.nice = iv[3];
    s.applyPolicy = iv[4] != 0;
    s.cpus = intsOf(env, cpus);

    jmethodID onPiece = nullptr;
    if (sink != nullptr) {
        jclass cls = env->GetObjectClass(sink);
        onPiece = env->GetMethodID(cls, "onPiece", "([B)Z");
        env->DeleteLocalRef(cls);
        if (onPiece == nullptr) {
            env->ExceptionClear();
            err.error = "sink has no onPiece([B)Z";
            return toJString(env, err.toJson());
        }
    }
    auto cb = [&](const std::string& piece) -> bool {
        if (sink == nullptr) return true;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(piece.size()));
        if (arr == nullptr) { env->ExceptionClear(); return false; }
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(piece.size()), reinterpret_cast<const jbyte*>(piece.data()));
        jboolean keep = env->CallBooleanMethod(sink, onPiece, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            // Never let a Kotlin exception stay pending while native code keeps running.
            env->ExceptionDescribe();
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };
    iris::MnnStats st = E(h)->generate(msgs, s, cb);
    return toJString(env, st.toJson());
}

} // extern "C"
