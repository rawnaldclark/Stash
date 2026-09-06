package com.stash.data.download.files

import com.stash.core.data.prefs.LibraryLayout

/**
 * Single source of truth for where a downloaded track lives inside the
 * configured library destination (internal `filesDir/music` root or the
 * user-picked SAF tree root).
 *
 * Issue #198 / #104 introduced selectable folder structures. Before this
 * object the `<artist>/<album>/<title>` layout was re-derived by hand in
 * four places (internal commit, SAF commit, library move, lyrics sidecar)
 * plus a fifth assumption baked into the reconciliation index — each one
 * an opportunity for drift. Every writer and reader now goes through
 * [resolve] (writes) or [lookupCandidates] (reads), so the on-disk shape
 * cannot disagree with itself.
 *
 * Filename rules:
 *  - [LibraryLayout.ARTIST_ALBUM] keeps the historical `<title>.<ext>`
 *    (the directory pair already disambiguates same-titled tracks, and
 *    changing it would rename every existing file).
 *  - [LibraryLayout.SINGLE_FOLDER] and [LibraryLayout.PLAYLIST] prefix the
 *    artist slug (`<artist>-<title>.<ext>`) because the directory no longer
 *    separates artists — two different songs both titled "Intro" would
 *    otherwise silently overwrite each other in one shared folder.
 *
 * Fallback rules (deliberate, tested):
 *  - Blank album → `singles/` directory (unchanged historical behavior).
 *  - PLAYLIST with no resolvable playlist name (track in no playlist, or
 *    the name slugifies to nothing) → falls back to ARTIST_ALBUM segments,
 *    so unfiled tracks stay grouped under their artist instead of piling
 *    into a junk folder.
 */
object LibraryLayoutResolver {

    /**
     * A track's location relative to the library root: [segments] are the
     * directory names in order (empty = directly in the root) and
     * [baseName] is the filename without extension.
     */
    data class ResolvedLocation(
        val segments: List<String>,
        val baseName: String,
    ) {
        /** Slash-joined directory key ("artist/album"); "" = library root. */
        val dirKey: String get() = segments.joinToString("/")
    }

    /**
     * Computes where a track should be written under [layout].
     *
     * @param artist       Track artist as stored on the row (slugged here).
     * @param album        Album name, or null/blank when unknown.
     * @param title        Track title (slugged into the base name).
     * @param playlistName Resolved owning playlist for [LibraryLayout.PLAYLIST];
     *   ignored by the other layouts. Null when unknown/unresolvable.
     */
    fun resolve(
        layout: LibraryLayout,
        artist: String,
        album: String?,
        title: String,
        playlistName: String?,
    ): ResolvedLocation {
        val artistSlug = FileOrganizerSlugs.slugify(artist)
        val titleSlug = FileOrganizerSlugs.slugify(title)

        if (layout == LibraryLayout.SINGLE_FOLDER) {
            return ResolvedLocation(emptyList(), "$artistSlug-$titleSlug")
        }

        if (layout == LibraryLayout.PLAYLIST && !playlistName.isNullOrBlank()) {
            val playlistSlug = FileOrganizerSlugs.slugify(playlistName)
            if (playlistSlug.isNotBlank()) {
                return ResolvedLocation(listOf(playlistSlug), "$artistSlug-$titleSlug")
            }
            // Playlist name slugified to nothing (emoji-only etc.) → fall through.
        }

        // ARTIST_ALBUM — and the PLAYLIST fallback when no playlist resolved.
        val albumSlug = if (!album.isNullOrBlank()) FileOrganizerSlugs.slugify(album) else "singles"
        return ResolvedLocation(listOf(artistSlug, albumSlug), titleSlug)
    }

    /**
     * Candidate locations to probe when looking up whether a file already
     * exists on disk (library reconciliation, adoption). Ordered most
     * specific first:
     *
     *  1. The current-layout location.
     *  2. The legacy `<artist>/<album>` location — tracks downloaded before
     *     a layout switch stay there until the user runs Reorganize, and a
     *     lookup that only tried the new location would report them missing
     *     and reset/redownload them (the #429 failure shape).
     *  3. For nested layouts, the flat `<artist>-<title>` name at the root —
     *     covers files downloaded while SINGLE_FOLDER was active and not yet
     *     reorganized.
     *
     * Each entry is `(dirKey, candidate full filenames)`. With
     * [knownFormats] empty only the bare base name is probed; otherwise one
     * candidate per known audio extension is generated (the extension-probe
     * behavior of the old slug-walk).
     */
    fun lookupCandidates(
        layout: LibraryLayout,
        artist: String,
        album: String?,
        title: String,
        playlistName: String?,
        knownFormats: List<String>,
    ): List<CandidateLocation> {
        val artistSlug = FileOrganizerSlugs.slugify(artist)
        val titleSlug = FileOrganizerSlugs.slugify(title)
        val flatBase = "$artistSlug-$titleSlug"

        fun namesFor(base: String): List<String> =
            if (knownFormats.isEmpty()) listOf(base) else knownFormats.map { "$base.$it" }

        val primary = resolve(layout, artist, album, title, playlistName)
        val candidates = mutableListOf(
            CandidateLocation(primary.dirKey, namesFor(primary.baseName)),
        )

        val albumSlug = if (!album.isNullOrBlank()) FileOrganizerSlugs.slugify(album) else "singles"
        val legacyKey = "$artistSlug/$albumSlug"
        if (candidates.none { it.dirKey == legacyKey }) {
            candidates += CandidateLocation(legacyKey, namesFor(titleSlug))
        }
        if (primary.segments.isNotEmpty()) {
            candidates += CandidateLocation("", namesFor(flatBase))
        }
        return candidates
    }

    /** One probe location from [lookupCandidates]. */
    data class CandidateLocation(
        val dirKey: String,
        val candidateFileNames: List<String>,
    )
}
