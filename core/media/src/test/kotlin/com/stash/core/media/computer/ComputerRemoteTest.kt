package com.stash.core.media.computer

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.remote.ComputerCommand
import com.stash.core.data.weblink.remote.ComputerItem
import com.stash.core.data.weblink.remote.ComputerLink
import com.stash.core.data.weblink.remote.ComputerLive
import com.stash.core.data.weblink.remote.ComputerState
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComputerRemoteTest {
    private class FakeLink : ComputerLink {
        override val computers = MutableStateFlow<Map<String, ComputerLive>>(emptyMap())
        override val linkedComputers = MutableStateFlow(mapOf(PC to "Stash on DESKTOP"))
        val sent = mutableListOf<ComputerCommand>()
        override fun sendToComputer(device: String, command: ComputerCommand) {
            sent += command
        }
    }

    private var now = 100_000L
    private val link = FakeLink()
    private val phone = MutableStateFlow(PlayerState())
    /** This phone's library, by title; each lookup counted. */
    private val library = mutableMapOf<String, Track>()
    private val lookups = mutableListOf<String>()
    private val remote = ComputerRemote(link, CoroutineScope(UnconfinedTestDispatcher()), { now }, { s ->
        lookups += s.title
        library[s.title]
    }).also { it.watchPhone(phone) }

    private fun song(title: String) = WireSong(title, "Frank Ocean")

    private fun says(playing: Boolean, sid: String = "sessionAAAA", titles: List<String> = listOf("Pink + White", "Nights", "Solo")) {
        val items = titles.mapIndexed { i, t -> ComputerItem("q${i + 1}", song(t)) }
        val st = ComputerState(
            sid = sid, playing = playing, loading = false, positionMs = 10_000, durationMs = 184_000, volume = 0.5,
            song = items.first().song, iid = "q1", at = 2, length = 10, items = items, shuffle = false, repeat = "off", from = "Blonde",
        )
        link.computers.update { it + (PC to ComputerLive(PC, "Stash on DESKTOP", st, now)) }
    }

    private fun track(id: Long, title: String, local: Boolean = false) = Track(
        id = id, title = title, artist = "Frank Ocean",
        source = if (local) MusicSource.LOCAL else MusicSource.YOUTUBE,
        youtubeId = if (local) null else "yt$id".padEnd(11, 'x'),
    )

    @Test
    fun `a computer that plays while this phone doesn't is taken up, and shows as the player`() {
        says(playing = true)
        assertThat(remote.current()?.device).isEqualTo(PC)
        val view = remote.view.value!!
        assertThat(view.currentTrack?.title).isEqualTo("Pink + White")
        assertThat(view.queue.map { it.title }).containsExactly("Pink + White", "Nights", "Solo").inOrder()
        assertThat(view.queueSongsBefore).isEqualTo(2)
        assertThat(view.queueSongsAfter).isEqualTo(5)
        assertThat(view.source).isEqualTo(PlaybackSource.Handoff("Blonde"))
        // A song described by the computer never names a library row.
        assertThat(view.queue.all { it.id < 0 }).isTrue()
    }

    @Test
    fun `while this phone plays nothing is taken up, and when it starts to play it stops being a remote`() {
        phone.value = PlayerState(currentTrack = track(1, "Here"), isPlaying = true, playWhenReady = true)
        says(playing = true)
        assertThat(remote.current()).isNull()

        phone.value = PlayerState()
        assertThat(remote.current()?.device).isEqualTo(PC)
        phone.value = PlayerState(isPlaying = true, playWhenReady = true)
        assertThat(remote.current()).isNull()
        assertThat(remote.view.value).isNull()
    }

    @Test
    fun `a paused computer is not taken up by itself, and a computer gone quiet for a minute is gone`() {
        says(playing = false)
        assertThat(remote.current()).isNull()
        says(playing = true)
        assertThat(remote.current()).isNotNull()
        now += ComputerRemote.STALE_MS
        assertThat(remote.current()).isNull()
        assertThat(remote.play()).isFalse()
    }

    @Test
    fun `a tapped list goes to the computer from the tapped song, without the songs only this phone has`() {
        says(playing = true)
        val list = listOf(track(1, "One"), track(2, "Two"), track(3, "Mine", local = true), track(4, "Four"))
        assertThat(remote.setQueue(list, 1, PlaybackSource.Playlist(5, "Road"))).isTrue()
        val start = link.sent.single() as ComputerCommand.Start
        assertThat(start.songs.map { it.title }).containsExactly("Two", "Four").inOrder()
        assertThat(start.at).isEqualTo(0)
        assertThat(start.play).isTrue()
        assertThat(start.from).isEqualTo("Road")
        // A list of nothing but this phone's files plays here.
        assertThat(remote.setQueue(listOf(track(9, "Mine", local = true)), 0, PlaybackSource.Unknown)).isFalse()
    }

    @Test
    fun `a song this phone sent shows as its own row when the computer names it`() {
        says(playing = true)
        remote.add(listOf(track(42, "Nights")), next = true)
        // The computer's next state, after the addition.
        now += 1_000
        says(playing = true)
        assertThat(remote.view.value!!.queue[1].id).isEqualTo(42)
    }

    @Test
    fun `a song the computer plays that this library holds shows as its row, each song looked for once`() {
        library["Nights"] = track(7, "Nights")
        says(playing = true)
        val view = remote.view.value!!
        assertThat(view.queue[1].id).isEqualTo(7)
        assertThat(view.currentTrack!!.id).isLessThan(0)
        now += 1_000
        says(playing = true)
        assertThat(lookups).containsExactly("Pink + White", "Nights", "Solo")
    }

    @Test
    fun `the queue's rows map to the computer's items, a move to its place in the whole queue`() {
        says(playing = true)
        remote.removeAt(2)
        remote.move(from = 2, to = 1)
        remote.skipTo(1)
        assertThat(link.sent).containsExactly(
            ComputerCommand.Remove("q3"),
            ComputerCommand.Move("q3", 3),
            ComputerCommand.SkipTo("q2"),
        ).inOrder()
        assertThat(remote.removeAt(9)).isFalse()
    }

    @Test
    fun `Play next of many keeps their order, a long list going in chunks`() {
        says(playing = true)
        val many = (1..150L).map { track(it, "Song $it") }
        remote.add(many, next = true)
        val adds = link.sent.map { it as ComputerCommand.Add }
        assertThat(adds.map { it.songs.size }).containsExactly(50, 100).inOrder()
        assertThat(adds.first().songs.first().title).isEqualTo("Song 101")
        assertThat(adds.all { it.next }).isTrue()
    }

    @Test
    fun `This phone pauses the computer and doesn't take up the same session again`() {
        says(playing = true)
        val was = remote.leave()!!
        assertThat(link.sent).containsExactly(ComputerCommand.Pause)
        // What it played carries on here, from where it was.
        assertThat(was.songs.map { it.title }).containsExactly("Pink + White", "Nights", "Solo").inOrder()
        assertThat(was.positionMs).isEqualTo(10_000)
        assertThat(was.from).isEqualTo("Blonde")
        assertThat(remote.current()).isNull()
        says(playing = true)
        assertThat(remote.current()).isNull()
        // A new session there (Stash opened again) is taken up.
        says(playing = true, sid = "sessionBBBB")
        assertThat(remote.current()?.device).isEqualTo(PC)
    }

    @Test
    fun `picking the computer moves this phone's queue there from where it is`() {
        says(playing = false)
        val queue = listOf(track(1, "One"), track(2, "Two"))
        assertThat(remote.pick(PC, queue, 1, 30_000, playing = true, source = PlaybackSource.Search)).isTrue()
        val start = link.sent.single() as ComputerCommand.Start
        assertThat(start.songs.map { it.title }).containsExactly("Two")
        assertThat(start.positionMs).isEqualTo(30_000)
        assertThat(remote.current()?.device).isEqualTo(PC)
    }

    private companion object {
        const val PC = "d_computer01"
    }
}
