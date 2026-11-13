package com.stash.core.media.streaming

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SONG_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.AddonStreamCheck.Why
import com.stash.data.download.lossless.LosslessSourceHealthGate
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player's check of an add-on's stream, as a decision (the brief's table) and as the bookkeeping
 * around it: what a refusal sets aside, what it says in the log, and what the player reads back.
 * A real add-on (2026-11-13) said "flac, Lossless, 16/44.1" for every song and served a 120 s AAC,
 * a 67 s video and a 32 s MP3: those three are the first rows below.
 */
@RunWith(RobolectricTestRunner::class) // MediaItem extras and URIs
class AddonStreamCheckTest {

    private val song = 240_000L // the library's length for the song: 4 minutes

    private fun verdict(stated: Boolean, mimes: List<String?>, streamMs: Long?, songMs: Long = song) =
        AddonStreamCheck.verdict(stated, mimes, streamMs, songMs)

    // ------------------------------------------------------------------------------------- the decision

    @Test fun `the three prank files of the add-on that called everything lossless are each refused`() {
        assertThat(verdict(stated = true, listOf("audio/mp4a-latm"), 120_000)).isEqualTo(Why.LOSSY)
        assertThat(verdict(stated = true, listOf("video/avc", "audio/mp4a-latm"), 67_000)).isEqualTo(Why.VIDEO)
        // The 32 s MP3 is lossy before it is short: one rule, the first that holds, is the reason.
        assertThat(verdict(stated = true, listOf("audio/mpeg"), 32_000)).isEqualTo(Why.LOSSY)
    }

    @Test fun `stated lossless and decoded as any lossy codec is refused`() {
        val lossy = listOf(
            "audio/mp4a-latm", "audio/aac", "audio/mpeg", "audio/mpeg-L2", "audio/opus", "audio/vorbis",
            "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4", "audio/3gpp", "audio/amr-wb", "audio/vnd.dts",
        )
        for (mime in lossy) assertThat(verdict(stated = true, listOf(mime), song)).isEqualTo(Why.LOSSY)
    }

    @Test fun `stated lossless and decoded as FLAC, ALAC or PCM plays, whichever of them was named`() {
        for (mime in listOf("audio/flac", "audio/alac", "audio/raw", "audio/wav")) {
            assertThat(verdict(stated = true, listOf(mime), song)).isNull()
        }
        // The add-on said ALAC (or PCM) and the file is FLAC: still lossless, still plays.
        assertThat(AddonStreamCheck.statesLossless("alac")).isTrue()
        assertThat(AddonStreamCheck.statesLossless("wav")).isTrue()
        assertThat(AddonStreamCheck.statesLossless("FLAC")).isTrue()
    }

    @Test fun `a stream called lossy is never refused for its codec`() {
        // The lossy pass, or an honest add-on: an MP3 it called AAC is still a lossy file it called lossy.
        for (codec in listOf("aac", "mp3", "opus", "vorbis", "eac3", null)) assertThat(AddonStreamCheck.statesLossless(codec)).isFalse()
        assertThat(verdict(stated = false, listOf("audio/mpeg"), song)).isNull()
        assertThat(verdict(stated = false, listOf("audio/mp4a-latm"), song)).isNull()
    }

    @Test fun `a manifest offering lossless audio with a lossy fallback beside it is honest - review-888 M2`() {
        // A DASH or HLS add-on: FLAC or ALAC, plus AAC (or Atmos) for slow networks.
        assertThat(verdict(stated = true, listOf("audio/flac", "audio/mp4a-latm"), song)).isNull()
        assertThat(verdict(stated = true, listOf("audio/mp4a-latm", "audio/alac"), song)).isNull()
        assertThat(verdict(stated = true, listOf("audio/flac", "audio/eac3-joc"), song)).isNull()
        assertThat(verdict(stated = true, listOf("audio/raw", "audio/mpeg"), song)).isNull()
        // Every audio format lossy is still a lie, however many there are.
        assertThat(verdict(stated = true, listOf("audio/mp4a-latm", "audio/eac3-joc"), song)).isEqualTo(Why.LOSSY)
        assertThat(verdict(stated = true, listOf("audio/mpeg", null, "audio/x-something-new"), song)).isEqualTo(Why.LOSSY)
        // R2-n5: media3 names DTS-HD High Resolution (lossy) and Master Audio alike, so it is no proof of lossless
        // beside a lossy format; alone it is not refused (it isn't named lossy either).
        assertThat(verdict(stated = true, listOf("audio/mp4a-latm", "audio/vnd.dts.hd"), song)).isEqualTo(Why.LOSSY)
        assertThat(verdict(stated = true, listOf("audio/vnd.dts.hd"), song)).isNull()
    }

    @Test fun `a video track is refused whatever the add-on called it`() {
        assertThat(verdict(stated = false, listOf("audio/mp4a-latm", "video/avc"), song)).isEqualTo(Why.VIDEO)
        assertThat(verdict(stated = true, listOf("video/hevc", "audio/flac"), song)).isEqualTo(Why.VIDEO)
        assertThat(verdict(stated = false, listOf("video/avc"), null)).isEqualTo(Why.VIDEO)
    }

    @Test fun `a cover picture stored as a still-image track is not a video - review-888 m8`() {
        assertThat(verdict(stated = true, listOf("audio/flac", "video/mjpeg"), song)).isNull()
        assertThat(verdict(stated = false, listOf("audio/mp4a-latm", "image/png"), song)).isNull()
        // Its audio is still judged.
        assertThat(verdict(stated = true, listOf("audio/mp4a-latm", "video/mjpeg"), song)).isEqualTo(Why.LOSSY)
    }

    @Test fun `a stub far shorter than the song is refused by the downloads' own rule`() {
        // At most 35 s, and under half the song: both.
        assertThat(verdict(stated = true, listOf("audio/flac"), 10_000)).isEqualTo(Why.LENGTH)
        assertThat(verdict(stated = false, listOf("audio/mpeg"), 30_000)).isEqualTo(Why.LENGTH)
        assertThat(verdict(stated = true, listOf("audio/flac"), 35_000)).isEqualTo(Why.LENGTH)
    }

    @Test fun `a stream within the rule plays`() {
        // Over 35 s: never a stub, however short beside the song (the rule's own bound).
        assertThat(verdict(stated = true, listOf("audio/flac"), 36_000)).isNull()
        // Short, but not under half the song: a short song.
        assertThat(verdict(stated = true, listOf("audio/flac"), 30_000, songMs = 50_000)).isNull()
        assertThat(verdict(stated = true, listOf("audio/flac"), song)).isNull()
    }

    @Test fun `with the song's length unknown, or the stream's, the length is not checked`() {
        assertThat(verdict(stated = true, listOf("audio/flac"), 10_000, songMs = 0)).isNull()
        assertThat(verdict(stated = true, listOf("audio/flac"), null)).isNull()
        assertThat(verdict(stated = true, listOf("audio/flac"), 0)).isNull()
        // The other rules still hold.
        assertThat(verdict(stated = true, listOf("audio/mpeg"), null, songMs = 0)).isEqualTo(Why.LOSSY)
    }

    @Test fun `a codec this app cannot name is no reason, and neither are missing formats`() {
        assertThat(verdict(stated = true, listOf("audio/x-something-new"), song)).isNull()
        assertThat(verdict(stated = true, listOf(null), song)).isNull()
        assertThat(verdict(stated = true, emptyList(), song)).isNull()
    }

    // ------------------------------------------------------------------------------------- a refusal

    private val gate = LosslessSourceHealthGate()
    private var clock = 1_000L
    private val check = AddonStreamCheck(gate) { clock }
    private val lines = mutableListOf<String>()

    private fun claim(origin: String = "addon:liar", trackId: Long = 42L, stated: Boolean = true) =
        AddonStreamCheck.Claim(origin, trackId, mediaId = trackId.toString(), statedLossless = stated, songMs = song)

    @Test fun `a refusal sets the add-on aside as a wrong file, says so in one line without the host, and is told`() = runTest(UnconfinedTestDispatcher()) {
        check.log = { lines += it }
        val told = async { check.refusals.first() }

        val why = check.judge(claim(), listOf("audio/mpeg"), 240_000)

        assertThat(why).isEqualTo(Why.LOSSY)
        assertThat(gate.isDegraded("addon:liar")).isTrue()
        assertThat(check.isSetAside("addon:liar")).isTrue()
        assertThat(gate.setAside.first()["addon:liar"]?.wrongFile).isTrue()
        assertThat(lines).containsExactly("liar refused for 42: lossy")
        assertThat(told.await()).isEqualTo(AddonStreamCheck.Refused("addon:liar", 42L, "42", Why.LOSSY))
    }

    @Test fun `the log line names the rule for each refusal and never a URL`() {
        check.log = { lines += it }
        check.judge(claim(trackId = 1), listOf("video/avc", "audio/mp4a-latm"), 67_000)
        check.judge(claim(trackId = 2, stated = false), listOf("audio/mpeg"), 10_000)
        assertThat(lines).containsExactly("liar refused for 1: video", "liar refused for 2: length").inOrder()
        assertThat(lines.none { "http" in it || "/" in it }).isTrue()
    }

    @Test fun `a stream that passes sets nothing aside and says nothing`() {
        check.log = { lines += it }
        assertThat(check.judge(claim(), listOf("audio/flac"), 240_000)).isNull()
        assertThat(check.isSetAside("addon:liar")).isFalse()
        assertThat(check.takeRefusal(42L)).isFalse()
        assertThat(lines).isEmpty()
    }

    @Test fun `the player's error for a refused song finds the refusal once, while it is fresh`() {
        check.log = {}
        check.judge(claim(trackId = 7), listOf("audio/mpeg"), 240_000)
        assertThat(check.takeRefusal(8L)).isFalse()
        assertThat(check.takeRefusal(7L)).isTrue()
        assertThat(check.takeRefusal(7L)).isFalse() // once

        check.judge(claim(trackId = 9), listOf("audio/mpeg"), 240_000)
        clock += AddonStreamCheck.REFUSAL_FRESH_MS + 1
        assertThat(check.takeRefusal(9L)).isFalse() // an error this late is not the refusal's
    }

    @Test fun `only an add-on's origin is ever set aside by it`() {
        for (origin in listOf(null, "qbdlx", "tidal", "youtube", "lossy:addon:liar")) assertThat(check.isSetAside(origin)).isFalse()
    }

    // ------------------------------------------------------------------------------------- whose link a song opened

    @Test fun `a placeholder's opened link is noted by song, another source's replaces it, and forget drops it`() {
        check.opened(5L, "addon:radio")
        check.opened(6L, "qbdlx")
        assertThat(check.openedFrom(5L)).isEqualTo("addon:radio")
        assertThat(check.openedFrom(6L)).isNull()
        check.opened(5L, "tidal") // resolved again from another source
        assertThat(check.openedFrom(5L)).isNull()
        check.opened(5L, "addon:radio")
        check.forget(5L)
        assertThat(check.openedFrom(5L)).isNull()
    }

    // ------------------------------------------------------------------------------------- what each item claims

    private fun item(extras: Bundle.() -> Unit, uri: String = "https://media.example/a.flac", mediaId: String = "42") = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply(extras)).build())
        .build()

    @Test fun `an item resolved to an add-on's URL claims what its extras say`() {
        val claim = AddonStreamCheck.claimOf(item({
            putLong(EXTRA_TRACK_ID, 42L)
            putString(EXTRA_STREAM_ORIGIN, "addon:liar")
            putString(EXTRA_STREAM_CODEC, "flac")
            putLong(EXTRA_TRACK_DURATION_MS, 200_000L)
        }))!!
        assertThat(claim).isEqualTo(AddonStreamCheck.Claim("addon:liar", 42L, "42", statedLossless = true, songMs = 200_000L))
        assertThat(claim.addonId).isEqualTo("liar")
        // Any other source's item is not the check's business.
        assertThat(AddonStreamCheck.claimOf(item({ putString(EXTRA_STREAM_ORIGIN, "qbdlx") }))).isNull()
        assertThat(AddonStreamCheck.claimOf(item({}))).isNull()
    }

    @Test fun `a placeholder's claim is the stream it opened, with the song's id and length from its own URI`() {
        val placeholder = item({}, uri = stashResolveUri(77L, title = "T", artist = "A", durationMs = 180_000L).toString(), mediaId = "77")
        val opened = StreamUrl(url = "https://media.example/x", expiresAtMs = Long.MAX_VALUE, codec = "aac", origin = "addon:lossy-pass")
        assertThat(AddonStreamCheck.claimOf(opened, placeholder))
            .isEqualTo(AddonStreamCheck.Claim("addon:lossy-pass", 77L, "77", statedLossless = false, songMs = 180_000L))
        assertThat(AddonStreamCheck.claimOf(opened.copy(origin = "tidal"), placeholder)).isNull()
    }

    @Test fun `with no length of its own in the queue, a claim takes the one the stream's source knew - #888 phone run 3b`() {
        // A Search tap, or a radio song, whose result had no length: the library row's or the add-on's own matched
        // item's, as the rung stamped it. The queue's own length still comes first.
        val tapped = AddonStreamCheck.claimOf(item({
            putLong(EXTRA_TRACK_ID, 42L)
            putString(EXTRA_STREAM_ORIGIN, "addon:liar")
            putString(EXTRA_STREAM_CODEC, "flac")
            putLong(EXTRA_STREAM_SONG_MS, 222_640L)
        }))!!
        assertThat(tapped.songMs).isEqualTo(222_640L)
        val known = AddonStreamCheck.claimOf(item({
            putLong(EXTRA_TRACK_ID, 42L)
            putString(EXTRA_STREAM_ORIGIN, "addon:liar")
            putLong(EXTRA_TRACK_DURATION_MS, 200_000L)
            putLong(EXTRA_STREAM_SONG_MS, 222_640L)
        }))!!
        assertThat(known.songMs).isEqualTo(200_000L)

        val noLength = item({}, uri = stashResolveUri(78L, title = "T", artist = "A").toString(), mediaId = "78")
        val opened = StreamUrl(url = "https://media.example/x", expiresAtMs = Long.MAX_VALUE, codec = "flac", origin = "addon:liar", songMs = 222_640L)
        assertThat(AddonStreamCheck.claimOf(opened, noLength)!!.songMs).isEqualTo(222_640L)
        // And so the stub is refused, which it wasn't with no length.
        assertThat(verdict(stated = true, listOf("audio/flac"), 10_000, songMs = AddonStreamCheck.claimOf(opened, noLength)!!.songMs)).isEqualTo(Why.LENGTH)
        assertThat(AddonStreamCheck.claimOf(opened.copy(songMs = null), noLength)!!.songMs).isEqualTo(0L)
    }

    // ------------------------------------------------------------------------------------- a placeholder resolved again

    @Test fun `a placeholder resolved again is another URI to the player, with every resolve input kept`() {
        val first = stashResolveUri(77L, youtubeId = "yt1", title = "T", artist = "A", album = "Al", durationMs = 180_000L, isrc = "US1")
        val again = first.resolvedAgain()
        assertThat(again).isNotEqualTo(first)
        assertThat(again.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
        assertThat(again.lastPathSegment).isEqualTo("77")
        for (name in listOf("yt", "t", "a", "al", "d", "isrc")) assertThat(again.getQueryParameter(name)).isEqualTo(first.getQueryParameter(name))
        // And again: a URI it hasn't been either.
        val twice = again.resolvedAgain()
        assertThat(twice).isNotEqualTo(again)
        assertThat(twice.getQueryParameters("again")).hasSize(1)
        assertThat(Uri.parse(twice.toString()).getQueryParameter("d")).isEqualTo("180000")
        assertThat(again.isPutBack()).isTrue()
        assertThat(first.isPutBack()).isFalse()
    }

    @Test fun `the queue's placeholder put back twice is two URIs - review-888 M1`() {
        // A slot holding a URL is put back as the queue's placeholder for its song. Under resolve-first the
        // slot shows its source's manifest, not the source's own URI, which may be that placeholder put back
        // once already: "one more" than the queue's placeholder would be that same URI, and the source is kept.
        val queued = stashResolveUri(78L, title = "T", artist = "A", durationMs = 180_000L)
        val once = queued.resolvedAgain()
        val twice = queued.resolvedAgain()
        assertThat(twice).isNotEqualTo(once)
        assertThat(twice.isPutBack()).isTrue()
    }
}
