package com.stash.feature.record.card

import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.JsText
import com.stash.feature.record.archive.RecordSummary
import com.stash.feature.record.art.ArtText
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.imports.ImportState
import java.time.Instant
import java.time.ZoneId
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

/** The card, by what there is (stash-player HomeCard.svelte's states). */
sealed interface RecordCardState {
    /** A record (an import, or enough plays of this phone's own): the way into it. Covers come as they're found. */
    data class Record(val view: CardView, val albumArt: List<String?>, val artistArt: List<String?>) : RecordCardState

    /** Nothing yet: "Press your record", the ways in, and Get started (the import sheet). */
    data object Invite : RecordCardState

    /** They tapped through to ask Spotify for their data on [askedAt]: the zip can come straight in when the email does. */
    data class Waiting(val askedAt: Long) : RecordCardState

    /** An import on its way with its sheet closed: how far it is, and the way back to it. */
    data class Importing(val progress: ImportProgress) : RecordCardState

    /** A preview waiting for the person (nothing is written until they import). */
    data object Ready : RecordCardState

    /** Nothing on Home: no record yet, and the invitation hidden with its ×. */
    data object Hidden : RecordCardState
}

/** What the import says, for the card: whether anything came in, when Spotify was asked, the ×, and the import's own state. */
data class CardImport(
    val imported: Boolean = false,
    val asked: Long? = null,
    val inviteHidden: Boolean = false,
    val state: ImportState = ImportState.Idle,
)

/**
 * The card's rules. Once there is a record it says what the record says, as the web's card does once a record exists
 * (record-card.ts `fromTaste`, given the record's own count): the plays (its streams, 30 s or more: what every screen of the
 * web's record calls plays), the hours (all the time it keeps), the earliest year and the artists come from the rows the
 * record keeps ([RecordSummary]); only the sleeve's albums are ranked from the plays totalled by album (the web's
 * `topAlbums`).
 */
object RecordCard {
    /** This phone's own plays (streams) that make a record without an import (shared.ts `OWN_FOR_RECORD`). */
    const val OWN_FOR_RECORD = 50

    /** "Press your record" before there is a record: on, now that Get started has something to bring in (the Spotify import). */
    const val INVITE_ENABLED = true

    /** Albums on the sleeve (its 2×2; the first is also the label), and artists in the row. */
    const val CARD_ALBUMS = 4
    const val CARD_ARTISTS = 5

    private const val HOUR_MS = 3_600_000.0

    /**
     * HomeCard.svelte: the record once anything was imported or this phone has [OWN_FOR_RECORD] streams of its own, unless an
     * import is on its way (then how far it is, or the preview waiting); else the invitation ("Waiting for Spotify" once they
     * asked), unless its × hid it.
     */
    fun stateOf(
        summary: RecordSummary,
        albums: List<OwnAlbumTotal>,
        import: CardImport,
        invite: Boolean = INVITE_ENABLED,
    ): RecordCardState {
        val entry = import.imported || summary.ownStreams >= OWN_FOR_RECORD
        val st = import.state
        return when {
            entry && !st.busy -> viewOf(summary, albums).let { view -> RecordCardState.Record(view, view.albums.map { it.cover }, view.artists.map { null }) }
            st is ImportState.Preview -> RecordCardState.Ready
            st is ImportState.Working -> RecordCardState.Importing(st.progress)
            !invite || import.inviteHidden -> RecordCardState.Hidden
            import.asked != null -> RecordCardState.Waiting(import.asked)
            else -> RecordCardState.Invite
        }
    }

    /** The card from the record's own numbers, and the sleeve from the albums played most. */
    fun viewOf(summary: RecordSummary, albums: List<OwnAlbumTotal>): CardView = CardView(
        listenedMs = summary.totalMs,
        since = summary.first ?: 0,
        plays = summary.streams,
        topArtist = summary.topArtists.firstOrNull()?.name,
        albums = topAlbums(albums),
        artists = summary.topArtists.take(CARD_ARTISTS).map { it.name },
    )

    /**
     * record-card.ts `topAlbums`, from the plays totalled by artist and album (newest first, so an album's spelling and its
     * song are the ones seen first, as the web's newest-first history has them): one per album however it's spelled (the
     * primary artist and the album without its marks), the most played first, then by name. The words are cleaned as the
     * export cleans them.
     */
    fun topAlbums(totals: List<OwnAlbumTotal>): List<CardAlbum> {
        class Album(val a: CardAlbum, var ms: Long, var cover: String?)
        val albums = LinkedHashMap<String, Album>()
        for (r in totals) {
            val artist = HandoffWire.cleanText(r.artist, TEXT)
            val album = HandoffWire.cleanText(r.album, TEXT)
            if (album.isEmpty()) continue
            val cover = r.albumArtUrl?.takeIf { it.isNotBlank() }
            val k = "${artistKey(artist)}|${ArtText.bareAlbum(album).lowercase()}"
            val a = albums[k]
            if (a == null) {
                albums[k] = Album(CardAlbum(artist, album, HandoffWire.cleanText(r.title, TEXT)), r.listenedMs, cover)
            } else {
                a.ms += r.listenedMs
                if (a.cover == null) a.cover = cover
            }
        }
        return albums.values
            .sortedWith(compareByDescending<Album> { it.ms }.thenComparator { x, y -> x.a.album.compareTo(y.a.album) })
            .take(CARD_ALBUMS)
            .map { it.a.copy(cover = it.cover) }
    }

    /** taste.ts `artistKey`: the primary artist, folded. */
    fun artistKey(artist: String): String = SongKey.fold(ArtText.primary(artist))

    /** years.ts `hours`: whole hours, at least 1 once anything was listened to (half up, as JavaScript rounds). */
    fun hours(ms: Long): Long = if (ms <= 0) 0 else maxOf(1L, Math.round(ms / HOUR_MS))

    /** years.ts `count` in [locale]: "1,234" in English. */
    fun count(n: Long, locale: Locale = Locale.US): String = String.format(locale, "%,d", n)

    /** The year of the earliest play in the phone's time zone, as the record shows it; null with none. */
    fun sinceYear(view: CardView, zone: ZoneId = ZoneId.systemDefault()): Int? =
        if (view.since > 0) Instant.ofEpochMilli(view.since).atZone(zone).year else null

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
