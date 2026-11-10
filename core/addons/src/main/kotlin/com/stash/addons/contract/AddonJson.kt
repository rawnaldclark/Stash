// Vendored from stash-addon-spec types/kotlin at 5238551 (Stash Add-ons v1, draft 1). Edit the spec, then copy it here again.
package com.stash.addons.contract

import kotlinx.serialization.json.Json

/**
 * Stash Add-ons v1 (SPEC.md), as kotlinx.serialization data classes for the app.
 *
 * These classes carry shapes only. A host still checks every answer by the schema's rules (text caps, URL rule,
 * tier consistency, resolve strictness) before using it, and drops a message that breaks one: reject, don't
 * repair. Decode with [AddonJson], never with `coerceInputValues` or `isLenient`, which repair input.
 */
public val AddonJson: Json = Json {
    /** Hosts ignore fields they do not know (SPEC.md 1). */
    ignoreUnknownKeys = true
    /** Optional fields are left out, not sent as null. */
    explicitNulls = false
    encodeDefaults = false
}

/** The contract major this package implements. */
public const val CONTRACT_MAJOR: Int = 1

/** Query parameters a setting key must not use (SPEC.md 2.2). */
public val RESERVED_PARAMS: Set<String> = setOf(
    "q", "limit", "offset", "skip", "genre", "id", "title", "artist", "album",
    "durationMs", "isrc", "explicit", "tier", "minTier", "manifest",
)

/** Caps (SPEC.md 1.1). Text caps count code points. */
public object Limits {
    public const val ID: Int = 200
    public const val TITLE: Int = 300
    public const val SHORT_TEXT: Int = 60
    public const val VERSION: Int = 40
    public const val DESCRIPTION: Int = 300
    public const val MESSAGE: Int = 300
    public const val URL: Int = 2048
    public const val STREAM_URL: Int = 16_384
    public const val TRACKS: Int = 200
    public const val SEARCH_ARTISTS: Int = 50
    public const val SEARCH_ALBUMS: Int = 50
    public const val ARTIST_ALBUMS: Int = 200
    public const val ARTWORK: Int = 8
    public const val CATALOGS: Int = 10
    public const val CATALOG_ITEMS: Int = 100
    public const val GENRES: Int = 50
    public const val LYRIC_LINES: Int = 3000
    public const val LYRIC_WORDS: Int = 300
    public const val SETTINGS: Int = 20
    public const val HEADERS: Int = 16
    public const val BODY_BYTES: Long = 2L * 1024 * 1024
}

/** Host timeouts (SPEC.md 3.1), ms. */
public object TimeoutsMs {
    public const val MANIFEST: Long = 10_000
    public const val SEARCH: Long = 10_000
    public const val LYRICS: Long = 10_000
    public const val CATALOG: Long = 10_000
    public const val QUOTA: Long = 10_000
    public const val RESOLVE: Long = 15_000
    public const val STREAM: Long = 15_000
    public const val ALBUM: Long = 15_000
    public const val ARTIST: Long = 15_000
    public const val DOWNLOAD: Long = 20_000
}
