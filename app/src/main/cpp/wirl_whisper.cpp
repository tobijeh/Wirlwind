// JNI bridge between org.wirl.WhisperNative and whisper.cpp. Kotlin owns threads and lifetimes: one transcription at a
// time per context, and a context is freed only when nothing is using it.
#include <jni.h>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

// Builds a Java String from UTF-8 bytes. NewStringUTF expects "modified" UTF-8 and mishandles emoji and other 4-byte characters.
jstring toJavaString(JNIEnv *env, const std::string &utf8) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(utf8.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(utf8.size()), reinterpret_cast<const jbyte *>(utf8.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jstring charset = env->NewStringUTF("UTF-8");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    auto result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    return result;
}

} // namespace

extern "C" {

// Returns a context handle, or 0 if the model file can't be loaded.
JNIEXPORT jlong JNICALL
Java_org_wirl_WhisperNative_nativeInit(JNIEnv *env, jobject /* thiz */, jstring path) {
    const char *modelPath = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(modelPath, params);
    env->ReleaseStringUTFChars(path, modelPath);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_org_wirl_WhisperNative_nativeFree(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// Transcribes 16 kHz mono float samples. Returns the text, or null if whisper.cpp reports an error.
JNIEXPORT jstring JNICALL
Java_org_wirl_WhisperNative_nativeTranscribe(JNIEnv *env, jobject /* thiz */, jlong handle, jfloatArray pcm,
                                             jstring language, jstring prompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *initial = prompt != nullptr ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang;
    params.translate = false;
    // Each chunk stands alone: no timestamps, and no text carried over from earlier chunks.
    params.no_timestamps = true;
    params.no_context = true;
    params.single_segment = false;
    // Greedy decoding without temperature fallback. The fallback re-decodes a chunk at higher temperatures whenever the
    // first attempt looks uncertain, which can multiply the time a short dictation takes.
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    // Dictionary terms, so names and special words are recognized at the speech stage.
    params.initial_prompt = (initial != nullptr && initial[0] != '\0') ? initial : nullptr;

    jsize count = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(ctx, params, samples, count);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    std::string text;
    if (rc == 0) {
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            const char *piece = whisper_full_get_segment_text(ctx, i);
            if (piece != nullptr) text += piece;
        }
    }

    env->ReleaseStringUTFChars(language, lang);
    if (initial != nullptr) env->ReleaseStringUTFChars(prompt, initial);

    if (rc != 0) return nullptr;
    return toJavaString(env, text);
}

} // extern "C"
