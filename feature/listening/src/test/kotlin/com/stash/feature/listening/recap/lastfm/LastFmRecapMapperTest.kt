package com.stash.feature.listening.recap.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmScrobble
import com.stash.feature.listening.recap.RecapCard
import com.stash.feature.listening.recap.RecapDeck
import com.stash.feature.listening.recap.lastfm.LastFmFixtures.at
import com.stash.feature.listening.recap.lastfm.LastFmFixtures.input
import org.junit.Test
import java.time.LocalDate

/** Last.fm scrobbles → the Stash recap's RecapData: the numbers, the time rule, matching and art. */
class LastFmRecapMapperTest {

    private val data = LastFmRecapMapper.build(input())

    @Test fun `counts every scrobble of the month in the phone's zone, and only those`() {
        // 30 in September (the Sep 30 23:30 one is October in UTC but September in New York); not August's.
        assertThat(data.totals.plays).isEqualTo(30)
        assertThat(data.totals.songs).isEqualTo(13)
        assertThat(data.days).isEqualTo(LastFmFixtures.september.dayRange(LastFmFixtures.today))
        assertThat(data.hourly[23]).isEqualTo(1)
        assertThat(data.hourly[20]).isEqualTo(5)
    }

    @Test fun `every Last_fm time is estimated - the story says about`() {
        assertThat(data.totals.estimatedPlays).isEqualTo(data.totals.plays)
        assertThat(data.estimated).isTrue()
    }

    @Test fun `song length - the library's, then Last_fm's, then the average of those known`() {
        val songs = data.topSongs.associateBy { it.track.title }
        assertThat(songs.getValue("Taro").listenedMs).isEqualTo(10 * 300_000L)
        assertThat(songs.getValue("Common Blue").listenedMs).isEqualTo(6 * 240_000L)
        // Neither knows Drowning: the average of the two known lengths.
        assertThat(songs.getValue("Drowning").listenedMs).isEqualTo(4 * 270_000L)
        assertThat(data.totals.listenedMs).isEqualTo(10 * 300_000L + 6 * 240_000L + 14 * 270_000L)
    }

    @Test fun `no length known anywhere - three and a half minutes each`() {
        val none = LastFmRecapMapper.build(input(local = emptyList(), durations = emptyMap()))
        assertThat(none.totals.listenedMs).isEqualTo(30 * LastFmRecapMapper.DEFAULT_SONG_MS)
    }

    @Test fun `a library match is a copy of its row, the rest negative in-memory ids`() {
        val taro = data.topSongs.first()
        assertThat(taro.track.title).isEqualTo("Taro")
        assertThat(taro.plays).isEqualTo(10)
        assertThat(taro.track.id).isEqualTo(7L) // the library's row: story music plays its file
        assertThat(taro.track.filePath).isEqualTo("/music/taro.opus")
        val others = data.topSongs.drop(1)
        // Between search's ids (the Int range) and scanned files' (-2^32 and below): never a real
        // row, never a search result, and never taken for a local file (which would never stream).
        assertThat(others.all { it.track.id < Int.MIN_VALUE.toLong() && it.track.id > com.stash.core.model.SCANNED_ID_BASE }).isTrue()
        assertThat(others.none { com.stash.core.model.isScannedTrackId(it.track.id) }).isTrue()
        assertThat(others.map { it.track.id }.distinct()).hasSize(others.size)
        // In-memory songs carry their (estimated) length, so story music starts a third of the way in.
        val songs = data.topSongs.associateBy { it.track.title }
        assertThat(songs.getValue("Common Blue").track.durationMs).isEqualTo(240_000L)
        assertThat(songs.getValue("Drowning").track.durationMs).isEqualTo(270_000L)
    }

    @Test fun `art - Last_fm's image, else the library's, else the artist photo`() {
        val photos = listOf(ArtistImageEntity(artistName = "Slow Joy", imageUrl = "https://photo/slowjoy.jpg"))
        val withPhotos = LastFmRecapMapper.build(input().copy(photos = photos))
        val songs = withPhotos.topSongs.associateBy { it.track.title }
        assertThat(songs.getValue("Common Blue").track.albumArtUrl).isEqualTo(LastFmFixtures.BLUE_IMAGE)
        // Taro: no Last.fm image, the library's own cover file.
        assertThat(songs.getValue("Taro").track.albumArtPath).isEqualTo("/art/taro.jpg")
        assertThat(songs.getValue("Drowning").track.albumArtUrl).isEqualTo("https://photo/slowjoy.jpg")
        // A song with nothing at all: no art (the story's Stash purple).
        assertThat(songs.getValue("One-off 1").track.albumArtUrl).isNull()
        // A library song WITH a Last.fm image takes Last.fm's.
        val imaged = LastFmRecapMapper.build(
            input(scrobbles = LastFmFixtures.scrobbles().map { if (it.title == "Taro") it.copy(image = "https://lfm/taro.jpg") else it }),
        )
        val t = imaged.topSongs.first()
        assertThat(t.track.albumArtUrl).isEqualTo("https://lfm/taro.jpg")
        assertThat(t.track.albumArtPath).isNull()
        assertThat(t.track.id).isEqualTo(7L)
    }

    @Test fun `an artist without a photo shows their most scrobbled song that has art`() {
        // Slow Joy's most scrobbled song has no art; a less played one has Last.fm's.
        val extra = listOf(LastFmScrobble(at(9, 17, 10), "Slow Joy", "Lucky", image = "https://lfm/lucky.jpg"))
        val built = LastFmRecapMapper.build(input(scrobbles = LastFmFixtures.scrobbles() + extra))
        val slowJoy = built.topArtists.first { it.name == "Slow Joy" }
        assertThat(slowJoy.photoUrl).isNull()
        assertThat(slowJoy.artUrl).isEqualTo("https://lfm/lucky.jpg")
    }

    @Test fun `artists, busiest day, on repeat and the top artist's days`() {
        assertThat(data.topArtists.map { it.name }.take(3)).containsExactly("alt-J", "Warpaint", "Slow Joy").inOrder()
        assertThat(data.topArtists.first().plays).isEqualTo(10)
        val sep11 = LocalDate.of(2026, 9, 11).toEpochDay()
        assertThat(data.busiestDay!!.day).isEqualTo(sep11)
        assertThat(data.obsession!!.day).isEqualTo(sep11)
        assertThat(data.obsession!!.plays).isEqualTo(5)
        assertThat(data.obsession!!.track.title).isEqualTo("Taro")
        assertThat(data.topArtistDays).containsExactly(
            sep11, LocalDate.of(2026, 9, 3).toEpochDay(), LocalDate.of(2026, 9, 5).toEpochDay(),
            LocalDate.of(2026, 9, 7).toEpochDay(), LocalDate.of(2026, 9, 9).toEpochDay(), LocalDate.of(2026, 9, 12).toEpochDay(),
        )
        assertThat(data.longestStreak!!.days).isAtLeast(2)
        // Never the #1 under the artist cards (it would give the quiz away).
        assertThat(data.artistTopSongs.values.map { it.track.title }).doesNotContain("Taro")
    }

    @Test fun `what can't be known from one period is left out`() {
        assertThat(data.topSongFirstListen).isNull()
        assertThat(data.newSongs).isEqualTo(0)
        assertThat(data.newFinds).isEmpty()
    }

    @Test fun `the same deck is built from it - no discoveries card, no first-played line`() {
        val cards = RecapDeck.build(data, "Your September on Last.fm")
        assertThat(cards.first()).isInstanceOf(RecapCard.Intro::class.java)
        assertThat(cards.last()).isInstanceOf(RecapCard.Summary::class.java)
        assertThat((cards.last() as RecapCard.Summary).title).isEqualTo("Your September on Last.fm")
        assertThat(cards.filterIsInstance<RecapCard.Minutes>().single().estimated).isTrue()
        assertThat(cards.filterIsInstance<RecapCard.TopSong>().single().firstListen).isNull()
        assertThat(cards.filterIsInstance<RecapCard.OnRepeat>()).hasSize(1)
        assertThat(cards.filterIsInstance<RecapCard.Discoveries>()).isEmpty()
        // The story's music: every cue is one of the recap's songs (never a library write).
        val cues = RecapDeck.cues(cards, data)
        assertThat(cues).hasSize(cards.size)
    }

    @Test fun `fewer than 20 scrobbles - the deck has no story`() {
        val thin = LastFmRecapMapper.build(input(scrobbles = LastFmFixtures.scrobbles().take(10)))
        assertThat(RecapDeck.build(thin, "t")).isEmpty()
    }

    @Test fun `no scrobble in the period - empty data`() {
        val empty = LastFmRecapMapper.build(input(scrobbles = listOf(LastFmScrobble(at(8, 1, 10), "A", "B"))))
        assertThat(empty.totals.plays).isEqualTo(0)
    }

    @Test fun `library matching prefers a downloaded row with a file, and ignores case`() {
        val streamOnly = TrackEntity(id = 3, title = "TARO", artist = "Alt-J", durationMs = 299_000)
        val downloaded = LastFmFixtures.localTaro
        val index = LastFmRecapMapper.localIndex(listOf(streamOnly, downloaded))
        assertThat(index.getValue(LastFmRecapMapper.songKey("taro", "alt-j")).id).isEqualTo(7L)
    }

    @Test fun `song lengths are asked only for songs the library doesn't time, most scrobbled first`() {
        val local = LastFmRecapMapper.localIndex(listOf(LastFmFixtures.localTaro))
        val asks = LastFmRecapMapper.durationAsks(LastFmFixtures.scrobbles(), local, limit = 3)
        assertThat(asks.map { it.title }).containsExactly("Common Blue", "Drowning", "One-off 4").inOrder()
    }
}
