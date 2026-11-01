// RecallVault on-device transcription JNI bridge.
//
// Thin wrapper over the whisper.cpp C API (include/whisper.h, v1.9.5):
// init a context from a ggml model file, run a full greedy
// transcription over 16 kHz mono float samples, return the joined
// segment text. Built into librecallwhisper.so with whisper + ggml
// linked statically (see CMakeLists.txt / build.sh).
#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "RecallVaultWhisper"

extern "C" JNIEXPORT jlong JNICALL
Java_com_irwinlabs_recallvault_Whisper_initContext(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "whisper_init_from_file failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_irwinlabs_recallvault_Whisper_transcribe(JNIEnv *env, jclass, jlong ctxPtr,
                                                  jfloatArray samples, jint nThreads) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) return nullptr;

    jsize n = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) return nullptr;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.language = "en";
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    int rc = whisper_full(ctx, params, pcm, (int) n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string out;
    const int nSeg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSeg; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) out += seg;
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_irwinlabs_recallvault_Whisper_freeContext(JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}
