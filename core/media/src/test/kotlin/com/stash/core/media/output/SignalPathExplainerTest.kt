package com.stash.core.media.output

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HiResOutputMode
import org.junit.Test

class SignalPathExplainerTest {

    private val flac2496 = SourceFormat("FLAC", lossless = true, sampleRate = 96_000, bitsPerSample = 24, channelCount = 2)
    private val flac1644 = SourceFormat("FLAC", lossless = true, sampleRate = 44_100, bitsPerSample = 16, channelCount = 2)
    private val usb = OutputDevice(OutputDeviceKind.USB, "FiiO KA3")

    private fun out(
        rate: Int = 96_000,
        mixer: MixerPath = MixerPath.SHARED,
        mixerRate: Int? = 48_000,
        track: SampleFormat = SampleFormat.FLOAT,
        device: OutputDevice? = usb,
        mode: HiResOutputMode = HiResOutputMode.STANDARD,
        reason: MixerDecision.Reason? = MixerDecision.Reason.MODE_STANDARD,
        mixerFormat: SampleFormat? = null,
        miss: BitPerfectMiss? = null,
    ) = OutputPathState(
        sampleRate = rate, channelCount = 2, decodedFormat = SampleFormat.FLOAT, trackFormat = track,
        mixer = mixer, mixerSampleRate = mixerRate, mixerFormat = mixerFormat, device = device, mode = mode,
        bitPerfectMiss = miss, sharedReason = reason,
    )

    /** Match mode on a USB DAC at the source's own rate. */
    private fun matched(rate: Int, format: SampleFormat?, miss: BitPerfectMiss? = null) = out(
        rate = rate, mixer = MixerPath.MATCHED, mixerRate = rate, mode = HiResOutputMode.MATCH_RATE, reason = null,
        mixerFormat = format, miss = miss,
    )

    @Test fun `no output yet means nothing to explain`() {
        assertThat(SignalPathExplainer.explain(flac2496, null, false)).isNull()
    }

    @Test fun `shared mixer at a different rate is called out as resampling, with the fix`() {
        val v = SignalPathExplainer.explain(flac2496, out(), dspActive = false)!!
        assertThat(v.quality).isEqualTo(SignalQuality.RESAMPLED)
        assertThat(v.headline).isEqualTo("Resampled to 48 kHz by Android")
        val android = v.stages.first { it.title == "Android" }
        assertThat(android.ok).isFalse()
        // The sheet is the only place resampling is told (the quality line carries no badge for it).
        assertThat(android.detail).startsWith("Resampled to 48 kHz")
        assertThat(android.detail).contains("turn on Hi-Res USB output")
    }

    @Test fun `matched USB mixer with a hi-res source is hi-res, not resampled`() {
        val v = SignalPathExplainer.explain(
            flac2496, out(mixer = MixerPath.MATCHED, mixerRate = 96_000, mode = HiResOutputMode.MATCH_RATE, reason = null), false,
        )!!
        assertThat(v.quality).isEqualTo(SignalQuality.LOSSLESS)
        assertThat(v.headline).isEqualTo("Hi-Res, not resampled")
        assertThat(v.stages.all { it.ok }).isTrue()
    }

    @Test fun `bit-perfect to a USB DAC is bit-perfect, and says the EQ is bypassed`() {
        val v = SignalPathExplainer.explain(
            flac2496,
            out(mixer = MixerPath.BIT_PERFECT, mixerRate = 96_000, track = SampleFormat.INT24, mode = HiResOutputMode.BIT_PERFECT, reason = null),
            dspActive = true, // EQ on, but bit-perfect bypasses it
        )!!
        assertThat(v.quality).isEqualTo(SignalQuality.BIT_PERFECT)
        assertThat(v.stages.first { it.title == "Stash" }.detail).contains("bypassed")
    }

    @Test fun `bit-perfect into a 16-bit-only DAC with a 24-bit source is not claimed bit-perfect`() {
        val v = SignalPathExplainer.explain(
            flac2496,
            out(mixer = MixerPath.BIT_PERFECT, mixerRate = 96_000, track = SampleFormat.INT16, mode = HiResOutputMode.BIT_PERFECT, reason = null),
            false,
        )!!
        assertThat(v.quality).isNotEqualTo(SignalQuality.BIT_PERFECT)
    }

    @Test fun `CD-quality on the speaker at 44_1 kHz native is lossless`() {
        val v = SignalPathExplainer.explain(
            flac1644,
            out(rate = 44_100, mixerRate = 44_100, device = OutputDevice(OutputDeviceKind.SPEAKER, "Phone speaker")),
            false,
        )!!
        assertThat(v.quality).isEqualTo(SignalQuality.LOSSLESS)
        assertThat(v.headline).isEqualTo("Lossless, not resampled")
    }

    @Test fun `active EQ is shown as processing, not hidden`() {
        val v = SignalPathExplainer.explain(flac1644, out(rate = 44_100, mixerRate = 44_100), dspActive = true)!!
        assertThat(v.quality).isEqualTo(SignalQuality.ENHANCED)
        assertThat(v.stages.first { it.title == "Stash" }.ok).isFalse()
    }

    @Test fun `lossy source wins over everything`() {
        val opus = SourceFormat("Opus", lossless = false, sampleRate = 48_000, bitsPerSample = null, channelCount = 2)
        val v = SignalPathExplainer.explain(opus, out(rate = 48_000), false)!!
        assertThat(v.quality).isEqualTo(SignalQuality.LOSSY)
    }

    @Test fun `bluetooth is flagged as re-encoded`() {
        val bt = OutputDevice(OutputDeviceKind.BLUETOOTH, "WH-1000XM5")
        val v = SignalPathExplainer.explain(flac1644, out(rate = 44_100, mixerRate = 44_100, device = bt), false)!!
        val dev = v.stages.last()
        assertThat(dev.ok).isFalse()
        assertThat(dev.detail).contains("re-encoded")
    }

    @Test fun `a 16-bit-only DAC shows the last step down to 16-bit, not just the float output`() {
        // The device case: the mixer ran PCM_16 at 48 kHz while the sheet said "32-bit float".
        val flac2448 = SourceFormat("FLAC", lossless = true, sampleRate = 48_000, bitsPerSample = 24, channelCount = 2)
        val v = SignalPathExplainer.explain(flac2448, matched(48_000, SampleFormat.INT16), false)!!
        val android = v.stages.first { it.title == "Android" }
        assertThat(android.detail).isEqualTo("Matched to 48 kHz / 16-bit (your DAC's maximum)")
        assertThat(android.ok).isFalse()
        assertThat(v.stages.first { it.title == "Output" }.detail).isEqualTo("48 kHz · 32-bit float")
        // 16 bits reach the DAC: not claimed as hi-res.
        assertThat(v.headline).isEqualTo("Lossless, not resampled")
    }

    @Test fun `a matched mixer at least as wide as the source names its format and costs nothing`() {
        val cd = SignalPathExplainer.explain(flac1644, matched(44_100, SampleFormat.INT16), false)!!
        assertThat(cd.stages.first { it.title == "Android" }.detail).isEqualTo("Matched to 44.1 kHz / 16-bit, not resampled")
        assertThat(cd.stages.all { it.ok }).isTrue()

        val hiRes = SignalPathExplainer.explain(flac2496, matched(96_000, SampleFormat.INT32), false)!!
        assertThat(hiRes.stages.first { it.title == "Android" }.detail).isEqualTo("Matched to 96 kHz / 32-bit, not resampled")
        assertThat(hiRes.headline).isEqualTo("Hi-Res, not resampled")
    }

    @Test fun `bit-perfect served as rate matching says why`() {
        fun detail(miss: BitPerfectMiss) = SignalPathExplainer.explain(
            flac2496, matched(96_000, SampleFormat.INT24, miss).copy(mode = HiResOutputMode.BIT_PERFECT), false,
        )!!.stages.first { it.title == "Android" }.detail
        assertThat(detail(BitPerfectMiss.LISTEN_TOGETHER))
            .isEqualTo("Matched to 96 kHz / 24-bit, not resampled (bit-perfect is off during Listen Together)")
        assertThat(detail(BitPerfectMiss.SPEED)).endsWith("(bit-perfect needs 1x speed)")
        assertThat(detail(BitPerfectMiss.NOT_OFFERED)).endsWith("(bit-perfect not offered at this rate)")
    }

    @Test fun `a narrowed mixer and a bit-perfect miss share one note`() {
        val flac2448 = SourceFormat("FLAC", lossless = true, sampleRate = 48_000, bitsPerSample = 24, channelCount = 2)
        val v = SignalPathExplainer.explain(flac2448, matched(48_000, SampleFormat.INT16, BitPerfectMiss.NOT_OFFERED), false)!!
        assertThat(v.stages.first { it.title == "Android" }.detail)
            .isEqualTo("Matched to 48 kHz / 16-bit (your DAC's maximum; bit-perfect not offered at this rate)")
    }

    @Test fun `khz formatting`() {
        assertThat(SignalPathExplainer.khz(44_100)).isEqualTo("44.1 kHz")
        assertThat(SignalPathExplainer.khz(96_000)).isEqualTo("96 kHz")
        assertThat(SignalPathExplainer.khz(352_800)).isEqualTo("352.8 kHz")
    }

    @Test fun `the streaming service heads the Source line`() {
        val v = SignalPathExplainer.explain(flac2496, out(), dspActive = false)!!.withService("Qobuz")
        assertThat(v.stages.first { it.title == "Source" }.detail).isEqualTo("Qobuz · FLAC · 24-bit · 96 kHz")
        // Only the Source line changes.
        assertThat(v.stages.drop(1)).isEqualTo(SignalPathExplainer.explain(flac2496, out(), false)!!.stages.drop(1))
    }

    @Test fun `a downloaded file names no service`() {
        val v = SignalPathExplainer.explain(flac1644, out(), dspActive = false)!!
        assertThat(v.withService(null)).isEqualTo(v)
        assertThat(v.stages.first { it.title == "Source" }.detail).isEqualTo("FLAC · 16-bit · 44.1 kHz")
    }

    @Test fun `no source line means no service to add`() {
        val v = SignalPathExplainer.explain(null, out(), dspActive = false)!!
        assertThat(v.withService("Tidal")).isEqualTo(v)
    }
}
