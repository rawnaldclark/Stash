// Host test of Tuning's engine core (stash_tuning_core.h, the code the app's JNI layer runs), for
// CI: no phone, no JVM. It is what catches a re-vendored Signalsmith header that lost the STASH
// PATCH (every retuned partial 0.4 to 3 Hz off), a block size that lets bass notes pull on each
// other, and a reported latency that no longer matches the real delay (position compensation and
// the end-of-stream tail rely on it). SignalsmithPitchStageTest checks the same on a phone.
//
// Each run goes through the stage the way the output stage drives it: chunks of 2048 frames, then
// the stage's latency of silence. Exit code 0 when every check passes.

#include <cmath>
#include <cstdio>
#include <vector>

#include "stash_tuning_core.h"

namespace {

constexpr int kChannels = 2;
constexpr int kChunk = 2048;  // ConvertingWriter.DEFAULT_CHUNK_FRAMES
constexpr double kPi = 3.14159265358979323846;
constexpr double kFactor = 432.0 / 440.0;
constexpr double kPitchToleranceHz = 0.5;
constexpr double kDelayToleranceMs = 1.0;
constexpr double kEdgeToleranceMs = 5.0;

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        ++failures;
        std::printf("  FAIL: %s\n", what);
    }
}

// Runs channel-0 signal x (n frames, copied to both channels) through a fresh stage at 432;
// returns channel 0 of the n + latency frames out.
std::vector<double> run(int rate, const std::vector<double> &x, int &latencyOut) {
    stash_tuning::Tuner *t = stash_tuning::create(kChannels, rate);
    if (t == nullptr) {
        check(false, "stage created");
        latencyOut = 0;
        return {};
    }
    stash_tuning::setFactor(t, static_cast<float>(kFactor));
    const int latency = stash_tuning::latency(t);
    latencyOut = latency;
    const int n = static_cast<int>(x.size());
    const int total = n + latency;
    std::vector<double> y(total, 0.0);
    for (int f = 0; f < total; f += kChunk) {
        const int frames = total - f < kChunk ? total - f : kChunk;
        stash_tuning::reserve(t, frames);
        for (int i = 0; i < frames; ++i) {
            const float v = f + i < n ? static_cast<float>(x[f + i]) : 0.0f;
            t->in[i * kChannels] = v;
            t->in[i * kChannels + 1] = v;
        }
        check(stash_tuning::process(t, frames) == stash_tuning::Result::kOk, "chunk processed cleanly");
        for (int i = 0; i < frames; ++i) y[f + i] = t->out[i * kChannels];
    }
    stash_tuning::destroy(t);
    return y;
}

void fft(std::vector<double> &re, std::vector<double> &im) {
    const size_t n = re.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = -2 * kPi / static_cast<double>(len);
        for (size_t i = 0; i < n; i += len) {
            for (size_t k = 0; k < len / 2; ++k) {
                const double cr = std::cos(ang * k), ci = std::sin(ang * k);
                const size_t a = i + k, b = i + k + len / 2;
                const double xr = re[b] * cr - im[b] * ci;
                const double xi = re[b] * ci + im[b] * cr;
                re[b] = re[a] - xr;
                im[b] = im[a] - xi;
                re[a] += xr;
                im[a] += xi;
            }
        }
    }
}

// The strongest peak within 3 Hz of hz in mag, refined by a parabola through log magnitudes.
double peakNear(const std::vector<double> &mag, double hz, double binHz) {
    int lo = static_cast<int>((hz - 3.0) / binHz), hi = static_cast<int>((hz + 3.0) / binHz);
    if (lo < 1) lo = 1;
    if (hi > static_cast<int>(mag.size()) - 2) hi = static_cast<int>(mag.size()) - 2;
    int k = lo;
    for (int i = lo; i <= hi; ++i) {
        if (mag[i] > mag[k]) k = i;
    }
    const double a = std::log(mag[k - 1] + 1e-30), b = std::log(mag[k] + 1e-30), c = std::log(mag[k + 1] + 1e-30);
    const double d = a - 2 * b + c;
    const double offset = std::fabs(d) < 1e-12 ? 0.0 : 0.5 * (a - c) / d;
    return (k + offset) * binHz;
}

// Every partial of partials comes out at hz x 432/440, within kPitchToleranceHz.
void checkPitch(int rate, const std::vector<double> &partials) {
    const int n = rate * 4;
    std::vector<double> x(n, 0.0);
    for (int f = 0; f < n; ++f) {
        for (double p : partials) x[f] += 0.15 * std::sin(2 * kPi * p * f / rate);
    }
    int latency = 0;
    const std::vector<double> y = run(rate, x, latency);
    if (y.empty()) return;
    // A clean stretch from 0.5 s after the stage filled up, a power of two long, Hann-windowed
    // and zero-padded 4x.
    const size_t segment = rate > 48000 ? (1u << 18) : (1u << 17);
    const size_t start = static_cast<size_t>(latency + rate / 2);
    const size_t pad = segment * 4;
    std::vector<double> re(pad, 0.0), im(pad, 0.0);
    for (size_t i = 0; i < segment; ++i) {
        re[i] = y[start + i] * (0.5 - 0.5 * std::cos(2 * kPi * i / (segment - 1)));
    }
    fft(re, im);
    std::vector<double> mag(pad / 2);
    for (size_t i = 0; i < pad / 2; ++i) mag[i] = std::sqrt(re[i] * re[i] + im[i] * im[i]);
    const double binHz = static_cast<double>(rate) / pad;
    std::printf("%d Hz, latency %d:", rate, latency);
    for (double p : partials) {
        const double want = p * kFactor;
        const double got = peakNear(mag, want, binHz);
        std::printf(" %.2f -> %.2f (want %.2f)", p, got, want);
        char what[96];
        std::snprintf(what, sizeof what, "%.2f Hz at %d Hz within %.1f Hz of %.2f", p, rate, kPitchToleranceHz, want);
        if (std::fabs(got - want) > kPitchToleranceHz) {
            std::printf("\n");
            check(false, what);
        }
    }
    std::printf("\n");
}

double centroid(const std::vector<double> &s) {
    double e = 0, m = 0;
    for (size_t i = 0; i < s.size(); ++i) {
        e += s[i] * s[i];
        m += s[i] * s[i] * static_cast<double>(i);
    }
    return m / e;
}

// First and last frame where the 10 ms RMS envelope is over half its peak.
void edges(const std::vector<double> &s, int rate, int &first, int &last) {
    const int win = rate / 100;
    std::vector<double> env(s.size());
    double acc = 0;
    for (size_t f = 0; f < s.size(); ++f) {
        acc += s[f] * s[f];
        if (f >= static_cast<size_t>(win)) acc -= s[f - win] * s[f - win];
        env[f] = std::sqrt(acc > 0 ? acc / win : 0.0);
    }
    double peak = 0;
    for (double v : env) peak = v > peak ? v : peak;
    first = -1;
    last = -1;
    for (size_t f = 0; f < env.size(); ++f) {
        if (env[f] > peak / 2) {
            if (first < 0) first = static_cast<int>(f);
            last = static_cast<int>(f);
        }
    }
}

// A one-second 440 Hz burst comes out delayed by exactly the latency the stage reports, as long.
void checkTiming(int rate) {
    const int n = rate * 3, on = rate, off = 2 * rate, edge = rate / 100;
    std::vector<double> x(n, 0.0);
    for (int f = on; f < off; ++f) {
        double ramp = 1.0;
        if (f - on < edge) ramp = 0.5 - 0.5 * std::cos(kPi * (f - on) / edge);
        else if (off - f < edge) ramp = 0.5 - 0.5 * std::cos(kPi * (off - f) / edge);
        x[f] = 0.3 * ramp * std::sin(2 * kPi * 440.0 * f / rate);
    }
    int latency = 0;
    const std::vector<double> y = run(rate, x, latency);
    if (y.empty()) return;
    check(static_cast<int>(y.size()) == n + latency, "frames out = frames in + latency");
    const double delayMs = (centroid(y) - centroid(x)) * 1000.0 / rate;
    const double latencyMs = latency * 1000.0 / rate;
    const std::vector<double> aligned(y.begin() + latency, y.end());
    int inFirst, inLast, outFirst, outLast;
    edges(x, rate, inFirst, inLast);
    edges(aligned, rate, outFirst, outLast);
    const double onsetMs = (outFirst - inFirst) * 1000.0 / rate;
    const double spanMs = ((outLast - outFirst) - (inLast - inFirst)) * 1000.0 / rate;
    std::printf("%d Hz burst: delay %.2f ms vs latency %.2f ms; onset %+.2f ms, length %+.2f ms\n",
                rate, delayMs, latencyMs, onsetMs, spanMs);
    check(std::fabs(delayMs - latencyMs) <= kDelayToleranceMs, "burst delayed by the reported latency (1 ms)");
    check(std::fabs(onsetMs) <= kEdgeToleranceMs, "burst onset in place once the latency is removed (5 ms)");
    check(std::fabs(spanMs) <= kEdgeToleranceMs, "burst keeps its length (5 ms)");
}

// NaN or infinite input never poisons the stage: it is read as silence and music carries on.
void checkNonFiniteInput() {
    const int rate = 48000;
    stash_tuning::Tuner *t = stash_tuning::create(kChannels, rate);
    if (t == nullptr) {
        check(false, "stage created");
        return;
    }
    stash_tuning::setFactor(t, static_cast<float>(kFactor));
    stash_tuning::reserve(t, kChunk);
    bool finite = true;
    for (int round = 0; round < 40; ++round) {
        for (int i = 0; i < kChunk * kChannels; ++i) {
            t->in[i] = round == 3 ? std::nanf("") : static_cast<float>(0.3 * std::sin(2 * kPi * 440.0 * (round * kChunk + i / kChannels) / rate));
        }
        stash_tuning::process(t, kChunk);
        for (int i = 0; i < kChunk * kChannels; ++i) finite = finite && stash_tuning::isFiniteBits(t->out[i]);
    }
    check(finite, "output stays finite after a NaN chunk");
    stash_tuning::destroy(t);
}

}  // namespace

int main() {
    for (int rate : {44100, 96000}) {
        checkPitch(rate, {55.0, 82.41, 440.0, 880.0, 1320.0});  // a chord: two bass notes a fourth apart
        checkPitch(rate, {55.0});
        checkPitch(rate, {82.41});
        checkTiming(rate);
    }
    checkNonFiniteInput();
    std::printf(failures == 0 ? "all checks passed\n" : "%d check(s) failed\n", failures);
    return failures == 0 ? 0 : 1;
}
