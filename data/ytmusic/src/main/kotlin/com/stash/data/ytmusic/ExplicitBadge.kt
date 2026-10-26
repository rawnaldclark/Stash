package com.stash.data.ytmusic

import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * YouTube Music's parental-advisory "E" badge, and how to choose between the
 * explicit and clean copies of a song that it is the only difference between.
 *
 * Measured on InnerTube WEB_REMIX (2026-10-26): an explicit song carries a
 * `musicInlineBadgeRenderer` whose icon is [EXPLICIT_BADGE_ICON]; its clean
 * edit carries nothing. The two are otherwise twins: the same title, album
 * name and length, told apart only by their videoId (and album browseId).
 * Which one a search lists first varies (the songs filter put the explicit one
 * first; an unfiltered search showed it in the top card and the clean one as a
 * later row), so taking "the first result" played the clean edit by chance.
 *
 * A missing badge does not prove a song clean (most songs are never explicit),
 * so the badge reads as `true` or unknown (`null`), never `false`.
 */

/** InnerTube's icon type for the explicit badge. */
internal const val EXPLICIT_BADGE_ICON = "MUSIC_EXPLICIT_BADGE"

/**
 * Where a renderer keeps its badges: list rows (search, playlists, album
 * tracklists) in `badges`, the top-result card and two-row album tiles in
 * `subtitleBadges`, an album page's header in `subtitleBadge`.
 */
private val BADGE_KEYS = listOf("badges", "subtitleBadges", "subtitleBadge")

/** Twins agree on length to the second; this allows rounding between layouts. */
private const val TWIN_DURATION_TOLERANCE_SEC = 3.0

private val WHITESPACE = Regex("\\s+")

/**
 * True when [renderer] (a `musicResponsiveListItemRenderer`,
 * `musicCardShelfRenderer`, `musicTwoRowItemRenderer` or
 * `musicResponsiveHeaderRenderer`) carries the explicit badge.
 */
fun hasExplicitBadge(renderer: JsonObject): Boolean =
    BADGE_KEYS.any { key ->
        renderer[key]?.asArray()?.any { badge ->
            badge.asObject()
                ?.navigatePath("musicInlineBadgeRenderer", "icon", "iconType")
                ?.asString() == EXPLICIT_BADGE_ICON
        } == true
    }

/**
 * [hasExplicitBadge] in the shape the models carry: `true` when badged, `null`
 * otherwise, since an unbadged song may be a clean edit or never explicit.
 */
fun explicitFromBadge(renderer: JsonObject): Boolean? =
    if (hasExplicitBadge(renderer)) true else null

/** What [preferExplicitTwin] compares a candidate on. */
data class ExplicitTwinKey(
    val title: String,
    val artist: String,
    /** 0 or less when the row carries no length (flat search rows don't). */
    val durationSeconds: Double,
    val explicitBadge: Boolean,
)

/**
 * The first of [candidates], unless one of its explicit/clean twins suits the
 * source track better: a badged twin when [explicit] is `true` or `null` (play
 * the original when the source doesn't say), an unbadged one when it is `false`
 * (the source is known clean).
 *
 * A twin has the first candidate's title and artist (ignoring case and spacing)
 * and, when both lengths are known, a length within a few seconds. Nothing
 * looser counts: an "(Instrumental)" or "(Live)" of the same length is another
 * recording, not a twin. So the preference only ever chooses between copies of
 * one recording and never replaces the pick with a different song; with no
 * twin, the first candidate stands exactly as before.
 *
 * @return null only when [candidates] is empty.
 */
fun <T> preferExplicitTwin(
    candidates: List<T>,
    explicit: Boolean?,
    key: (T) -> ExplicitTwinKey,
): T? {
    val first = candidates.firstOrNull() ?: return null
    val lead = key(first)
    val wantBadge = explicit != false
    return candidates.firstOrNull { candidate ->
        val k = key(candidate)
        k.explicitBadge == wantBadge && isExplicitTwin(lead, k)
    } ?: first
}

private fun isExplicitTwin(a: ExplicitTwinKey, b: ExplicitTwinKey): Boolean {
    if (twinText(a.title) != twinText(b.title)) return false
    if (twinText(a.artist) != twinText(b.artist)) return false
    if (a.durationSeconds <= 0.0 || b.durationSeconds <= 0.0) return true
    return abs(a.durationSeconds - b.durationSeconds) <= TWIN_DURATION_TOLERANCE_SEC
}

private fun twinText(s: String): String = s.trim().lowercase().replace(WHITESPACE, " ")
