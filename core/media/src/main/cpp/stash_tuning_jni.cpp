// Tuning: Signalsmith Stretch as a pitch stage for Stash's float output stage.
//
// The Kotlin side (SignalsmithPitchStage) hands over interleaved float frames, in place, in
// chunks of up to ~2048 frames. Every chunk goes in and the same number of frames comes out,
// delayed by latency() frames: a pitch shift that keeps the tempo (no time stretch). Draining the
// tail at the end of a stream is the caller's job: it feeds latency() frames of silence through
// process(), which is the library's own recipe for ending a stream ("pass an additional
// inputLatency() of silence", then the output latency), done as one run of silence so the stage
// needs no second code path.
//
// Built optimised in every variant (see CMakeLists.txt): the library is all number-crunching and
// is up to 10x slower unoptimised.

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace {

// Bumped when the native contract changes; the Kotlin side only checks that it is positive.
constexpr jint kNativeVersion = 1;

// Above this rate the cheaper preset (shorter block, longer interval) keeps the CPU cost of
// 88.2 to 192 kHz streams in check; the quality difference at a third of a semitone is negligible.
constexpr int kCheaperAboveHz = 48000;

// Fixed seed: the stretcher only draws random numbers when time-stretching past 2x, which this
// stage never does, but a fixed seed keeps runs reproducible all the same.
constexpr long kSeed = 0x5743;

struct Tuner {
    explicit Tuner(int channelCount) : stretch(kSeed), channels(channelCount) {}

    signalsmith::stretch::SignalsmithStretch<float> stretch;
    const int channels;
    float factor = 1.0f;
    std::vector<float> in;   // interleaved copy of the chunk (input and output must differ)
    std::vector<float> out;  // interleaved output, copied back over the caller's array
};

// The stretcher reads and writes buffer[channel][index]; these view an interleaved buffer that way.
struct InterleavedIn {
    const float *data;
    int channels;
    struct Channel {
        const float *p;
        int stride;
        float operator[](int i) const { return p[static_cast<size_t>(i) * stride]; }
    };
    Channel operator[](int c) const { return Channel{data + c, channels}; }
};

struct InterleavedOut {
    float *data;
    int channels;
    struct Channel {
        float *p;
        int stride;
        float &operator[](int i) const { return p[static_cast<size_t>(i) * stride]; }
    };
    Channel operator[](int c) const { return Channel{data + c, channels}; }
};

// -ffast-math lets the compiler assume no NaN or infinity, so std::isfinite can be folded away.
// Test the exponent bits instead: a NaN fed to a phase vocoder would live on in its state.
inline bool isFiniteBits(float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof bits);
    return (bits & 0x7f800000u) != 0x7f800000u;
}

inline Tuner *from(jlong handle) { return reinterpret_cast<Tuner *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_version(JNIEnv *, jclass) {
    return kNativeVersion;
}

JNIEXPORT jlong JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_create(JNIEnv *, jclass, jint channels, jint sampleRate) {
    if (channels <= 0 || channels > 32 || sampleRate <= 0) return 0;
    Tuner *t = new (std::nothrow) Tuner(channels);
    if (t == nullptr) return 0;
    try {
        // splitComputation off for both presets: it spreads the work over an extra interval of
        // latency, which helps a real-time audio callback, not a playback thread that writes
        // ahead into a 250 to 750 ms AudioTrack buffer.
        if (sampleRate > kCheaperAboveHz) {
            t->stretch.presetCheaper(channels, static_cast<float>(sampleRate), false);
        } else {
            t->stretch.presetDefault(channels, static_cast<float>(sampleRate), false);
        }
        t->stretch.setTransposeFactor(1.0f);
        t->stretch.reset();
    } catch (...) {
        delete t;
        return 0;
    }
    return reinterpret_cast<jlong>(t);
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_destroy(JNIEnv *, jclass, jlong handle) {
    delete from(handle);
}

JNIEXPORT jint JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_latency(JNIEnv *, jclass, jlong handle) {
    Tuner *t = from(handle);
    if (t == nullptr) return 0;
    return t->stretch.inputLatency() + t->stretch.outputLatency();
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_setFactor(JNIEnv *, jclass, jlong handle, jfloat factor) {
    Tuner *t = from(handle);
    if (t == nullptr || !(factor > 0.0f) || !isFiniteBits(factor)) return;
    if (factor == t->factor) return;
    t->factor = factor;
    t->stretch.setTransposeFactor(factor);
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_reset(JNIEnv *, jclass, jlong handle) {
    Tuner *t = from(handle);
    if (t == nullptr) return;
    t->stretch.reset();
}

JNIEXPORT void JNICALL
Java_com_stash_core_media_equalizer_dsp_SignalsmithNative_process(
    JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint frames) {
    Tuner *t = from(handle);
    if (t == nullptr || samples == nullptr || frames <= 0) return;
    const size_t n = static_cast<size_t>(frames) * static_cast<size_t>(t->channels);
    if (static_cast<size_t>(env->GetArrayLength(samples)) < n) return;
    try {
        if (t->in.size() < n) t->in.resize(n);
        if (t->out.size() < n) t->out.resize(n);
    } catch (...) {
        return;  // out of memory: leave the chunk as it was rather than crash the player
    }
    env->GetFloatArrayRegion(samples, 0, static_cast<jsize>(n), t->in.data());
    if (env->ExceptionCheck()) return;

    float *in = t->in.data();
    for (size_t i = 0; i < n; ++i) {
        if (!isFiniteBits(in[i])) in[i] = 0.0f;
    }

    t->stretch.process(InterleavedIn{in, t->channels}, frames, InterleavedOut{t->out.data(), t->channels}, frames);

    // A non-finite sample out means the state is poisoned: start clean rather than stay silent.
    float *out = t->out.data();
    for (size_t i = 0; i < n; ++i) {
        if (!isFiniteBits(out[i])) {
            t->stretch.reset();
            std::memset(out, 0, n * sizeof(float));
            break;
        }
    }
    env->SetFloatArrayRegion(samples, 0, static_cast<jsize>(n), out);
}

}  // extern "C"
