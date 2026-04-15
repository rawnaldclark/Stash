package com.stash.core.model

/**
 * Controls how algorithmic playlists (Daily Mixes, Discover Weekly, etc.)
 * are updated during sync.
 *
 * - [REFRESH] — replace old tracks with the current set each sync.
 *   Keeps playlists clean and current. Old tracks stay in the library
 *   but are removed from the playlist view.
 *
 * - [ACCUMULATE] — only add new tracks, never remove old ones.
 *   Playlists grow over time as a discovery archive. Useful for
 *   building a large collection from rotating mixes.
 */
enum class SyncMode {
    REFRESH,
    ACCUMULATE,
}
