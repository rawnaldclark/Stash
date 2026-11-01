package com.stash.feature.library

/**
 * The on-screen name of an active Songs source filter ("FLAC ×" above the list, "No FLAC
 * songs…" when it empties it); null for [SourceFilter.ALL], which filters nothing.
 */
internal fun sourceFilterLabel(filter: SourceFilter): String? = when (filter) {
    SourceFilter.ALL -> null
    SourceFilter.YOUTUBE -> "YouTube"
    SourceFilter.SPOTIFY -> "Spotify"
    SourceFilter.FLAC -> "FLAC"
    SourceFilter.NON_FLAC -> "Non-FLAC"
    SourceFilter.DOWNLOADED -> "Downloaded"
    // "Online" until 2026-11-01, when the mode itself took that name (Online / Offline).
    SourceFilter.ONLINE -> "Not downloaded"
    SourceFilter.CLOUD -> "Cloud"
}

/**
 * The chip above a Songs list narrowed by [filter] ("Showing FLAC only", tap to clear); null for
 * [SourceFilter.ALL]. "Not downloaded" is a phrase, not a name, so it reads as one there.
 */
internal fun activeFilterChipLabel(filter: SourceFilter): String? = when (filter) {
    SourceFilter.ONLINE -> "Showing songs that aren't downloaded"
    else -> sourceFilterLabel(filter)?.let { "Showing $it only" }
}

/**
 * The empty-state line for a Library tab emptied by the user's own search [query] or source
 * [filter], rather than by an empty library; null when neither is active, so the tab keeps its
 * "Sync your library…" / "Connect a service…" line.
 *
 * Without it, a FLAC filter persisted from days ago (or a search that matched nothing) blamed
 * sync or a missing connection for an empty list the user could fix with one tap.
 *
 * @param noun What the tab lists, plural and lowercase ("songs", "artists").
 */
internal fun noMatchMessage(noun: String, query: String, filter: SourceFilter = SourceFilter.ALL): String? {
    val q = query.trim()
    // "No Not downloaded songs…" doesn't read: this filter is said as a phrase.
    if (filter == SourceFilter.ONLINE) {
        return if (q.isNotEmpty()) "No $noun that aren't downloaded match “$q”"
        else "No $noun in your library that aren't downloaded"
    }
    val label = sourceFilterLabel(filter)
    return when {
        q.isNotEmpty() && label != null -> "No $label $noun match “$q”"
        label != null -> "No $label $noun in your library"
        q.isNotEmpty() -> "No $noun match “$q”"
        else -> null
    }
}

/**
 * What the Songs tab says with no songs at all (LibraryEmptyState in LibraryScreen):
 * the body copy and where its main button leads. The three ways music arrives are always named
 * (sync, Search, import from the phone); the main button is Sync once a service is connected and
 * connecting one before that, because Sync now with nothing connected has nothing to fetch.
 *
 * @property connectFirst True: the main button opens Settings › Accounts. False: the Sync tab.
 */
internal data class EmptyLibraryPrompt(
    val body: String,
    val primaryLabel: String,
    val connectFirst: Boolean,
)

internal fun emptyLibraryPrompt(anyServiceConnected: Boolean): EmptyLibraryPrompt =
    if (anyServiceConnected) {
        EmptyLibraryPrompt(
            // Not "songs you download": in Stream only this tab lists every synced song.
            body = "Songs from the playlists you sync show up here. You can also find songs " +
                "in Search, or import audio files from this phone.",
            primaryLabel = "Sync your playlists",
            connectFirst = false,
        )
    } else {
        EmptyLibraryPrompt(
            body = "Connect Spotify or YouTube Music and your playlists' songs show up here. " +
                "You can also find songs in Search, or import audio files from this phone.",
            primaryLabel = "Connect a music service",
            connectFirst = true,
        )
    }
