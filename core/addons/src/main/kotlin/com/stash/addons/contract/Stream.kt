// Vendored from stash-addon-spec types/kotlin at 5238551 (Stash Add-ons v1, draft 1). Edit the spec, then copy it here again.
package com.stash.addons.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Quality tiers, lowest first (SPEC.md 5.1). Declaration order is the tier order. */
@Serializable
public enum class Tier(public val wire: String) {
    /** Lossy below 256 kbps: data saver, Tidal LOW, JioSaavn 96/160. */
    @SerialName("low") LOW("low"),
    /** Lossy at 256 kbps or more: Tidal HIGH (the AAC 320 the app uses today), JioSaavn 320. */
    @SerialName("high") HIGH("high"),
    /** Lossless at 16-bit or less and 48 kHz or less: CD, Tidal LOSSLESS. */
    @SerialName("lossless") LOSSLESS("lossless"),
    /** Lossless above that: Tidal HI_RES_LOSSLESS. */
    @SerialName("hires") HIRES("hires");

    public val isLossless: Boolean get() = this >= LOSSLESS

    public companion object {
        public fun fromWire(value: String): Tier? = entries.firstOrNull { it.wire == value }
    }
}

/** A stream's delivery: Eclipse's `manifest` field. */
@Serializable
public enum class Delivery(public val wire: String) {
    /** One progressive file. */
    @SerialName("none") NONE("none"),
    @SerialName("dash") DASH("dash"),
    @SerialName("hls") HLS("hls");

    public companion object {
        public fun fromWire(value: String): Delivery? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
public enum class Codec(public val lossless: Boolean) {
    @SerialName("flac") FLAC(true),
    @SerialName("alac") ALAC(true),
    @SerialName("pcm") PCM(true),
    @SerialName("aac") AAC(false),
    @SerialName("he-aac") HE_AAC(false),
    @SerialName("mp3") MP3(false),
    @SerialName("opus") OPUS(false),
    @SerialName("vorbis") VORBIS(false),
    @SerialName("eac3_joc") EAC3_JOC(false),
}

@Serializable
public enum class Container {
    @SerialName("flac") FLAC,
    @SerialName("mp3") MP3,
    @SerialName("mp4") MP4,
    @SerialName("fmp4") FMP4,
    @SerialName("ogg") OGG,
    @SerialName("webm") WEBM,
    @SerialName("wav") WAV,
}

/**
 * What the host asks of `/stream/{id}` and `/download/{id}` (SPEC.md 5.2): the best tier within
 * [[minTier], [tier]] in one of [manifest]. The app's Tidal requests today:
 *  - lossless only: `StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(NONE, DASH))`
 *  - `?aac=1`: `StreamRequest(Tier.HIRES, Tier.HIGH, listOf(NONE, DASH))`
 *  - `?aac=only`: `StreamRequest(Tier.HIGH, Tier.HIGH, listOf(NONE))`
 */
public data class StreamRequest(
    val tier: Tier = Tier.HIRES,
    val minTier: Tier = Tier.LOW,
    val manifest: List<Delivery> = listOf(Delivery.NONE),
) {
    init {
        require(minTier <= tier) { "minTier above tier" }
        require(manifest.isNotEmpty()) { "no delivery" }
    }

    /** Query parameters, in a stable order. */
    public fun queryParameters(): List<Pair<String, String>> = listOf(
        "tier" to tier.wire,
        "minTier" to minTier.wire,
        "manifest" to manifest.joinToString(",") { it.wire },
    )

    /** True when [info] is an answer this request allows (SPEC.md 5.2). */
    public fun allows(info: StreamInfo): Boolean = info.tier in minTier..tier && info.manifest in manifest
}

/**
 * `GET /stream/{id}` and `GET /download/{id}` (SPEC.md 5.3). Maps 1:1 onto the app's `StreamUrl`:
 * url, expiresAt (seconds here), codec, bitDepth, sampleRate, bitrate, isDash = manifest == DASH,
 * isHls = manifest == HLS.
 */
@Serializable
public data class StreamInfo(
    val url: String,
    val manifest: Delivery,
    val tier: Tier,
    /** The media host sends Access-Control-Allow-Origin. */
    val cors: Boolean,
    val codec: Codec? = null,
    val container: Container? = null,
    val mime: String? = null,
    /** bits per second */
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val durationMs: Long? = null,
    /** Unix seconds. Cache until `expiresAt - 60 s`, else 30 minutes. */
    val expiresAt: Long? = null,
    /** Eclipse's display label ("320kbps", "lossless"). */
    val quality: String? = null,
    /** Eclipse's flag; v1 defines no keys, so `true` is not playable. */
    val encrypted: Boolean? = null,
    /** Send these on every request for this stream to [url]'s origin only (SPEC.md 5.4). */
    val headers: Map<String, String>? = null,
    /** A URL answering a StreamInfo for a file to keep. */
    val download: String? = null,
    val quota: Quota? = null,
)

/** `GET /quota` (SPEC.md 4.8). [limit] null: no limit. */
@Serializable
public data class Quota(val used: Int, val limit: Int?, val resetsAt: Long, val label: String? = null)
