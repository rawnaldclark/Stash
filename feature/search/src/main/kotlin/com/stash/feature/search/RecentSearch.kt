package com.stash.feature.search

/**
 * One recent-search entry. Three flavours:
 *  - [Type.QUERY]  — a committed text query (keyboard Search key). No art.
 *  - [Type.ARTIST] — the user opened an artist profile from results; carries
 *    the avatar and the artist browse id so a tap navigates straight back.
 *  - [Type.TRACK]  — the user engaged a track row (tap/preview/download/
 *    queue/playlist); carries the track thumbnail; [subtitle] is the artist,
 *    and [videoId] + [durationSeconds] so a tap plays it straight away
 *    (rows saved before 2026-10-22 lack them and re-run the search instead).
 *
 * Kept free of Android (unlike [RecentSearchesStore]) so the dedupe rules
 * below are plain JVM-testable.
 */
data class RecentSearch(
    val type: Type,
    val text: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val artistId: String? = null,
    val videoId: String? = null,
    val durationSeconds: Double? = null,
) {
    enum class Type { QUERY, ARTIST, TRACK }

    /** The query a tap should re-run (ARTIST entries navigate instead). */
    val searchText: String
        get() = if (type == Type.TRACK && subtitle != null) "$subtitle $text" else text

    /**
     * This row's identity: [type], [text] and [subtitle], ignoring case (a
     * missing subtitle matches an empty one, which is how it is stored). Art
     * and ids are not identity: a refreshed avatar is still the same artist.
     * Also the recents list's LazyColumn key, which stays unique because
     * recording an entry drops every other with the same identity.
     *
     * Identity used to be the bare [text] across types, so the track "Intro"
     * and the artist "Intro" were one row, and a second "Intro" by another
     * artist replaced the first.
     */
    internal val identityKey: String
        // Joined by the unit separator the store strips from every field, so a
        // ":" in a title can't make two different rows read as one.
        get() = "$type\u001F${text.lowercase()}\u001F${subtitle.orEmpty().lowercase()}"

    /** Whether [other] is the same recents row as this one (see [identityKey]). */
    internal fun sameEntryAs(other: RecentSearch): Boolean = identityKey == other.identityKey

    /**
     * Whether recording this entry drops [other] from the recents: [other] is
     * the same row ([sameEntryAs]), or a plain query that this entry re-runs
     * exactly. The second keeps the designed merge: commit "lil wayne", open
     * the artist "Lil Wayne", and the recents show the artist with its avatar
     * rather than two near-duplicates. A song only absorbs the query
     * "coldplay yellow", never the broader "yellow" someone may want again.
     */
    internal fun supersedes(other: RecentSearch): Boolean =
        sameEntryAs(other) ||
            (other.type == Type.QUERY && other.text.equals(searchText, ignoreCase = true))
}
