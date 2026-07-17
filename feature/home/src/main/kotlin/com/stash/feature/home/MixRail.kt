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
