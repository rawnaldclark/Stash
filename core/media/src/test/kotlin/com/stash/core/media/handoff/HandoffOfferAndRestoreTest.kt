package com.stash.core.media.handoff

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.HandoffRepeat
import com.stash.core.data.weblink.handoff.NowRead
import com.stash.core.data.weblink.handoff.PublishedState
import com.stash.core.data.weblink.handoff.StashNow
import com.stash.core.data.weblink.handoff.StashQueue
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import org.junit.Test

/** Offer selection and extrapolation (spec §2.5, §8.2), the restore's layout (§8.3) and the published documents (§4.2). */
class HandoffOfferAndRestoreTest {
    private fun song(n: Int, durationMs: Long? = 200_000) = WireSong("Song $n", "Artist $n", durationMs = durationMs)

    private fun now(playing: Boolean, position: Long, song: WireSong? = song(1), index: Int = 0, shuffle: Boolean = false, queueId: String = "q_abc") =
        StashNow(playing, position, 1.0, index, queueId, song, shuffle, HandoffRepeat.OFF, "Night drive")

    private fun read(vararg states: PublishedState, serverTime: Long = 1_000_000, own: Long? = null) =
        NowRead("d_phone", serverTime, states.toList(), own)

    // ------------------------------------------------------------------------------------------ offers

    @Test
    fun `a paused browser state is offered at its position`() {
        val o = HandoffOfferPicker.pick(read(PublishedState("d_web", "Chrome on Windows", 990_000, now(false, 151_000))), 0, false, emptyList(), 0)!!
        assertThat(o.deviceName).isEqualTo("Chrome on Windows")
        assertThat(o.positionMs).isEqualTo(151_000)
        assertThat(o.stillPlaying).isFalse()
        assertThat(o.key).isEqualTo("d_web@990000")
    }

    @Test
    fun `a playing state is extrapolated from the server's stamp`() {
        val o = HandoffOfferPicker.pick(read(PublishedState("d_web", "Chrome", 990_000, now(true, 100_000))), 0, false, emptyList(), 0)!!
        assertThat(o.positionMs).isEqualTo(110_000)
        assertThat(o.stillPlaying).isTrue()
        assertThat(o.positionAt(5_000)).isEqualTo(115_000)
    }

    @Test
    fun `past the song's end the published position is offered, as paused`() {
        val o = HandoffOfferPicker.pick(read(PublishedState("d_web", "Chrome", 800_000, now(true, 150_000))), 0, false, emptyList(), 0)!!
        assertThat(o.positionMs).isEqualTo(150_000)
        assertThat(o.stillPlaying).isFalse()
    }

    @Test
    fun `no offer while playing here, when dismissed, older than own playback, over 12 hours, or without a song`() {
        val st = PublishedState("d_web", "Chrome", 990_000, now(false, 1_000))
        assertThat(HandoffOfferPicker.pick(read(st), 0, true, emptyList(), 0)).isNull()
        assertThat(HandoffOfferPicker.pick(read(st), 0, false, listOf("d_web@990000"), 0)).isNull()
        assertThat(HandoffOfferPicker.pick(read(st, own = 995_000), HandoffOfferPicker.ownLastAt(read(st, own = 995_000), 0, 0), false, emptyList(), 0)).isNull()
        assertThat(HandoffOfferPicker.pick(read(st, serverTime = 990_000 + 12 * 3_600_000L), 0, false, emptyList(), 0)).isNull()
        assertThat(HandoffOfferPicker.pick(read(PublishedState("d_web", "Chrome", 990_000, now(false, 0, song = null))), 0, false, emptyList(), 0)).isNull()
    }

    @Test
    fun `the newest of two browsers wins, and own playback is taken from the later of slot and local clock`() {
        val a = PublishedState("d_a", "Firefox", 980_000, now(false, 1_000))
        val b = PublishedState("d_b", "Chrome", 985_000, now(false, 2_000))
        assertThat(HandoffOfferPicker.pick(read(a, b), 0, false, emptyList(), 0)!!.device).isEqualTo("d_b")
        // This phone played at local 5_000 when its clock was 10_000 behind the server: server time 15_000 + …
        val r = read(a, b, serverTime = 1_000_000, own = 900_000)
        assertThat(HandoffOfferPicker.ownLastAt(r, lastPlayedLocal = 982_000 - 10_000, localNow = 1_000_000 - 10_000)).isEqualTo(982_000)
        assertThat(HandoffOfferPicker.pick(r, 982_000, false, emptyList(), 0)!!.device).isEqualTo("d_b")
        assertThat(HandoffOfferPicker.pick(r, 986_000, false, emptyList(), 0)).isNull()
    }

    // ------------------------------------------------------------------------------------------ restore layout

    private val items = (0 until 6).map { song(it) }

    @Test
    fun `unshuffled - the queue as published, starting at the now's index`() {
        val l = HandoffLayout.of(now(false, 0, items[3], index = 3), StashQueue("q_abc", items, 3))!!
        assertThat(l.songs).isEqualTo(items)
        assertThat(l.startSlot).isEqualTo(3)
        assertThat(l.playOrder).isNull()
    }

    @Test
    fun `shuffled - the timeline is the unshuffled order and the play order maps back to it`() {
        // Play order items[0..5]; unshuffled order is items[2], items[0], items[5], items[1], items[4], items[3].
        val original = listOf(2, 0, 5, 1, 4, 3)
        val l = HandoffLayout.of(now(false, 0, items[1], index = 1, shuffle = true), StashQueue("q_abc", items, 1, 0, original))!!
        assertThat(l.songs).isEqualTo(original.map { items[it] })
        assertThat(l.playSequence.map { l.songs[it] }).isEqualTo(items) // plays in the browser's order
        assertThat(l.songs[l.startSlot]).isEqualTo(items[1])
    }

    @Test
    fun `a queue with another id is used when the song is in it, else the song plays alone`() {
        val l = HandoffLayout.of(now(false, 0, items[4], index = 1, queueId = "q_new"), StashQueue("q_old", items, 0))!!
        assertThat(l.startSlot).isEqualTo(4)
        val alone = HandoffLayout.of(now(false, 0, song(99), index = 1, queueId = "q_new"), StashQueue("q_old", items, 0))!!
        assertThat(alone.songs).containsExactly(song(99))
        assertThat(HandoffLayout.of(now(false, 0, song(7)), null)!!.songs).containsExactly(song(7))
    }

    private fun t(n: Int) = Track(id = n.toLong(), title = "Song $n", artist = "Artist $n", dateAdded = 0)

    @Test
    fun `the rest goes around the first block, unplayable songs left out`() {
        val l = HandoffLayout(items, startSlot = 2, playOrder = null)
        // Slot 1 and 5 weren't found here.
        val matched = mapOf(0 to t(0), 2 to t(2), 3 to t(3), 4 to t(4))
        val rest = finishLayout(l, firstSlots = listOf(2, 3), matched = matched)
        assertThat(rest.before).containsExactly(t(0))
        assertThat(rest.after).containsExactly(t(4))
        assertThat(rest.playOrder).isNull()
    }

    @Test
    fun `shuffled rest - the play order is reproduced over the songs that remain`() {
        val original = listOf(2, 0, 5, 1, 4, 3)
        val l = HandoffLayout.of(now(false, 0, items[1], index = 1, shuffle = true), StashQueue("q_abc", items, 1, 0, original))!!
        // Timeline slots: 0→items2, 1→items0, 2→items5, 3→items1, 4→items4, 5→items3. items[4] (slot 4) isn't found here.
        val matched = (0 until 6).filter { it != 4 }.associateWith { t(original[it]) }
        val rest = finishLayout(l, firstSlots = listOf(l.startSlot), matched = matched)
        val timeline = rest.before + listOf(matched.getValue(l.startSlot)) + rest.after
        assertThat(timeline.map { it.id }).containsExactly(2L, 0L, 5L, 1L, 3L).inOrder()
        assertThat(rest.playOrder!!.map { timeline[it].id }).containsExactly(0L, 1L, 2L, 3L, 5L).inOrder()
    }

    // ------------------------------------------------------------------------------------------ published documents

    private val queue = (1..5).map { Track(id = it.toLong(), title = "Song $it", artist = "Artist $it") }

    @Test
    fun `the documents name the current song, its index and the queue id`() {
        val s = PlayerState(currentTrack = queue[2], isPlaying = true, queue = queue, currentIndex = 2)
        val docs = HandoffDocsBuilder.build(s, queue.map(HandoffDocsBuilder::songOf), 42_000, 1.0)!!
        assertThat(docs.now.song!!.title).isEqualTo("Song 3")
        assertThat(docs.now.index).isEqualTo(2)
        assertThat(docs.now.positionMs).isEqualTo(42_000)
        assertThat(docs.now.queueId).isEqualTo(docs.queue.id)
        assertThat(docs.queue.id).startsWith("q_")
        assertThat(docs.queue.original).isNull()
    }

    @Test
    fun `shuffled - original lists the songs in their unshuffled order`() {
        // Play order rows 0..4 sit at timeline slots 3, 0, 4, 1, 2.
        val s = PlayerState(currentTrack = queue[0], queue = queue, currentIndex = 0, isShuffleEnabled = true, shuffleTimelineSlots = listOf(3, 0, 4, 1, 2))
        val docs = HandoffDocsBuilder.build(s, queue.map(HandoffDocsBuilder::songOf), 0, 1.0)!!
        assertThat(docs.queue.original).containsExactly(1, 3, 4, 0, 2).inOrder()
        assertThat(docs.now.shuffle).isTrue()
    }

    @Test
    fun `a local file is marked phoneOnly and counted, a song without words never travels`() {
        val local = Track(id = 9, title = "Demo", artist = "Me", source = MusicSource.LOCAL)
        val nameless = Track(id = 10, title = "", artist = "")
        val q = listOf(queue[0], local, nameless, queue[1])
        val s = PlayerState(currentTrack = q[0], queue = q, currentIndex = 0)
        val docs = HandoffDocsBuilder.build(s, q.map(HandoffDocsBuilder::songOf), 0, 1.0)!!
        assertThat(docs.queue.items.map { it?.title }).containsExactly("Song 1", "Demo", "Song 2").inOrder()
        assertThat(docs.queue.items[1]!!.phoneOnly).isTrue()
        assertThat(docs.now.leftOut).isEqualTo(1)
    }

    @Test
    fun `over 2,000 songs a window starts 100 before the current one`() {
        val big = (1..2_500).map { Track(id = it.toLong(), title = "S$it", artist = "A") }
        val s = PlayerState(currentTrack = big[1_000], queue = big, currentIndex = 1_000)
        val docs = HandoffDocsBuilder.build(s, big.map(HandoffDocsBuilder::songOf), 0, 1.0)!!
        assertThat(docs.queue.items).hasSize(1_600) // 900 … 2,499: at most 2,000 from 100 before the current song
        assertThat(docs.queue.offset).isEqualTo(900)
        assertThat(docs.queue.index).isEqualTo(100)
        assertThat(docs.now.song!!.title).isEqualTo("S1001")
    }
}
