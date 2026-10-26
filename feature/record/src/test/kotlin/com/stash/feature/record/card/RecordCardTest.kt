package com.stash.feature.record.card

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import org.junit.Test

/**
 * Home's Your record card: which one shows, and what it says. The web's own cases (src/lib/ui/spotify/record-card.test.ts and
 * years.test.ts), with the phone's plays totalled by album as the query hands them (newest first).
 */
class RecordCardTest {
    private val h = 3_600_000L

    private fun total(artist: String, album: String, title: String, ms: Long, plays: Int = 1, first: Long = 1, last: Long = first, art: String? = null) =
        OwnAlbumTotal(artist = artist, album = album, title = title, albumArtUrl = art, plays = plays, listenedMs = ms, firstAt = first, lastAt = last)

    @Test fun `from the phone's own plays, their lengths, the earliest, the covers the songs have`() {
        val v = RecordCard.fromOwn(
            listOf(
                total("Lilac Motel", "Lights", "A", 400_000, plays = 2, first = 200, last = 300, art = "https://x/lights.jpg"),
                total("Juniper Wake", "Kerosene", "C", 210_000, first = 100),
            ),
        )
        assertThat(v.listenedMs).isEqualTo(610_000)
        assertThat(v.since).isEqualTo(100)
        assertThat(v.plays).isEqualTo(3)
        assertThat(v.topArtist).isEqualTo("Lilac Motel")
        assertThat(v.artists).containsExactly("Lilac Motel", "Juniper Wake").inOrder()
        assertThat(v.albums).containsExactly(
            CardAlbum("Lilac Motel", "Lights", "A", "https://x/lights.jpg"),
            CardAlbum("Juniper Wake", "Kerosene", "C"),
        ).inOrder()
        val none = RecordCard.fromOwn(emptyList())
        assertThat(listOf(none.plays, none.since, none.topArtist, none.albums, none.artists)).containsExactly(0, 0L, null, emptyList<CardAlbum>(), emptyList<String>()).inOrder()
    }

    @Test fun `the albums listened to most, one per album however it's spelled, then by name`() {
        val v = RecordCard.fromOwn(
            listOf(
                total("Paper Moons", "Tides", "One", 5 * h),
                total("Paper Moons", "Tides (Deluxe Edition)", "Two", 4 * h), // the same album
                total("Evergreen", "Always", "Three", 6 * h),
                total("Glass Harbor", "Harbor - Single", "Four", 2 * h),
                total("Velvet Static", "Static", "Five", 1 * h),
                total("Nobody", "", "Six", 50 * h), // no album: no cover to show, but its time counts
                total("Last", "Fifth", "Eight", h / 2),
                total("Paper Moons feat. Evergreen", "Moonlight", "Nine", h / 4),
            ),
        )
        assertThat(v.albums.map { it.album }).containsExactly("Tides", "Always", "Harbor - Single", "Static").inOrder()
        assertThat(v.albums).hasSize(RecordCard.CARD_ALBUMS)
        // a credit counts for its first artist; shown as it was first seen
        assertThat(v.artists).containsExactly("Nobody", "Paper Moons", "Evergreen", "Glass Harbor", "Velvet Static").inOrder()
        assertThat(v.topArtist).isEqualTo("Nobody")
        // the first album's cover is kept from whichever of its spellings has one
        val covered = RecordCard.fromOwn(listOf(total("A", "Tides", "1", 2 * h), total("A", "Tides (Deluxe)", "2", h, art = "https://x/t.jpg")))
        assertThat(covered.albums.single().cover).isEqualTo("https://x/t.jpg")
        // ties: by album name, in UTF-16 order
        val tie = RecordCard.fromOwn(listOf(total("A", "beta", "1", h), total("B", "Alpha", "2", h)))
        assertThat(tie.albums.map { it.album }).containsExactly("Alpha", "beta").inOrder()
    }

    @Test fun `the record at fifty plays of the phone's own, the invitation before, unless it was hidden`() {
        val few = listOf(total("A", "B", "C", h, plays = 49))
        val enough = listOf(total("A", "B", "C", h, plays = 30), total("D", "E", "F", h, plays = 20))
        assertThat(RecordCard.stateOf(few, inviteHidden = false)).isEqualTo(RecordCardState.Invite)
        assertThat(RecordCard.stateOf(few, inviteHidden = true)).isEqualTo(RecordCardState.Hidden)
        assertThat(RecordCard.stateOf(emptyList(), inviteHidden = false)).isEqualTo(RecordCardState.Invite)
        val record = RecordCard.stateOf(enough, inviteHidden = true)
        assertThat(record).isInstanceOf(RecordCardState.Record::class.java)
        record as RecordCardState.Record
        assertThat(record.view.plays).isEqualTo(RecordCard.OWN_FOR_RECORD)
        assertThat(record.albumArt).hasSize(2)
        assertThat(record.artistArt).containsExactly(null, null)
    }

    @Test fun `hours, at least 1 once anything played, and counts with separators`() {
        assertThat(RecordCard.count(48_210)).isEqualTo("48,210")
        assertThat(RecordCard.hours(0)).isEqualTo(0)
        assertThat(RecordCard.hours(60_000)).isEqualTo(1)
        assertThat(RecordCard.hours((1.6 * h).toLong())).isEqualTo(2)
        // half up, as JavaScript's Math.round
        assertThat(RecordCard.hours(5 * h / 2)).isEqualTo(3)
        assertThat(RecordCard.hoursLabel(h)).isEqualTo("1 hour")
        assertThat(RecordCard.hoursLabel(1712 * h)).isEqualTo("1,712 hours")
    }

    @Test fun `since when, in UTC, and the plays`() {
        val v = CardView(listenedMs = h, since = 1_451_606_400_000, plays = 1234, topArtist = null, albums = emptyList(), artists = emptyList())
        assertThat(RecordCard.sinceLine(v)).isEqualTo("since 2016 · 1,234 plays")
        assertThat(RecordCard.sinceLine(v.copy(since = 0, plays = 1))).isEqualTo("1 play")
        // 2015-12-31 23:30 UTC is still 2015, wherever the phone is
        assertThat(RecordCard.sinceLine(v.copy(since = 1_451_604_600_000))).startsWith("since 2015")
    }

    @Test fun `initials for a picture still on its way`() {
        assertThat(RecordCard.initials("Paper Moons")).isEqualTo("PM")
        assertThat(RecordCard.initials("evergreen")).isEqualTo("E")
        assertThat(RecordCard.initials("Sigur Rós Orchestra")).isEqualTo("SR")
        assertThat(RecordCard.initials("  ")).isEqualTo("")
        assertThat(RecordCard.initials("ßeta 🎵band")).isEqualTo("SS🎵")
    }
}
