package com.stash.feature.listening.recap

import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.feature.listening.stats.ListeningInsights
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * A recap worth offering (Home's Recap door, Your listening's banner): the period, its title and a
 * short line ("11 h · #1 Garden"), and the #1 song's cover for the card's art and tint.
 */
data class RecapOffer(
    val period: RecapPeriod,
    /** "Your October so far". */
    val title: String,
    /** "11 h · #1 Garden". */
    val subtitle: String,
    val art: ArtModel?,
) {}

/**
 * The first of [candidates] with enough listens for a recap, as an offer; null when none
 * qualifies. One totals read per candidate tried, then the winner's top songs.
 */
internal suspend fun readRecapOffer(
    history: ListeningHistoryRepository,
    candidates: List<RecapPeriod>,
    today: Long,
): RecapOffer? {
    var plays = 0
    var listenedMs = 0L
    var estimated = false
    val period = RecapAvailability.firstQualifying(candidates) { p ->
        val totals = history.observeTotals(p.dayRange(today)).first()
        plays = totals.plays
        listenedMs = totals.listenedMs
        estimated = totals.estimatedPlays > 0
        totals.plays
    } ?: return null
    val top = ListeningInsights.mergeSameSongs(
        history.observeTopTracks(period.dayRange(today), ListeningInsights.SCAN_LIMIT).first(),
    ).firstOrNull()
    val subtitle = listOfNotNull(
        shortTime(listenedMs).takeIf { listenedMs > 0 }?.let { if (estimated) "about $it" else it } ?: "$plays plays",
        top?.let { "#1 ${it.displayTitle}" },
    ).joinToString(" · ")
    return RecapOffer(period, period.title(today), subtitle, top?.art())
}

/** A door-sized time: "47 min", "11 h" (whole hours, never more than the screens' exact time says). */
internal fun shortTime(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "%,d h".format(Locale.US, minutes / 60)
}
