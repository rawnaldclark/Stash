package com.stash.feature.record.card

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import com.stash.feature.record.archive.RankedArtist
import com.stash.feature.record.archive.RecordSummary
import com.stash.feature.record.importer.ImportPreview
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.imports.ImportFailure
import com.stash.feature.record.imports.ImportState
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Home's Your record card: which one shows, and what it says. Its numbers are the record's (PageRowsTest runs the fork's
 * parity fixture through them); the sleeve's albums are the web's `topAlbums` (record-card.test.ts), and the words years.ts's.
 */
class RecordCardTest {
    private val h = 3_600_000L

    private fun total(artist: String, album: String, title: String, ms: Long, plays: Int = 1, first: Long = 1, last: Long = first, art: String? = null) =
        OwnAlbumTotal(artist = artist, album = album, title = title, albumArtUrl = art, plays = plays, listenedMs = ms, firstAt = first, lastAt = last)

    private fun summary(streams: Int, totalMs: Long = h, first: Long? = 100, artists: List<String> = listOf("A"), own: Int = streams) =
        RecordSummary(plays = streams + 3, streams = streams, totalMs = totalMs, first = first, topArtists = artists.map { RankedArtist(it, 1) }, ownStreams = own)

    private val preview = ImportPreview(
        plays = 10, same = 0, overlap = 0, listenedMs = h, first = 1, last = 2, years = 1, playlists = emptyList(),
        likes = 0, hidden = 0, extended = true, phone = 0, unreadable = 0,
    )

    @Test fun `the card's numbers are the record's - its streams, all its time, its earliest start, its ranking`() {
        val s = RecordSummary(
            plays = 80,
            streams = 71,
            totalMs = 131_400_000,
            first = 1_118_836_800_000,
            topArtists = listOf("Leif Inge", "Ben UFO", "Wye Oak", "Alvvays", "Hum", "Low").map { RankedArtist(it, 1) },
        )
        val v = RecordCard.viewOf(s, listOf(total("Lilac Motel", "Lights", "A", 400_000, art = "https://x/lights.jpg")))
        assertThat(v.plays).isEqualTo(71)
        assertThat(v.listenedMs).isEqualTo(131_400_000)
        assertThat(v.since).isEqualTo(1_118_836_800_000)
        assertThat(v.topArtist).isEqualTo("Leif Inge")
        assertThat(v.artists).containsExactly("Leif Inge", "Ben UFO", "Wye Oak", "Alvvays", "Hum").inOrder()
        assertThat(v.albums).containsExactly(CardAlbum("Lilac Motel", "Lights", "A", "https://x/lights.jpg"))
        val none = RecordCard.viewOf(RecordSummary(0, 0, 0, null, emptyList()), emptyList())
        assertThat(listOf(none.plays, none.since, none.topArtist, none.albums, none.artists)).containsExactly(0, 0L, null, emptyList<CardAlbum>(), emptyList<String>()).inOrder()
    }

    @Test fun `the albums listened to most, one per album however it's spelled, then by name`() {
        val albums = RecordCard.topAlbums(
            listOf(
                total("Paper Moons", "Tides", "One", 5 * h),
                total("Paper Moons", "Tides (Deluxe Edition)", "Two", 4 * h), // the same album
                total("Evergreen", "Always", "Three", 6 * h),
                total("Glass Harbor", "Harbor - Single", "Four", 2 * h),
                total("Velvet Static", "Static", "Five", 1 * h),
                total("Nobody", "", "Six", 50 * h), // no album: no cover to show
                total("Last", "Fifth", "Eight", h / 2),
                total("Paper Moons feat. Evergreen", "Moonlight", "Nine", h / 4),
            ),
        )
        assertThat(albums.map { it.album }).containsExactly("Tides", "Always", "Harbor - Single", "Static").inOrder()
        assertThat(albums).hasSize(RecordCard.CARD_ALBUMS)
        // the first album's cover is kept from whichever of its spellings has one
        val covered = RecordCard.topAlbums(listOf(total("A", "Tides", "1", 2 * h), total("A", "Tides (Deluxe)", "2", h, art = "https://x/t.jpg")))
        assertThat(covered.single().cover).isEqualTo("https://x/t.jpg")
        // ties: by album name, in UTF-16 order
        val tie = RecordCard.topAlbums(listOf(total("A", "beta", "1", h), total("B", "Alpha", "2", h)))
        assertThat(tie.map { it.album }).containsExactly("Alpha", "beta").inOrder()
    }

    @Test fun `the record at fifty streams of this phone's own, under that the invitation, unless it was hidden`() {
        assertThat(RecordCard.INVITE_ENABLED).isTrue()
        assertThat(RecordCard.stateOf(summary(streams = 49), emptyList(), CardImport())).isEqualTo(RecordCardState.Invite)
        assertThat(RecordCard.stateOf(summary(streams = 49), emptyList(), CardImport(inviteHidden = true))).isEqualTo(RecordCardState.Hidden)
        assertThat(RecordCard.stateOf(summary(streams = 49), emptyList(), CardImport(), invite = false)).isEqualTo(RecordCardState.Hidden)
        val record = RecordCard.stateOf(summary(streams = 50, artists = listOf("A", "B")), listOf(total("A", "B", "C", h)), CardImport(inviteHidden = true))
        assertThat(record).isInstanceOf(RecordCardState.Record::class.java)
        record as RecordCardState.Record
        assertThat(record.view.plays).isEqualTo(RecordCard.OWN_FOR_RECORD)
        assertThat(record.albumArt).hasSize(1)
        assertThat(record.artistArt).containsExactly(null, null)
        // fifty streams in all, but only ten of them this phone's own (and nothing imported): not yet a record
        assertThat(RecordCard.stateOf(summary(streams = 50, own = 10), emptyList(), CardImport())).isEqualTo(RecordCardState.Invite)
    }

    @Test fun `once anything is imported, the record, whatever this phone played`() {
        val s = RecordCard.stateOf(summary(streams = 6_086, own = 0), emptyList(), CardImport(imported = true, inviteHidden = true))
        assertThat(s).isInstanceOf(RecordCardState.Record::class.java)
        assertThat((s as RecordCardState.Record).view.plays).isEqualTo(6_086)
    }

    @Test fun `waiting for Spotify once they asked, and the asked date survives a hidden card only as hidden`() {
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), CardImport(asked = 1_761_000_000_000))).isEqualTo(RecordCardState.Waiting(1_761_000_000_000))
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), CardImport(asked = 1, inviteHidden = true))).isEqualTo(RecordCardState.Hidden)
    }

    @Test fun `an import on its way shows how far it is, over the invitation and over the record`() {
        val reading = ImportState.Working(ImportProgress.Read(2, 7, 0.4f, 2019))
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), CardImport(state = reading))).isEqualTo(RecordCardState.Importing(reading.progress))
        assertThat(RecordCard.stateOf(summary(streams = 80), emptyList(), CardImport(imported = true, state = reading))).isEqualTo(RecordCardState.Importing(reading.progress))
        // even hidden: an import is on its way
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), CardImport(inviteHidden = true, state = reading))).isEqualTo(RecordCardState.Importing(reading.progress))
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), CardImport(state = ImportState.Preview(preview, 2)))).isEqualTo(RecordCardState.Ready)
    }

    @Test fun `a failure or a done import is no import on its way`() {
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), CardImport(state = ImportState.Failed(ImportFailure.NOT_EXPORT)))).isEqualTo(RecordCardState.Invite)
        assertThat(RecordCard.stateOf(summary(streams = 60, own = 0), emptyList(), CardImport(imported = true, state = ImportState.Done(60, 60))))
            .isInstanceOf(RecordCardState.Record::class.java)
    }

    @Test fun `an import that didn't finish says so over the record and a hidden invitation, until an import is on its way again`() {
        val unfinished = CardImport(unfinished = true)
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), unfinished)).isEqualTo(RecordCardState.Unfinished)
        assertThat(RecordCard.stateOf(summary(streams = 6_086), emptyList(), unfinished.copy(imported = true))).isEqualTo(RecordCardState.Unfinished)
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), unfinished.copy(inviteHidden = true))).isEqualTo(RecordCardState.Unfinished)
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), unfinished.copy(state = ImportState.Failed(ImportFailure.OTHER, saving = true))))
            .isEqualTo(RecordCardState.Unfinished)
        // Tried again: how far it is
        val reading = ImportState.Working(ImportProgress.Open)
        assertThat(RecordCard.stateOf(summary(streams = 0), emptyList(), unfinished.copy(state = reading))).isEqualTo(RecordCardState.Importing(ImportProgress.Open))
    }

    @Test fun `what the import alone decides is the same card whatever the plays, and nothing else is decided without them`() {
        val cases = listOf(
            CardImport(unfinished = true),
            CardImport(imported = true, unfinished = true, state = ImportState.Failed(ImportFailure.TOO_BIG)),
            CardImport(state = ImportState.Working(ImportProgress.Open)),
            CardImport(imported = true, inviteHidden = true, state = ImportState.Preview(preview, 1)),
        )
        for (c in cases) {
            val alone = RecordCard.importOnly(c)
            assertThat(alone).isNotNull()
            assertThat(alone).isEqualTo(RecordCard.stateOf(summary(streams = 6_086, artists = listOf("A")), emptyList(), c))
        }
        // The record, the invitation, waiting for Spotify: those need the plays
        assertThat(RecordCard.importOnly(CardImport(imported = true))).isNull()
        assertThat(RecordCard.importOnly(CardImport())).isNull()
        assertThat(RecordCard.importOnly(CardImport(asked = 1, state = ImportState.Done(1, 1)))).isNull()
    }

    @Test fun `hours, at least 1 once anything played, and counts with separators`() {
        assertThat(RecordCard.count(48_210)).isEqualTo("48,210")
        assertThat(RecordCard.count(48_210, Locale.GERMANY)).isEqualTo("48.210")
        assertThat(RecordCard.hours(0)).isEqualTo(0)
        assertThat(RecordCard.hours(60_000)).isEqualTo(1)
        assertThat(RecordCard.hours((1.6 * h).toLong())).isEqualTo(2)
        // half up, as JavaScript's Math.round (the fixture's 36.5 hours read 37)
        assertThat(RecordCard.hours(131_400_000)).isEqualTo(37)
    }

    @Test fun `since when, in the phone's time zone, as the record shows it`() {
        val v = CardView(listenedMs = h, since = 1_451_604_600_000, plays = 1, topArtist = null, albums = emptyList(), artists = emptyList())
        // 2015-12-31 23:30 UTC: still 2015 in London, already 2016 in Tokyo
        assertThat(RecordCard.sinceYear(v, ZoneId.of("Europe/London"))).isEqualTo(2015)
        assertThat(RecordCard.sinceYear(v, ZoneId.of("Asia/Tokyo"))).isEqualTo(2016)
        assertThat(RecordCard.sinceYear(v.copy(since = 0))).isNull()
    }

    @Test fun `initials for a picture still on its way`() {
        assertThat(RecordCard.initials("Paper Moons")).isEqualTo("PM")
        assertThat(RecordCard.initials("evergreen")).isEqualTo("E")
        assertThat(RecordCard.initials("Sigur Rós Orchestra")).isEqualTo("SR")
        assertThat(RecordCard.initials("  ")).isEqualTo("")
        assertThat(RecordCard.initials("ßeta 🎵band")).isEqualTo("SS🎵")
    }
}
