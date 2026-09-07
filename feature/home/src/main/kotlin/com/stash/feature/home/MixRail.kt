package com.stash.feature.home

import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType

/** The four Home mix rails, in display order. */
enum class MixRail { MADE_FOR_YOU, RADIOS, MOOD_DECADES, YOUR_MIXES }

private val MADE_FOR_YOU_NAMES = setOf(
    "discover weekly", "release radar", "on repeat", "repeat rewind", "daylist", "time capsule",
    "my supermix", "discover mix", "replay mix", "archive mix", "new release mix",
)
private val DAILY_OR_MYMIX = Regex("""^(daily mix|my mix)\s*\d+$""", RegexOption.IGNORE_CASE)

/**
 * Which Home rail a playlist belongs to, or null if it isn't a mix.
 * Order matters: STASH_MIX -> yours; within DAILY_MIX, radios beat the
 * made-for-you set, which beats the mood/decade fallback.
 */
fun mixRail(playlist: Playlist): MixRail? {
    if (playlist.type == PlaylistType.STASH_MIX) return MixRail.YOUR_MIXES
    if (playlist.type != PlaylistType.DAILY_MIX) return null
    val n = playlist.name.trim()
    if (n.endsWith("Radio", ignoreCase = true)) return MixRail.RADIOS
    if (DAILY_OR_MYMIX.matches(n) || n.lowercase() in MADE_FOR_YOU_NAMES) return MixRail.MADE_FOR_YOU
    return MixRail.MOOD_DECADES
}

/**
 * Freshest first: the mix that most recently gained a track leads the rail.
 *
 * Home's rails used to inherit the DAO's `ORDER BY p.name ASC`, so their head
 * was a fixed alphabetical prefix of the library — a sync could pull in hundreds
 * of songs and Home looked untouched, because the mixes that changed sorted into
 * the middle of an uncapped rail. Ordering by last-gained-a-track makes the rail
 * head exactly what the last sync found.
 *
 * [recency] maps playlist id -> epoch millis of its newest live membership
 * (PlaylistDao.observeLatestAdditionPerPlaylist). A mix with no live memberships
 * is absent from it and sorts last rather than jumping the queue. Name is the
 * tie-break: a sync writes many rows in the same millisecond, so ties are the
 * common case, and without it the rail would reshuffle on every emission.
 */
internal fun List<HomeMix>.freshestFirst(recency: Map<Long, Long>): List<HomeMix> =
    sortedWith(
        compareByDescending<HomeMix> { recency[it.id] ?: Long.MIN_VALUE }
            .thenBy { it.title.lowercase() },
    )

/**
 * Pinned first, then freshest. "Shown on Home" on the manage screen pins a mix
 * (`pinnedToHomeAt`), and a pinned mix leads its rail no matter how stale its
 * tracks are: Cup Noodle Radio (Pixel 6, 2026-09-07) sat 36th of 63 radios
 * behind the 12-card cut with the switch on. Pins keep their order, first
 * pinned first (the Your-playlists precedent); the rest stays [freshestFirst].
 */
internal fun List<HomeMix>.pinnedThenFreshest(recency: Map<Long, Long>): List<HomeMix> {
    val (pinned, rest) = partition { it.pinnedToHomeAt != null }
    return pinned.sortedBy { it.pinnedToHomeAt } + rest.freshestFirst(recency)
}

/**
 * The rail's visible head: every pinned mix, then fresh ones up to [limit] cards
 * in total. Expects [pinnedThenFreshest] order, so the pinned ones are the head.
 */
internal fun List<HomeMix>.railCut(limit: Int): List<HomeMix> =
    take(maxOf(limit, count { it.pinnedToHomeAt != null }))

/**
 * Home's "Your playlists" rail: playlists the user explicitly pinned
 * (`pinnedToHomeAt != null`), excluding anything that already lives on a
 * mix rail. Ordered by pin time — first pinned renders first; stable, no
 * reshuffling (the Your-mixes stable-order precedent).
 *
 * Reads the UNFILTERED playlist list on purpose: membership here depends
 * only on the pin stamp, never on the mixes' `hideFromHome` flow.
 */
fun yourPlaylistsRail(playlists: List<Playlist>): List<Playlist> =
    playlists
        .filter { it.pinnedToHomeAt != null && mixRail(it) == null }
        .sortedBy { it.pinnedToHomeAt }
