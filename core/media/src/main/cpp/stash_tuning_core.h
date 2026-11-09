// Tuning's engine core: Signalsmith Stretch set up and driven exactly as Stash plays it. The JNI
// layer (stash_tuning_jni.cpp) wraps this for the app; the host test (src/test/cpp) runs the same
// code on a desktop, so CI checks the pitch and the latency the app relies on without a phone.
//
// One stage per stream: every chunk goes in and the same number of frames comes out, delayed by
// latency() frames, a pitch shift that keeps the tempo. Draining the tail at the end of a stream
// is the caller's job: it feeds latency() frames of silence through process().

#pragma once

#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace stash_tuning {

// 200 ms blocks, a new one every third of a block, at every sample rate. Measured (a host build
// of this exact code, and SignalsmithPitchStageTest on a phone): the library's presets (120 ms
// blocks every 30 ms; 100 every 40 ms) have 7 to 9 Hz bins, too coarse to keep two bass notes a
// fourth apart (55 and 82.41 Hz) from pulling on each other: at 432 each lands up to 0.4 Hz off,
// and up to 2 Hz (over 60 cents) at the ends of the 392 to 494 Hz range. 200 ms gives 4.3 to
// 4.7 Hz bins and keeps every partial within about 0.1 Hz across the range, at the cost of 80 ms
// more latency. A third of a block (not the presets' quarter) keeps the bins it links for phase
// coherence to about 15 Hz apart, so separate notes stay separate. Same CPU at 44.1/48 kHz, about
// 1.5x above (the presets' cheaper one is gone: it was the least accurate). Also measured: attacks
// soften by a few ms, and each partial gets a faint image 15 Hz above it (about -35 dB, near the
// stock engine's own intermodulation in a chord). 280 ms blocks every 70 ms would remove the
// image (-56 dB) at 280 ms of latency.
constexpr double kBlockSeconds = 0.2;
constexpr int kIntervalsPerBlock = 3;

// Fixed seed: the stretcher only draws random numbers when time-stretching past 2x, which this
// stage never does, but a fixed seed keeps runs reproducible all the same.
constexpr long kSeed = 0x5743;

struct Tuner {
    explicit Tuner(int channelCount) : stretch(kSeed), channels(channelCount) {}

    signalsmith::stretch::SignalsmithStretch<float> stretch;
    const int channels;
    float factor = 1.0f;
    std::vector<float> in;   // interleaved input chunk (the stretcher's input and output must differ)
    std::vector<float> out;  // interleaved output chunk
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

// A stage for a channels x sampleRate stream, or nullptr (bad format, out of memory, a throw).
inline Tuner *create(int channels, int sampleRate) noexcept {
    if (channels <= 0 || channels > 32 || sampleRate <= 0) return nullptr;
    Tuner *t = new (std::nothrow) Tuner(channels);
    if (t == nullptr) return nullptr;
    try {
        // splitComputation off: it spreads the work over an extra interval of latency, which
        // helps a real-time audio callback, not a playback thread that writes ahead into a 250
        // to 750 ms AudioTrack buffer.
        const int block = static_cast<int>(sampleRate * kBlockSeconds);
        t->stretch.configure(channels, block, block / kIntervalsPerBlock, false);
        t->stretch.setTransposeFactor(1.0f);
        t->stretch.reset();
    } catch (...) {
        delete t;
        return nullptr;
    }
    return t;
}

inline void destroy(Tuner *t) noexcept { delete t; }

// The delay from input to output, in frames.
inline int latency(const Tuner *t) noexcept {
    return t->stretch.inputLatency() + t->stretch.outputLatency();
}

inline void setFactor(Tuner *t, float factor) noexcept {
    if (!(factor > 0.0f) || !isFiniteBits(factor) || factor == t->factor) return;
    t->factor = factor;
    t->stretch.setTransposeFactor(factor);
}

inline void reset(Tuner *t) noexcept { t->stretch.reset(); }

// Makes room for a chunk of frames in t->in and t->out; false when out of memory.
inline bool reserve(Tuner *t, int frames) noexcept {
    const size_t n = static_cast<size_t>(frames) * static_cast<size_t>(t->channels);
    try {
        if (t->in.size() < n) t->in.resize(n);
        if (t->out.size() < n) t->out.resize(n);
    } catch (...) {
        return false;
    }
    return true;
}

enum class Result {
    kOk,
    kResetNonFinite,  // the output went non-finite: the stage restarted, this chunk is silence
    kResetException,  // the library threw: the stage restarted, this chunk is silence
};

// Retunes the frames in t->in (filled by the caller, after reserve) into t->out.
inline Result process(Tuner *t, int frames) noexcept {
    const size_t n = static_cast<size_t>(frames) * static_cast<size_t>(t->channels);
    float *in = t->in.data();
    float *out = t->out.data();
    for (size_t i = 0; i < n; ++i) {
        if (!isFiniteBits(in[i])) in[i] = 0.0f;
    }
    try {
        t->stretch.process(InterleavedIn{in, t->channels}, frames, InterleavedOut{out, t->channels}, frames);
    } catch (...) {
        // Nothing may cross the JNI boundary (it would abort the app): start clean instead.
        try { t->stretch.reset(); } catch (...) {}
        std::memset(out, 0, n * sizeof(float));
        return Result::kResetException;
    }
    // A non-finite sample out means the state is poisoned: start clean rather than stay silent.
    for (size_t i = 0; i < n; ++i) {
        if (!isFiniteBits(out[i])) {
            t->stretch.reset();
            std::memset(out, 0, n * sizeof(float));
            return Result::kResetNonFinite;
        }
    }
    return Result::kOk;
}

}  // namespace stash_tuning
