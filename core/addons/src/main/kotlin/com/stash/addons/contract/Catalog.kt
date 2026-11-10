// Vendored from stash-addon-spec types/kotlin at 341004d (Stash Add-ons v1, draft 1). Edit the spec, then copy it here again.
package com.stash.addons.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
public data class Artwork(val url: String, val width: Int? = null, val height: Int? = null)

/** SPEC.md 4.1. */
@Serializable
public data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The id this add-on's `album` route takes. Drop it when the manifest lacks `album`. */
    val albumId: String? = null,
    /** The id this add-on's `artist` route takes. Drop it when the manifest lacks `artist`. */
    val artistId: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val explicit: Boolean? = null,
    val artwork: List<Artwork>? = null,
    /** Best tier `stream` can give for it, when known. */
    val maxTier: Tier? = null,
)

@Serializable
public data class ArtistSummary(val id: String, val name: String, val artwork: List<Artwork>? = null)

@Serializable
public data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val artwork: List<Artwork>? = null,
)

/** The one result the add-on thinks the query is about. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
public sealed interface SearchTop {
    @Serializable @SerialName("track")
    public data class TrackTop(val item: Track) : SearchTop

    @Serializable @SerialName("artist")
    public data class ArtistTop(val item: ArtistSummary) : SearchTop

    @Serializable @SerialName("album")
    public data class AlbumTop(val item: AlbumSummary) : SearchTop
}

/** `GET /search` (SPEC.md 4.2). */
@Serializable
public data class SearchResponse(
    val tracks: List<Track>,
    val artists: List<ArtistSummary>? = null,
    val albums: List<AlbumSummary>? = null,
    val top: SearchTop? = null,
)

/** What a host knows about a song: the `/resolve` (and `/lyrics`) query. */
@Serializable
public data class Descriptor(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val explicit: Boolean? = null,
)

/** `GET /resolve` (SPEC.md 4.3): the exact recording or null. */
@Serializable
public data class ResolveResponse(val item: Track?)

/** `GET /album/{id}`. */
@Serializable
public data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val artwork: List<Artwork>? = null,
    val tracks: List<Track>,
)

@Serializable
public data class ArtistAlbum(
    val id: String,
    val title: String,
    val year: Int? = null,
    val artwork: List<Artwork>? = null,
    val trackCount: Int? = null,
)

/** `GET /artist/{id}`. */
@Serializable
public data class Artist(
    val id: String,
    val name: String,
    val artwork: List<Artwork>? = null,
    val topTracks: List<Track>,
    val albums: List<ArtistAlbum>,
    val listenersText: String? = null,
    val listeners: Long? = null,
)

/** `GET /catalog/{id}`. [next] is the offset of the next page, or null at the end. */
@Serializable
public data class CatalogPage(val id: String, val title: String, val items: List<AlbumSummary>, val next: Int?)

@Serializable
public data class CatalogGenre(val id: String, val name: String)

/** `GET /catalog-genres`. */
@Serializable
public data class CatalogGenres(val genres: List<CatalogGenre>)

@Serializable
public data class LyricWord(val startMs: Long, val endMs: Long? = null, val text: String)

@Serializable
public data class LyricLine(val startMs: Long, val endMs: Long? = null, val text: String, val words: List<LyricWord>? = null)

/** `GET /lyrics` (SPEC.md 4.6). At least one of [synced] or [plain]. */
@Serializable
public data class Lyrics(val synced: List<LyricLine>? = null, val plain: String? = null)
