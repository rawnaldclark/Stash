package com.stash.core.data.discovery

/** A Home discovery genre chip. [genreId] null = "All" (no Qobuz genre filter). */
data class Genre(val label: String, val genreId: Int?)

/**
 * Curated static genre set for the Home discovery chips. IDs are real Qobuz
 * top-level `genre_id`s confirmed against the live `genre/list` endpoint
 * (Qobuz returns localized names; the labels here are ours). "All" carries a
 * null id so the repository omits the `genre_id` param entirely.
 */
object GenreCatalog {
    val GENRES = listOf(
        Genre("All", null),
        Genre("Pop/Rock", 112),
        Genre("Hip-Hop", 133),
        Genre("Electronic", 64),
        Genre("Jazz", 80),
        Genre("Classical", 10),
        Genre("Soul/R&B", 127),
        Genre("Metal", 116),
    )

    /** genreId for a chip label; null for "All" or an unknown label. */
    fun idFor(label: String): Int? = GENRES.firstOrNull { it.label == label }?.genreId
}
