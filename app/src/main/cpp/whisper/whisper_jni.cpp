#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

namespace {
constexpr const char *TAG = "DictationWhisper";

std::string transcribe(whisper_context *context, int threads, const float *audio, size_t length,
                       const char *language) {
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    const bool detect_language = language == nullptr || std::string(language) == "auto";
    params.language = detect_language ? nullptr : language;
    params.detect_language = detect_language;
    params.n_threads = threads;
    params.no_context = true;
    params.single_segment = false;
    params.no_timestamps = true;

    if (whisper_full(context, params, audio, length) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed");
        return {};
    }

    std::string result;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        result += whisper_full_get_segment_text(context, i);
    }
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_dictationhelper_speech_WhisperNative_initContext(JNIEnv *env, jclass,
                                                                    jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    auto params = whisper_context_default_params();
    auto *context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_dictationhelper_speech_WhisperNative_freeContext(JNIEnv *, jclass, jlong pointer) {
    whisper_free(reinterpret_cast<whisper_context *>(pointer));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dictationhelper_speech_WhisperNative_transcribe(JNIEnv *env, jclass,
                                                                   jlong pointer, jint threads,
                                                                   jfloatArray audio,
                                                                   jstring language) {
    auto *context = reinterpret_cast<whisper_context *>(pointer);
    const jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize length = env->GetArrayLength(audio);
    const char *language_chars = language == nullptr ? nullptr : env->GetStringUTFChars(language, nullptr);
    const std::string result = transcribe(context, threads, samples, static_cast<size_t>(length), language_chars);
    if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
    env->ReleaseFloatArrayElements(audio, const_cast<jfloat *>(samples), JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}
