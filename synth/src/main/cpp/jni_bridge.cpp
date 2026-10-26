// JNI entry points for com.grulla.music.synth.NativeSynth.
#include <android/log.h>
#include <fluidsynth.h>
#include <jni.h>

#include <memory>
#include <string>

#include "engine.h"

using grulla::Engine;
using grulla::Event;
using grulla::Sequence;

namespace {

Engine* engineOf(jlong handle) { return reinterpret_cast<Engine*>(handle); }

void logToAndroid(int level, const char* message, void*) {
    const int priority = level <= FLUID_ERR ? ANDROID_LOG_ERROR : level == FLUID_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(priority, "FluidSynth", "%s", message);
}

std::string toString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_grulla_music_synth_NativeSynth_nativeCreate(JNIEnv* env, jclass, jobjectArray error) {
    static bool logging = false;
    if (!logging) {
        for (int level : {FLUID_PANIC, FLUID_ERR, FLUID_WARN, FLUID_INFO}) fluid_set_log_function(level, logToAndroid, nullptr);
        fluid_set_log_function(FLUID_DBG, nullptr, nullptr);
        logging = true;
    }
    auto engine = std::make_unique<Engine>();
    const std::string message = engine->open();
    if (!message.empty()) {
        env->SetObjectArrayElement(error, 0, env->NewStringUTF(message.c_str()));
        return 0;
    }
    return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT void JNICALL Java_com_grulla_music_synth_NativeSynth_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete engineOf(handle);
}

JNIEXPORT jboolean JNICALL Java_com_grulla_music_synth_NativeSynth_nativeLoadSoundFont(JNIEnv* env, jclass, jlong handle,
                                                                                       jstring path) {
    return engineOf(handle)->loadSoundFont(toString(env, path)) ? JNI_TRUE : JNI_FALSE;
}

/**
 * timesMs[i] is when packed[i] happens; packed = status | data1 << 8 | data2 << 16.
 */
JNIEXPORT void JNICALL Java_com_grulla_music_synth_NativeSynth_nativeSetSequence(JNIEnv* env, jclass, jlong handle,
                                                                                 jdoubleArray timesMs, jintArray packed,
                                                                                 jdouble endMs) {
    Engine* engine = engineOf(handle);
    const jsize count = env->GetArrayLength(packed);
    auto sequence = std::make_unique<Sequence>();
    sequence->events.reserve(count);
    jdouble* times = env->GetDoubleArrayElements(timesMs, nullptr);
    jint* messages = env->GetIntArrayElements(packed, nullptr);
    for (jsize i = 0; i < count; ++i) {
        const uint32_t m = static_cast<uint32_t>(messages[i]);
        sequence->events.push_back(Event{engine->msToFrames(times[i]), static_cast<uint8_t>(m & 0xFF),
                                         static_cast<uint8_t>((m >> 8) & 0x7F), static_cast<uint8_t>((m >> 16) & 0x7F)});
    }
    env->ReleaseIntArrayElements(packed, messages, JNI_ABORT);
    env->ReleaseDoubleArrayElements(timesMs, times, JNI_ABORT);
    sequence->endFrame = engine->msToFrames(endMs);
    engine->setSequence(std::move(sequence));
}

JNIEXPORT void JNICALL Java_com_grulla_music_synth_NativeSynth_nativePlay(JNIEnv*, jclass, jlong handle) {
    engineOf(handle)->play();
}

JNIEXPORT void JNICALL Java_com_grulla_music_synth_NativeSynth_nativePause(JNIEnv*, jclass, jlong handle) {
    engineOf(handle)->pause();
}

JNIEXPORT void JNICALL Java_com_grulla_music_synth_NativeSynth_nativeStop(JNIEnv*, jclass, jlong handle) {
    engineOf(handle)->stop();
}

JNIEXPORT void JNICALL Java_com_grulla_music_synth_NativeSynth_nativeSetLoop(JNIEnv*, jclass, jlong handle, jdouble startMs,
                                                                             jdouble endMs) {
    engineOf(handle)->setLoop(startMs, endMs);
}

JNIEXPORT jdouble JNICALL Java_com_grulla_music_synth_NativeSynth_nativePositionMs(JNIEnv*, jclass, jlong handle) {
    return engineOf(handle)->positionMs();
}

JNIEXPORT jboolean JNICALL Java_com_grulla_music_synth_NativeSynth_nativeIsPlaying(JNIEnv*, jclass, jlong handle) {
    return engineOf(handle)->isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_grulla_music_synth_NativeSynth_nativeConsumeCompleted(JNIEnv*, jclass, jlong handle) {
    return engineOf(handle)->consumeCompleted() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_grulla_music_synth_NativeSynth_nativeSampleRate(JNIEnv*, jclass, jlong handle) {
    return engineOf(handle)->sampleRate();
}

}  // extern "C"
