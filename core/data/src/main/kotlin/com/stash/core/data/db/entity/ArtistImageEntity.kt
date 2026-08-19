package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached artist photo for the Library Artists tab.
 *
 * Keyed by the *display* artist name — the primary artist after
 * [com.stash.core.common.primaryArtist] collapses collaboration credits
 * ("Aarne, Toxi$, Big Baby Tape" → "Aarne"), so the backfill resolves and
 * stores one row per card the tab actually renders.
 *
 * - [imageUrl] is the artist's official avatar from the YT Music artists
 *   search, run through `ArtUrlUpgrader` for a high-res URL. Null when the
 *   name could not be resolved (local-only rips, obscure acts) — the UI then
 *   falls back to the album-art proxy / gradient initial.
 * - [attemptedAt] marks that a resolution was TRIED. A null [imageUrl] with a
 *   non-null [attemptedAt] is a permanent "no photo" sentinel, so the worker
 *   never re-polls unresolvable names on every run.
 *
 * The primary key is COLLATE NOCASE so a case variant of an attempted name
 * ("Aarne" vs "aarne") resolves to the same row instead of two API calls.
 *
 * Not part of any foreign-key graph — artist names are free-text tags on
 * `tracks`, and the table is a small pure cache (one row per artist, a few
 * hundred max). Stale rows are harmless: the Library only looks names up, and
 * orphaned entries simply never match.
 */
@Entity(tableName = "artist_images")
data class ArtistImageEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_name", collate = ColumnInfo.NOCASE)
    val artistName: String,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "attempted_at") val attemptedAt: Long = 0L,
)