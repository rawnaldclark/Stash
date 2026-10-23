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

/** A mix counts as barely played below this share of its songs played. */
internal const val UNPLAYED_MIX_SHARE = 0.25f

/**
 * Which mixes are barely played: fewer than [UNPLAYED_MIX_SHARE] of their live
 * songs played even once. A mix with no live songs isn't counted either way.
 */
internal fun barelyPlayedMixIds(shares: List<com.stash.core.data.db.dao.PlaylistPlayedShare>): Set<Long> =
    shares
        .filter { it.liveTracks > 0 && it.playedTracks.toFloat() / it.liveTracks < UNPLAYED_MIX_SHARE }
        .map { it.playlistId }
        .toSet()

/**
 * A service rail's order with rotation: favorites (pinned) first, first pinned
 * first, never rotated; then, when [unplayed] is given, the barely played mixes
 * ahead of the rest; and within each group either [freshestFirst] ([rotation]
 * OFF) or a shuffle that holds for one rotation period.
 *
 * The shuffle is seeded by the period number ([epochDay] / days) and [salt] (one
 * per rail, so two rails don't move in step), and starts from an id-sorted list:
 * the same mixes give the same order all period, whatever order they arrive in,
 * so the rail doesn't jump while it's being used. The next period, it reshuffles.
 */
internal fun List<HomeMix>.rotated(
    recency: Map<Long, Long>,
    rotation: com.stash.core.data.prefs.MixRotation,
    epochDay: Long,
    salt: Int,
    unplayed: Set<Long>? = null,
): List<HomeMix> {
    val (pinned, rest) = partition { it.pinnedToHomeAt != null }
    val groups = if (unplayed == null) listOf(rest) else rest.partition { it.id in unplayed }.toList()
    fun order(group: List<HomeMix>): List<HomeMix> =
        if (rotation == com.stash.core.data.prefs.MixRotation.OFF) {
            group.freshestFirst(recency)
        } else {
            val period = epochDay / rotation.days
            group.sortedBy { it.id }.shuffled(kotlin.random.Random(period * 1_000_003L + salt))
        }
    return pinned.sortedBy { it.pinnedToHomeAt } + groups.flatMap(::order)
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
