package com.stash.data.download.lossless.qbdlx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── catalog/search ─────────────────────────────────────────────────────
@Serializable
data class QbdlxSearchResponse(val tracks: QbdlxTrackList = QbdlxTrackList())

@Serializable
data class QbdlxTrackList(val items: List<QbdlxTrack> = emptyList())

@Serializable
data class QbdlxTrack(
    val id: Long = 0,
    val title: String = "",
    val isrc: String? = null,
    val duration: Int = 0,                        // seconds
    val streamable: Boolean = true,
    val performer: QbdlxPerformer? = null,
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int = 0,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Float = 0f,  // kHz
    val album: QbdlxAlbum? = null,
    /** The recording's version, kept out of [title] by Qobuz (e.g. "Retro Mix", "Acoustic"). */
    val version: String? = null,
)

@Serializable data class QbdlxPerformer(val name: String = "")
@Serializable data class QbdlxAlbum(val image: QbdlxImage? = null)
@Serializable data class QbdlxImage(val large: String? = null, val small: String? = null, val thumbnail: String? = null)

// ── track/getFileUrl ───────────────────────────────────────────────────
@Serializable
data class QbdlxFileUrl(
    val url: String? = null,
    @SerialName("format_id") val formatId: Int = 0,
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("sampling_rate") val samplingRate: Float = 0f, // kHz
    val sample: Boolean = false,
    val restrictions: List<QbdlxRestriction> = emptyList(),
)

@Serializable data class QbdlxRestriction(val code: String = "")
