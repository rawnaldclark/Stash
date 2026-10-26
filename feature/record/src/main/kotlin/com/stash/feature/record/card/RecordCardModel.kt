package com.stash.feature.record.card

import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.JsText
import com.stash.feature.record.art.ArtText
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** An album on the card: its words (to look its cover up by) and the cover one of its songs already has, when any does. */
data class CardAlbum(val artist: String, val album: String, val title: String, val cover: String? = null)

/**
 * What Home's Your record card shows (stash-player `src/lib/ui/spotify/record-card.ts` `CardView`): the time listened, since
 * when, how many plays, the artist played most, the albums whose covers make its sleeve (the most played first: also the
 * disc's label) and the artists whose pictures sit in a row.
 */
data class CardView(
    val listenedMs: Long,
    val since: Long,
    val plays: Int,
    val topArtist: String?,
    val albums: List<CardAlbum>,
    val artists: List<String>,
)

/** The card, by what there is. */
sealed interface RecordCardState {
    /** Enough plays of the phone's own for a record: the way into it. Covers come as they're found ([albumArt], [artistArt]). */
    data class Record(val view: CardView, val albumArt: List<String?>, val artistArt: List<String?>) : RecordCardState

    /** Not yet: "Press your record", and Get started. */
    data object Invite : RecordCardState

    /** The invitation hidden with its ×, and no record yet: nothing on Home. */
    data object Hidden : RecordCardState
}

/**
 * The card's rules, ported from the web (record-card.ts, HomeCard.svelte, years.ts) so it reads the same: the record once the
 * phone has [OWN_FOR_RECORD] plays of its own (the web's `OWN_FOR_RECORD`), each counted at its song's length or a guess
 * (the web's quirk, copied knowingly: its own plays count `durationMs ?? 210000` on this card); the invitation before that,
 * unless it was hidden.
 */
object RecordCard {
    /** Plays of the phone's own that make a record without an import (shared.ts `OWN_FOR_RECORD`). */
    const val OWN_FOR_RECORD = 50

    /** Albums on the sleeve (its 2×2; the first is also the label), and artists in the row. */
    const val CARD_ALBUMS = 4
    const val CARD_ARTISTS = 5

    private const val HOUR_MS = 3_600_000.0

    fun stateOf(totals: List<OwnAlbumTotal>, inviteHidden: Boolean): RecordCardState {
        val plays = totals.sumOf { it.plays }
        if (plays >= OWN_FOR_RECORD) {
            val view = fromOwn(totals)
            return RecordCardState.Record(view, view.albums.map { it.cover }, view.artists.map { null })
        }
        return if (inviteHidden) RecordCardState.Hidden else RecordCardState.Invite
    }

    /**
     * record-card.ts `fromOwn`, from the plays totalled by artist and album (newest first, so a name's spelling is the one
     * seen first, as the web's newest-first history has it). The words are cleaned as the export cleans them.
     */
    fun fromOwn(totals: List<OwnAlbumTotal>): CardView {
        class Artist(val name: String, var ms: Long)
        class Album(val a: CardAlbum, var ms: Long, var cover: String?)
        val artists = LinkedHashMap<String, Artist>()
        val albums = LinkedHashMap<String, Album>()
        var ms = 0L
        var since = Long.MAX_VALUE
        var plays = 0
        for (r in totals) {
            val artist = HandoffWire.cleanText(r.artist, TEXT)
            val album = HandoffWire.cleanText(r.album, TEXT)
            val title = HandoffWire.cleanText(r.title, TEXT)
            ms += r.listenedMs
            plays += r.plays
            since = minOf(since, r.firstAt)
            artists.getOrPut(artistKey(artist)) { Artist(artist, 0) }.ms += r.listenedMs
            if (album.isEmpty()) continue
            val cover = r.albumArtUrl?.takeIf { it.isNotBlank() }
            val k = "${artistKey(artist)}|${ArtText.bareAlbum(album).lowercase()}"
            val a = albums[k]
            if (a == null) {
                albums[k] = Album(CardAlbum(artist, album, title), r.listenedMs, cover)
            } else {
                a.ms += r.listenedMs
                if (a.cover == null) a.cover = cover
            }
        }
        val ranked = artists.values.sortedWith(compareByDescending<Artist> { it.ms }.thenComparator { x, y -> x.name.compareTo(y.name) })
        return CardView(
            listenedMs = ms,
            since = if (since == Long.MAX_VALUE) 0 else since,
            plays = plays,
            topArtist = ranked.firstOrNull()?.name,
            albums = albums.values
                .sortedWith(compareByDescending<Album> { it.ms }.thenComparator { x, y -> x.a.album.compareTo(y.a.album) })
                .take(CARD_ALBUMS)
                .map { it.a.copy(cover = it.cover) },
            artists = ranked.take(CARD_ARTISTS).map { it.name },
        )
    }

    /** taste.ts `artistKey`: the primary artist, folded. */
    fun artistKey(artist: String): String = SongKey.fold(ArtText.primary(artist))

    /** years.ts `hours`: whole hours, at least 1 once anything was listened to (half up, as JavaScript rounds). */
    fun hours(ms: Long): Long = if (ms <= 0) 0 else maxOf(1L, Math.round(ms / HOUR_MS))

    /** years.ts `count`: "1,234". */
    fun count(n: Long): String = String.format(Locale.US, "%,d", n)

    /** "412 hours", "1 hour". */
    fun hoursLabel(ms: Long): String = hours(ms).let { "${count(it)} ${if (it == 1L) "hour" else "hours"}" }

    /** "since 2024 · 1,234 plays" (the year in UTC, as the web's `getUTCFullYear`). */
    fun sinceLine(view: CardView): String {
        val year = if (view.since > 0) Instant.ofEpochMilli(view.since).atZone(ZoneOffset.UTC).year else null
        val plays = "${count(view.plays.toLong())} ${if (view.plays == 1) "play" else "plays"}"
        return if (year != null) "since $year · $plays" else plays
    }

    /** record-card.ts `initials`: "AB" for an artist with no picture yet (the first letter of the first two words). */
    fun initials(name: String): String =
        splitSpace(name).take(2).joinToString("") { w -> String(Character.toChars(w.codePointAt(0))).uppercase() }

    private fun splitSpace(s: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (c in s) {
            if (JsText.isSpace(c)) {
                if (word.isNotEmpty()) out += word.toString().also { word.clear() }
            } else {
                word.append(c)
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    /** Words are cut as a song's are (300). */
    private const val TEXT = 300
}
