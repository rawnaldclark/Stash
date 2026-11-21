package com.stash.feature.community.sessions

import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.OnAirWindow
import com.stash.core.model.community.Station
import com.stash.core.model.share.SharedTrack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * What the Sessions cards show, worked out from the view model's state with no Android in it, so the rules are
 * unit-tested (SessionsUiTest). The composables only draw these.
 */

/** Home's two Sessions rows, above Community's posts. */
internal data class SessionsRows(
    /** The stations this phone follows, on the air first: the round cards. Empty: the row isn't shown. */
    val yourStations: List<Station>,
    /** Live now, best first: the wide cards. Empty: one line says nobody's live. */
    val live: List<LiveSession>,
    /** How many are live, or null when the list may be cut short (it holds as many as were asked for). */
    val liveCount: Int?,
)

/**
 * Home's Sessions rows from [state], which asked for [asked] live sessions. Null while nothing about Sessions shows:
 * Community off, a Worker with Sessions off or one that hasn't said, or nothing loaded yet.
 */
internal fun sessionsRows(state: SessionsViewModel.UiState, asked: Int): SessionsRows? {
    val live = state.live
    if (!state.on || live == null) return null
    return SessionsRows(yourStations = state.following, live = live, liveCount = live.size.takeIf { it < asked })
}

/** What the Sessions screen shows under its top bar. */
internal sealed interface SessionsListUi {
    /** Nothing known yet: skeletons. */
    data object Loading : SessionsListUi

    /** Community is off, or the Worker says Sessions is: "Sessions isn't available right now". */
    data object Off : SessionsListUi

    /** Nothing loaded and the last try failed: a card with Retry. */
    data object Failed : SessionsListUi

    /** Nobody's on the air: a card that offers to go live. */
    data object Empty : SessionsListUi

    /** The live stations you follow, then [everyone] else as the Worker ranks them, the ranks counting those alone. */
    data class Ready(val following: List<LiveSession>, val everyone: List<RankedSession>) : SessionsListUi
}

/** One of everyone else's sessions with its place in that list, from 1. */
internal data class RankedSession(val rank: Int, val session: LiveSession)

/** The Sessions screen's list from [state]. Both halves keep the Worker's order (best first). */
internal fun sessionsList(state: SessionsViewModel.UiState): SessionsListUi {
    val live = state.live
    return when {
        state.off -> SessionsListUi.Off
        live == null -> if (state.failed) SessionsListUi.Failed else SessionsListUi.Loading
        live.isEmpty() -> SessionsListUi.Empty
        else -> {
            val (following, rest) = live.partition { it.station.following }
            SessionsListUi.Ready(following, rest.mapIndexed { i, s -> RankedSession(i + 1, s) })
        }
    }
}

/** "Pink + White · Frank Ocean": the song a session is playing, as one line. */
internal fun SharedTrack.nowLine(): String = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")

/** A station's own face while it's off the air: its name's first letter or digit, as a room's faces show initials. */
internal fun Station.initial(): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/**
 * When a station is next on the air, as a round card says it: the time alone when [next] starts today ("8:30 AM"),
 * else with its day ("Fri 9:00 PM"), in the phone's [zone] and [locale]. Null without a window, or for one that has
 * started (a station on the air says LIVE instead).
 */
internal fun onAirLabel(next: OnAirWindow?, now: Long, zone: ZoneId, locale: Locale): String? {
    if (next == null || next.start <= now) return null
    val start = Instant.ofEpochMilli(next.start).atZone(zone)
    // Newer locale data puts a narrow no-break space before AM and PM: a plain one reads the same and wraps as text does.
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(start)
        .replace(' ', ' ').replace(' ', ' ')
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (start.toLocalDate() == today) time else "${DateTimeFormatter.ofPattern("EEE", locale).format(start)} $time"
}
