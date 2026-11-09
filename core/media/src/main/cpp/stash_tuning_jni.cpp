// Tuning: Signalsmith Stretch as a pitch stage for Stash's float output stage, for the JVM.
//
// The Kotlin side (SignalsmithPitchStage) hands over interleaved float frames, in place, in
// chunks of up to ~2048 frames; stash_tuning_core.h does the work (and says how the engine is set
// up). Every chunk goes in and the same number of frames comes out, delayed by latency() frames.
// Draining the tail at the end of a stream is the caller's job: it feeds latency() frames of
// silence through process(), the library's own recipe for ending a stream ("pass an additional
// inputLatency() of silence", then the output latency), done as one run of silence so the stage
// needs no second code path.
//
// Built optimised in every variant (see CMakeLists.txt): the library is all number-crunching and
// is up to 10x slower unoptimised.

#include <jni.h>
#include <android/log.h>

#include <atomic>

#include "stash_tuning_core.h"

namespace {

// Bumped when the native contract changes; the Kotlin side only checks that it is positive.
constexpr jint kNativeVersion = 1;

inline stash_tuning::Tuner *from(jlong handle) { return reinterpret_cast<stash_tuning::Tuner *>(handle); }

// Said once per process: a library that throws once is likely to again, and the log would flood.
std::atomic<bool> loggedException{false};

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_version(JNIEnv *, jclass) {
    return kNativeVersion;
}

JNIEXPORT jlong JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_create(JNIEnv *, jclass, jint channels, jint sampleRate) {
    return reinterpret_cast<jlong>(stash_tuning::create(channels, sampleRate));
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_destroy(JNIEnv *, jclass, jlong handle) {
    stash_tuning::destroy(from(handle));
}

JNIEXPORT jint JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_latency(JNIEnv *, jclass, jlong handle) {
    auto *t = from(handle);
    return t == nullptr ? 0 : stash_tuning::latency(t);
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_setFactor(JNIEnv *, jclass, jlong handle, jfloat factor) {
    auto *t = from(handle);
    if (t != nullptr) stash_tuning::setFactor(t, factor);
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_reset(JNIEnv *, jclass, jlong handle) {
    auto *t = from(handle);
    if (t == nullptr) return;
    try { stash_tuning::reset(t); } catch (...) {}
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_process(
    JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint frames) {
    auto *t = from(handle);
    if (t == nullptr || samples == nullptr || frames <= 0) return;
    const jsize n = frames * t->channels;
    if (env->GetArrayLength(samples) < n) return;
    // Out of memory: leave the chunk as it was rather than crash the player.
    if (!stash_tuning::reserve(t, frames)) return;
    env->GetFloatArrayRegion(samples, 0, n, t->in.data());
    if (env->ExceptionCheck()) return;

    if (stash_tuning::process(t, frames) == stash_tuning::Result::kResetException &&
        !loggedException.exchange(true)) {
        __android_log_print(ANDROID_LOG_WARN, "StashTuning",
                            "pitch stage threw: restarted it, one chunk silenced (said once)");
    }
    env->SetFloatArrayRegion(samples, 0, n, t->out.data());
}

}  // extern "C"
