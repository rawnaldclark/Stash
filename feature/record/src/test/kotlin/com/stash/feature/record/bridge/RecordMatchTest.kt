package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.Track
import com.stash.feature.record.mixes.RecordMixSaver
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A mix's songs as this phone's rows ([RecordMatch]): your own row by its id, the rest by the hand-off's matcher with the Spotify
 * id and the cover found, the cover on the queue's row at once (the web's blank queue art, 10-24) and kept on rows with none;
 * Shuffle ([RecordPlayback]); Save as playlist ([RecordMixSaver]).
 */
class RecordMatchTest {
    private fun entity(id: Long, title: String, art: String? = null, file: String? = null) =
        TrackEntity(id = id, title = title, artist = "A", albumArtUrl = art, filePath = file)

    @Test fun `a song goes on the wire with its Spotify id and the cover found`() {
        val w = wireOf(RecordSong("Ivy", "Frank Ocean", "Blonde", sid = "2ZWlPOoWh0626oTaHrnl2a", cover = "https://cdn-images.dzcdn.net/x.jpg"))
        assertThat(w).isEqualTo(WireSong(title = "Ivy", artist = "Frank Ocean", album = "Blonde", spotifyId = "2ZWlPOoWh0626oTaHrnl2a", artwork = listOf("https://cdn-images.dzcdn.net/x.jpg")))
        // the record's own taps carry words only, as before
        assertThat(wireOf(RecordSong("Ivy", "Frank Ocean"))).isEqualTo(WireSong(title = "Ivy", artist = "Frank Ocean"))
    }

    @Test fun `your own row by its id, the rest matched, each row with the cover found when it has none`() = runTest {
        val asked = mutableListOf<List<WireSong>>()
        val kept = mutableListOf<Pair<Long, String>>()
        val rows = mapOf(
            7L to entity(7, "Mine", art = "https://mine/cover.jpg", file = "/m/7.flac"),
            8L to entity(8, "Bare"),
        )
        val match = RecordMatch(
            byId = { rows[it] },
            matchAll = { songs -> asked += songs; songs.map { s -> if (s.title == "Missing") null else entity(100 + s.title.length.toLong(), s.title) } },
            keepCover = { id, url -> kept += id to url },
        )
        val got = match.rows(
            listOf(
                RecordSong("Mine", "A", trackId = 7, cover = "https://found/7.jpg"),
                RecordSong("Bare", "A", trackId = 8, cover = "https://found/8.jpg"),
                RecordSong("Gone since", "A", trackId = 9, sid = "sid9"),
                RecordSong("New", "A", cover = "https://found/new.jpg"),
                RecordSong("Missing", "A"),
            ),
        )
        // the ones without a row of their own asked for, in order, the gone one by its words and Spotify id
        assertThat(asked.single().map { it.title }).containsExactly("Gone since", "New", "Missing").inOrder()
        assertThat(asked.single()[0].spotifyId).isEqualTo("sid9")
        assertThat(got.map { it?.title }).containsExactly("Mine", "Bare", "Gone since", "New", null).inOrder()
        // your cover stays yours; a row with none shows the one found, and keeps it
        assertThat(got.map { it?.albumArtUrl }).containsExactly("https://mine/cover.jpg", "https://found/8.jpg", null, "https://found/new.jpg", null).inOrder()
        assertThat(kept).containsExactly(8L to "https://found/8.jpg", 103L to "https://found/new.jpg")
        assertThat(got[0]!!.filePath).isEqualTo("/m/7.flac")
    }

    @Test fun `a cover that can't be kept still shows`() = runTest {
        val match = RecordMatch(byId = { null }, matchAll = { songs -> songs.map { entity(1, it.title) } }, keepCover = { _, _ -> error("database gone") })
        assertThat(match.rows(listOf(RecordSong("X", "A", cover = "https://c/x.jpg"))).single()!!.albumArtUrl).isEqualTo("https://c/x.jpg")
    }

    @Test fun `Shuffle plays the songs found in a shuffled order from the first, Play from the one asked for`() = runTest {
        val queued = mutableListOf<Triple<List<String>, Int, String>>()
        val playback = RecordPlayback(
            match = { songs -> songs.mapIndexed { i, s -> Track(id = i + 1L, title = s.title, artist = s.artist, filePath = if (s.title == "Here") "/m.flac" else null, dateAdded = 0) } },
            setQueue = { tracks, start, from -> queued += Triple(tracks.map { it.title }, start, from) },
            online = { true },
            toast = {},
            scope = backgroundScope,
            shuffled = { it.reversed() },
        )
        val songs = listOf("One", "Two", "Three", "Four", "Five", "Six").map { RecordSong(it, "A") }
        playback.play(songs, 4, "Your 2019", shuffle = false)
        runCurrent()
        playback.play(songs, 4, "Your 2019", shuffle = true)
        runCurrent()
        assertThat(queued).containsExactly(
            Triple(listOf("One", "Two", "Three", "Four", "Five", "Six"), 4, "Your 2019"),
            Triple(listOf("Six", "Five", "Four", "Three", "Two", "One"), 0, "Your 2019"),
        ).inOrder()
    }

    @Test fun `offline, Shuffle too plays only the songs on the phone`() = runTest {
        val queued = mutableListOf<List<String>>()
        val playback = RecordPlayback(
            match = { songs -> songs.mapIndexed { i, s -> Track(id = i + 1L, title = s.title, artist = s.artist, filePath = if (s.title.startsWith("Here")) "/m$i.flac" else null, dateAdded = 0) } },
            setQueue = { tracks, _, _ -> queued += tracks.map { it.title } },
            online = { false },
            toast = {},
            scope = backgroundScope,
            shuffled = { it.reversed() },
        )
        playback.play(listOf("Here 1", "Away", "Here 2").map { RecordSong(it, "A") }, 0, "x", shuffle = true)
        runCurrent()
        assertThat(queued).containsExactly(listOf("Here 2", "Here 1"))
    }

    @Test fun `Save as playlist makes one playlist of the rows found, each once, named for the mix`() = runTest {
        var made: Pair<String, List<Long>>? = null
        val saver = RecordMixSaver(rows = { songs -> songs.map { s -> if (s.title == "None") null else s.title.length.toLong() } }, create = { name, ids -> made = name to ids; 77L })
        val id = saver.save("Your 2019", listOf("Ab", "None", "Cde", "Fg").map { RecordSong(it, "A") })
        assertThat(id).isEqualTo(77L)
        assertThat(made).isEqualTo("Your 2019" to listOf(2L, 3L))
    }
}
