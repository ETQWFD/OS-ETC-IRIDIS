# ---- JNI contract with libiris_llama.so ----
# Native code calls these by name; they must survive shrinking/obfuscation.
-keep class com.iris.localai.engine.llama.LlamaNative { native <methods>; *; }
-keep interface com.iris.localai.engine.llama.PieceSink { *; }
-keep interface com.iris.localai.engine.llama.LoadProgressListener { *; }
-keepclassmembers class * implements com.iris.localai.engine.llama.PieceSink { boolean onPiece(byte[]); }
-keepclassmembers class * implements com.iris.localai.engine.llama.LoadProgressListener { boolean onProgress(float); }

# ---- sherpa-onnx: JNI reflects on its Kotlin config classes' fields ----
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ---- LiteRT-LM ----
# LiteRT-LM: JNI calls back into Kotlin classes by name; tool/JSON layers use reflection + Gson.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.iris.localai.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.iris.localai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# sherpa-onnx TTS callback: JNI resolves invoke([F)Ljava/lang/Integer; by name
-keep class com.iris.localai.voice.TtsChunkCallback { *; }

# ---- JNI contract with libiris_mnn.so (full flavour) ----
-keep class com.iris.localai.engine.mnn.MnnNative { native <methods>; *; }
-keep interface com.iris.localai.engine.mnn.MnnPieceSink { *; }
-keepclassmembers class * implements com.iris.localai.engine.mnn.MnnPieceSink { boolean onPiece(byte[]); }

# commons-compress: only the bzip2 + tar readers are used; optional codecs reference absent classes.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**

# 1.13.0: verbose/debug logs are dropped from release (info+ stay for logcat diagnostics).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
