package com.stash.core.data.prefs

/**
 * Folder structure used for downloaded tracks inside whichever storage
 * destination is configured (internal `filesDir/music` or the user-picked
 * SAF tree).
 *
 * Issue #198 ("Feature: Define folder structure for library", incl. storing
 * songs per playlist) and issue #104 ("one folder" — every track directly
 * inside the destination root).
 *
 * The layout only governs where files are WRITTEN and how existing files
 * are LOOKED UP (reconciliation / adoption / lyrics sidecars). Changing the
 * setting never moves files by itself; the Settings screen offers an
 * explicit "Reorganize library" action for relocating already-downloaded
 * tracks into the chosen structure.
 */
enum class LibraryLayout(
    val key: String,
    val label: String,
    val description: String,
) {
    /**
     * Classic nested layout: `<Artist>/<Album>/<title>.<ext>`, with blank
     * albums filed under `singles/`. This is the historical default.
     */
    ARTIST_ALBUM(
        key = "artist_album",
        label = "Artist / Album",
        description = "Nested folders — one per artist, one per album inside it.",
    ),

    /**
     * Issue #104: every track lands directly in the destination root as
     * `<artist>-<title>.<ext>` so the whole library is one flat folder
     * that's trivial to move or copy elsewhere.
     */
    SINGLE_FOLDER(
        key = "single_folder",
        label = "Single folder",
        description = "All tracks directly in one folder, named artist - title.",
    ),

    /**
     * Issue #198: one folder per playlist (`<Playlist>/<artist>-<title>.<ext>`).
     * A track in several playlists is filed under its first sync-enabled
     * playlist (lowest row id tie-break — the same rule the Failed Downloads
     * viewer uses). Tracks in no playlist fall back to Artist/Album so
     * unfiled music stays grouped instead of landing in a junk folder.
     */
    PLAYLIST(
        key = "playlist",
        label = "Per playlist",
        description = "One folder per playlist; tracks without a playlist use Artist / Album.",
    ),
    ;

    companion object {
        val DEFAULT: LibraryLayout = ARTIST_ALBUM

        fun fromKey(key: String?): LibraryLayout =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
