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
    SourceFilter.ONLINE -> "Online"
    SourceFilter.CLOUD -> "Cloud"
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
    val label = sourceFilterLabel(filter)
    return when {
        q.isNotEmpty() && label != null -> "No $label $noun match “$q”"
        label != null -> "No $label $noun in your library"
        q.isNotEmpty() -> "No $noun match “$q”"
        else -> null
    }
}

/**
 * What the Songs tab says with nothing downloaded at all (LibraryEmptyState in LibraryScreen):
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
            body = "Songs you download show up here. Sync your playlists, find songs in Search, " +
                "or import audio files from this phone.",
            primaryLabel = "Sync your playlists",
            connectFirst = false,
        )
    } else {
        EmptyLibraryPrompt(
            body = "Songs you download show up here. Connect Spotify or YouTube Music to bring " +
                "your playlists over, find songs in Search, or import audio files from this phone.",
            primaryLabel = "Connect a music service",
            connectFirst = true,
        )
    }
