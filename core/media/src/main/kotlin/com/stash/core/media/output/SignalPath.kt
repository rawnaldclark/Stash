package com.stash.core.media.output

import com.stash.core.data.prefs.HiResOutputMode

/** Where the audio physically goes, as far as the signal path cares. */
enum class OutputDeviceKind { USB, BLUETOOTH, WIRED, SPEAKER, OTHER }

data class OutputDevice(val kind: OutputDeviceKind, val name: String)

/** What the mixer between Stash and the DAC does to the samples. */
enum class MixerPath {
    /** Android's shared mixer at its own rate: resamples anything that doesn't match it. */
    SHARED,

    /** The USB output was reopened at the song's sample rate: mixed, but never resampled. */
    MATCHED,

    /** The bit-perfect mixer: samples reach the DAC unaltered. */
    BIT_PERFECT,
}

/**
 * The live state of the active output, published by the output stage when it
 * starts writing. Platform-free so the verdict logic below is testable.
 *
 * @property decodedFormat what the decoder handed the output stage.
 * @property trackFormat what Stash writes to the AudioTrack.
 * @property mixerSampleRate the rate the mixer runs at, when known: the song's
 *   own rate for MATCHED / BIT_PERFECT, else the output's native rate.
 * @property mixerFormat the format Stash set the USB mixer to (MATCHED /
 *   BIT_PERFECT): what the DAC finally gets. Null on the shared mixer.
 * @property bitPerfectMiss bit-perfect was asked for and the output fell back
 *   to rate matching, and why.
 * @property sharedReason why the mixer was left shared, for the explanation line.
 */
data class OutputPathState(
    val sampleRate: Int,
    val channelCount: Int,
    val decodedFormat: SampleFormat,
    val trackFormat: SampleFormat,
    val mixer: MixerPath,
    val mixerSampleRate: Int?,
    val mixerFormat: SampleFormat?,
    val device: OutputDevice?,
    val mode: HiResOutputMode,
    val bitPerfectMiss: BitPerfectMiss? = null,
    val sharedReason: MixerDecision.Reason? = null,
)

/** What the source is, from the decoder's input format. */
data class SourceFormat(
    val codec: String,
    val lossless: Boolean,
    val sampleRate: Int,
    val bitsPerSample: Int?,
    val channelCount: Int,
)

/** One line of the signal-path sheet. [ok] false marks the stage that costs fidelity. */
data class SignalStage(val title: String, val detail: String, val ok: Boolean)

enum class SignalQuality { BIT_PERFECT, LOSSLESS, ENHANCED, RESAMPLED, LOSSY }

data class SignalVerdict(
    val quality: SignalQuality,
    val headline: String,
    val stages: List<SignalStage>,
    /** The output device's name ("FiiO KA3", "Phone speaker"), when known. */
    val deviceName: String? = null,
)

/**
 * The same verdict with the streaming service named at the head of the Source
 * line: "Qobuz · FLAC · 24-bit · 96 kHz". The output stage reads only the
 * decoder's format and never knows where the bytes came from, so Now Playing
 * (which holds the track's stream origin) adds it here. Unchanged when
 * [service] is null (a downloaded or local file) or there is no Source line.
 */
fun SignalVerdict.withService(service: String?): SignalVerdict {
    if (service.isNullOrBlank()) return this
    if (stages.none { it.title == SignalPathExplainer.SOURCE_TITLE }) return this
    return copy(
        stages = stages.map {
            if (it.title == SignalPathExplainer.SOURCE_TITLE) it.copy(detail = "$service · ${it.detail}") else it
        },
    )
}

/**
 * Explains, honestly, what happens to the music between the file and the DAC:
 * the "signal path" the hi-fi players show. Pure, so the wording is tested.
 */
object SignalPathExplainer {

    /** Title of the first stage: the file or stream the decoder reads. */
    const val SOURCE_TITLE = "Source"

    fun explain(source: SourceFormat?, output: OutputPathState?, dspActive: Boolean): SignalVerdict? {
        if (output == null) return null
        val stages = ArrayList<SignalStage>(5)
        val srcRate = source?.sampleRate?.takeIf { it > 0 } ?: output.sampleRate

        // 1. Source
        if (source != null) {
            val bits = source.bitsPerSample?.let { "$it-bit · " } ?: ""
            stages += SignalStage(
                title = SOURCE_TITLE,
                detail = "${source.codec} · $bits${khz(srcRate)}",
                ok = source.lossless,
            )
        }

        // 2. Decoder → output stage
        val dspInPath = dspActive && output.mixer != MixerPath.BIT_PERFECT
        stages += SignalStage(
            title = "Stash",
            detail = when {
                output.mixer == MixerPath.BIT_PERFECT ->
                    "Untouched · EQ and normalization bypassed for bit-perfect"
                dspInPath -> "EQ / normalization in ${SampleFormat.FLOAT.label} (no truncation)"
                else -> "Untouched · decoded to ${output.decodedFormat.label}"
            },
            ok = !dspInPath,
        )

        // 3. Output (AudioTrack)
        stages += SignalStage(
            title = "Output",
            detail = "${khz(output.sampleRate)} · ${output.trackFormat.label}",
            ok = true,
        )

        // 4. Android mixer
        val resampled = output.mixer == MixerPath.SHARED &&
            output.mixerSampleRate != null && output.mixerSampleRate != srcRate
        // The format Stash set the USB mixer to: the last step before the DAC.
        // Narrower than the source (a 16-bit-only DAC, a 24-bit file) and the
        // extra bits end there, after the "Output" line's float.
        val mixerBits = output.mixerFormat?.takeIf { output.mixer != MixerPath.SHARED }?.bits
        val sourceBits = source?.bitsPerSample
        val narrowed = mixerBits != null && sourceBits != null && mixerBits < sourceBits
        val matchedNarrowed = output.mixer == MixerPath.MATCHED && narrowed
        stages += SignalStage(
            title = "Android",
            detail = when (output.mixer) {
                MixerPath.BIT_PERFECT -> "Bit-perfect · ${output.mixerFormat?.label ?: ""}".trimEnd(' ', '·')
                MixerPath.MATCHED -> matchedDetail(output, srcRate, matchedNarrowed)
                MixerPath.SHARED -> when {
                    resampled -> "Resampled to ${khz(output.mixerSampleRate!!)}" + sharedHint(output)
                    output.mixerSampleRate != null -> "Shared mixer at ${khz(output.mixerSampleRate)}, no resampling"
                    else -> "Shared mixer"
                }
            },
            ok = !resampled && !matchedNarrowed,
        )

        // 5. Device
        output.device?.let { d ->
            stages += SignalStage(
                title = deviceTitle(d.kind),
                detail = d.name + if (d.kind == OutputDeviceKind.BLUETOOTH) " · re-encoded by the Bluetooth codec" else "",
                ok = d.kind != OutputDeviceKind.BLUETOOTH,
            )
        }

        val bitDepthKept = source?.bitsPerSample == null ||
            output.trackFormat == SampleFormat.FLOAT ||
            output.trackFormat.bits >= source.bitsPerSample
        val quality = when {
            source != null && !source.lossless -> SignalQuality.LOSSY
            resampled -> SignalQuality.RESAMPLED
            output.mixer == MixerPath.BIT_PERFECT && bitDepthKept &&
                output.device?.kind == OutputDeviceKind.USB -> SignalQuality.BIT_PERFECT
            dspInPath -> SignalQuality.ENHANCED
            else -> SignalQuality.LOSSLESS
        }
        // Counted from what reaches the DAC: a 24-bit file on a 16-bit DAC isn't hi-res there.
        val deliveredBits = if (narrowed) mixerBits else sourceBits
        val hiRes = (deliveredBits ?: 16) > 16 || srcRate > 48_000
        val headline = when (quality) {
            SignalQuality.BIT_PERFECT -> "Bit-perfect"
            SignalQuality.LOSSLESS -> if (hiRes) "Hi-Res, not resampled" else "Lossless, not resampled"
            SignalQuality.ENHANCED -> "Lossless + your EQ"
            SignalQuality.RESAMPLED -> "Resampled to ${khz(output.mixerSampleRate ?: 0)} by Android"
            SignalQuality.LOSSY -> "Lossy source"
        }
        return SignalVerdict(quality, headline, stages, output.device?.name)
    }

    /**
     * "Matched to 96 kHz / 24-bit, not resampled", or, when the DAC's format
     * is narrower than the song, "Matched to 48 kHz / 16-bit (your DAC's
     * maximum)". A bit-perfect request served this way says why.
     */
    private fun matchedDetail(o: OutputPathState, rate: Int, narrowed: Boolean): String = buildString {
        append("Matched to ").append(khz(rate))
        o.mixerFormat?.let { append(" / ").append(it.label) }
        if (!narrowed) append(", not resampled")
        val notes = listOfNotNull(
            "your DAC's maximum".takeIf { narrowed },
            o.bitPerfectMiss?.let { missNote(it) },
        )
        if (notes.isNotEmpty()) append(" (").append(notes.joinToString("; ")).append(')')
    }

    private fun missNote(miss: BitPerfectMiss): String = when (miss) {
        BitPerfectMiss.NOT_OFFERED -> "bit-perfect not offered at this rate"
        BitPerfectMiss.SPEED -> "bit-perfect needs 1x speed"
        BitPerfectMiss.LISTEN_TOGETHER -> "bit-perfect is off during Listen Together"
    }

    private fun sharedHint(o: OutputPathState): String = when (o.sharedReason) {
        MixerDecision.Reason.RATE_UNSUPPORTED_BY_DAC -> " (your DAC doesn't offer ${khz(o.sampleRate)})"
        MixerDecision.Reason.MODE_STANDARD ->
            if (o.device?.kind == OutputDeviceKind.USB) " · turn on Hi-Res USB output to avoid this" else ""
        MixerDecision.Reason.UNSUPPORTED_ANDROID ->
            if (o.device?.kind == OutputDeviceKind.USB) " (needs Android 14 to avoid)" else ""
        else -> ""
    }

    private fun deviceTitle(kind: OutputDeviceKind) = when (kind) {
        OutputDeviceKind.USB -> "USB DAC"
        OutputDeviceKind.BLUETOOTH -> "Bluetooth"
        OutputDeviceKind.WIRED -> "Headphones"
        OutputDeviceKind.SPEAKER -> "Speaker"
        OutputDeviceKind.OTHER -> "Device"
    }

    /** 44100 → "44.1 kHz", 96000 → "96 kHz". */
    fun khz(hz: Int): String {
        val k = hz / 1000.0
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else "${"%.1f".format(java.util.Locale.ROOT, k)} kHz"
    }
}
