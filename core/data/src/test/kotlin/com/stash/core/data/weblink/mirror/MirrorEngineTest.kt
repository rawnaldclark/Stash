package com.stash.core.data.weblink.mirror

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.FakeBrowser
import com.stash.core.data.weblink.FakeSyncServer
import com.stash.core.data.weblink.InMemoryWebLinkStore
import com.stash.core.data.weblink.PairingSession
import com.stash.core.data.weblink.PairingState
import com.stash.core.data.weblink.SyncErrorCode
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.data.weblink.merge.SyncHashes
import com.stash.core.model.weblink.Hlc
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's mirror against a scripted browser (sync-v1 §5.2–5.4, §7): each first-merge choice, LWW likes with tombstones,
 * grow-only plays and both clears, three-way playlists with edits on both sides, read-only playlists, compaction (never over a
 * gap), a key change, and offline then online.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MirrorEngineTest {
    private val server = FakeSyncServer()
    private val store = InMemoryWebLinkStore()
    private val repo = WebLinkRepository(server, store)
    private var clock = 3_000_000L
    private val session = PairingSession(server, store, repo, phoneName = { "Pixel 5" }, now = { clock }, sleep = { clock += it })
    private val lib = FakeMirrorLibrary()
    private val records = InMemoryMirrorStore()
    private var minted = 0
    private val engine = MirrorEngine(server, store, repo, records, lib, { clock }, { "m_Phone%011d".format(++minted) })
    private lateinit var web: WebSide
    private val me: String get() = store.id!!.deviceId

    private suspend fun link(): FakeBrowser {
        val b = FakeBrowser(server, name = "Chrome on Windows")
        val link = b.openCode()
        server.onAnswered = { slot ->
            b.readAnswer(slot)
            b.reply()
        }
        session.open(link)
        assertThat(session.confirm()).isEqualTo(PairingState.Linked("Chrome on Windows"))
        repo.refresh()
        web = WebSide(server, b)
        return b
    }

    private suspend fun run() = engine.sync().also { clock += 1_000 }

    private fun likeOps(device: String) = web.ops(device).filterIsInstance<MirrorOp.Like>()

    // ------------------------------------------------------------------------------------------------ likes, first merge

    /** The browser turned likes on and shared its own (B, C); the phone holds A, B. */
    private suspend fun browserTurnsLikesOn(dir: Dir = Dir.BOTH) {
        lib.like(song("A"))
        lib.like(song("B"))
        web.configure(likes = dir)
        val since = web.head
        web.post(MirrorOp.Like(song("B"), true, web.at()), MirrorOp.Like(song("C"), true, web.at()), MirrorOp.Joined(Kind.LIKES, since))
    }

    @Test fun `both sides hold likes, the phone asks, and Combine keeps both everywhere`() = runTest {
        link()
        browserTurnsLikesOn()
        assertThat(run()).isEqualTo(MirrorRun.Done)
        val q = engine.status.value.question!!
        assertThat(listOf(q.here, q.there, q.both, q.combined, if (q.oneWay) 1 else 0)).containsExactly(2, 2, 1, 3, 0).inOrder()
        assertThat(lib.likedTitles()).containsExactly("A", "B") // nothing changes before the answer

        assertThat(engine.answer(FirstMergeChoice.COMBINE)).isEqualTo(MirrorRun.Done)
        assertThat(lib.likedTitles()).containsExactly("A", "B", "C")
        // The phone sends what the space lacks (A) and its `joined` mark.
        assertThat(likeOps(me).map { it.s.title to it.on }).containsExactly("A" to true)
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Joined>().single().kind).isEqualTo(Kind.LIKES)
        assertThat(engine.status.value.question).isNull()
    }

    @Test fun `Use the browser's, the phone's extras go, except a Spotify like it can't undo, which isn't sent back`() = runTest {
        link()
        lib.like(song("S"), external = true)
        browserTurnsLikesOn()
        run()
        engine.answer(FirstMergeChoice.THEIRS)
        assertThat(lib.likedTitles()).containsExactly("B", "C", "S")
        assertThat(likeOps(me)).isEmpty()
        run()
        assertThat(likeOps(me)).isEmpty()
    }

    @Test fun `Use this phone's, the browser's extras get tombstones`() = runTest {
        link()
        browserTurnsLikesOn()
        run()
        engine.answer(FirstMergeChoice.MINE)
        assertThat(lib.likedTitles()).containsExactly("A", "B")
        assertThat(likeOps(me).map { it.s.title to it.on }).containsExactly("A" to true, "C" to false)
    }

    @Test fun `web to phone only offers Add or Replace, never Use this phone's`() = runTest {
        link()
        browserTurnsLikesOn(Dir.TO_PHONE)
        run()
        assertThat(engine.status.value.question!!.oneWay).isTrue()
        engine.answer(FirstMergeChoice.MINE)
        assertThat(engine.status.value.question).isNotNull() // not an answer one-way
        engine.answer(FirstMergeChoice.THEIRS)
        assertThat(lib.likedTitles()).containsExactly("B", "C")
        assertThat(likeOps(me)).isEmpty() // the phone only receives
    }

    @Test fun `the phone waits for the browser's joined mark before it compares`() = runTest {
        link()
        lib.like(song("A"))
        web.configure(likes = Dir.BOTH)
        run()
        assertThat(engine.status.value.waiting).isTrue()
        assertThat(engine.status.value.question).isNull()
        assertThat(web.ops(me)).isEmpty() // nothing of that kind is sent while it waits
        web.post(MirrorOp.Joined(Kind.LIKES, web.config()!!.likes.since)) // the browser had no likes
        run()
        assertThat(engine.status.value.waiting).isFalse()
        assertThat(likeOps(me).map { it.s.title }).containsExactly("A") // nothing there: combined without asking
    }

    @Test fun `the phone turning likes on joins at once and sends everything`() = runTest {
        link()
        lib.like(song("A"))
        engine.configure(MirrorChange(dirs = mapOf(Kind.LIKES to Dir.BOTH)))
        val cfg = web.config()!!
        assertThat(cfg.likes.by).isEqualTo(me)
        assertThat(likeOps(me).map { it.s.title }).containsExactly("A")
    }

    // ------------------------------------------------------------------------------------------------ likes, LWW

    private suspend fun likesMirroring() {
        browserTurnsLikesOn()
        run()
        engine.answer(FirstMergeChoice.COMBINE)
    }

    @Test fun `last writer wins, an unlike from the web goes, a stale like doesn't come back, a phone like offline goes later`() = runTest {
        link()
        likesMirroring()
        web.wall = server.serverNow + 60_000
        web.post(MirrorOp.Like(song("A"), false, web.at()))
        run()
        assertThat(lib.likedTitles()).containsExactly("B", "C")
        // A like of A stamped before the unlike (a device with an old clock): ignored.
        web.post(MirrorOp.Like(song("A"), true, Hlc(1_000, 0, web.id)))
        run()
        assertThat(lib.likedTitles()).doesNotContain("A")

        // Offline: the like waits in the diff, then goes.
        lib.like(song("D"))
        server.failNext("postLog", SyncResult.Error(503, "unavailable"))
        assertThat(run()).isInstanceOf(MirrorRun.Retry::class.java)
        assertThat(likeOps(me).map { it.s.title }).doesNotContain("D")
        assertThat(run()).isEqualTo(MirrorRun.Done)
        assertThat(likeOps(me).last().let { it.s.title to it.on }).isEqualTo("D" to true)
    }

    @Test fun `a phone edit not sent yet wins over a remote op read in the same run`() = runTest {
        link()
        likesMirroring()
        lib.unlike(song("B")) // here, not sent
        web.post(MirrorOp.Like(song("B"), true, web.at())) // a browser re-like, read in the same run
        run()
        assertThat(lib.likedTitles()).doesNotContain("B")
        assertThat(likeOps(me).last().let { it.s.title to it.on }).isEqualTo("B" to false)
    }

    @Test fun `a stamp more than 10 minutes past its row is skipped`() = runTest {
        link()
        likesMirroring()
        web.post(MirrorOp.Like(song("Z"), true, Hlc(server.serverNow + 3_600_000, 0, web.id)))
        run()
        assertThat(lib.likedTitles()).doesNotContain("Z")
    }

    // ------------------------------------------------------------------------------------------------ plays

    @Test fun `plays both ways, web plays come in with their origin, the phone's go out, clears drop only what they say`() = runTest {
        link()
        lib.plays += PlayRec(song("Mine"), 1_000)
        web.configure(plays = Dir.BOTH)
        val since = web.head
        web.post(MirrorOp.Play(song("Web 1"), 2_000), MirrorOp.Play(song("Web 2"), 3_000), MirrorOp.Joined(Kind.PLAYS, since))
        run()
        assertThat(lib.plays.filter { it.origin == web.id }.map { it.s.title }).containsExactly("Web 1", "Web 2")
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Play>().map { it.s.title }).containsExactly("Mine")

        // A play from the web isn't sent back; a new one of the phone's goes.
        lib.plays += PlayRec(song("Mine 2"), 4_000)
        run()
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Play>().map { it.s.title }).containsExactly("Mine", "Mine 2")

        // "Only here" on the web: drops only the web's own plays on the phone, before then.
        web.post(MirrorOp.ClearPlays(2_500, web.at()))
        run()
        assertThat(lib.plays.map { it.s.title }).containsExactly("Mine", "Web 2", "Mine 2")
        // "Also on Pixel 5": every play before then.
        web.post(MirrorOp.ClearPlays(3_500, web.at(), all = true))
        run()
        assertThat(lib.plays.map { it.s.title }).containsExactly("Mine 2")
        // An older remote play doesn't come back after that clear.
        web.post(MirrorOp.Play(song("Old"), 3_000))
        run()
        assertThat(lib.plays.map { it.s.title }).containsExactly("Mine 2")
    }

    @Test fun `only the newest 5,000 of the phone's own plays go on a first merge`() = runTest {
        link()
        repeat(5_010) { lib.plays += PlayRec(song("P$it"), 10_000L + it) }
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYS to Dir.TO_WEB)))
        val sent = web.ops(me).filterIsInstance<MirrorOp.Play>()
        assertThat(sent).hasSize(5_000)
        assertThat(sent.minOf { it.playedAt }).isEqualTo(10_010L)
    }

    // ------------------------------------------------------------------------------------------------ playlists

    @Test fun `a playlist edited on both sides keeps both edits`() = runTest {
        link()
        val drive = lib.addPlaylist("Night drive", listOf(song("1"), song("2"), song("3")))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.BOTH), add = listOf(drive)))
        val mid = web.config()!!.ids.single()
        val first = web.ops(me).filterIsInstance<MirrorOp.Pl>().single()
        assertThat(first.items!!.map { it.title }).containsExactly("1", "2", "3").inOrder()
        assertThat(first.parent).isNull()

        // The web adds X after 1 (from the phone's version); the phone removes 3 meanwhile.
        val h = SyncHashes.playlistHash("Night drive", first.items, null)
        web.post(MirrorOp.Pl(mid, web.at(), h, "Night drive", listOf(song("1"), song("X"), song("2"), song("3"))))
        lib.playlists.getValue(drive).items = listOf(song("1"), song("2"))
        run()
        assertThat(lib.playlists.getValue(drive).items.map { it.title }).containsExactly("1", "X", "2").inOrder()
        val pushed = web.ops(me).filterIsInstance<MirrorOp.Pl>().last()
        assertThat(pushed.items!!.map { it.title }).containsExactly("1", "X", "2").inOrder()
    }

    @Test fun `a playlist from the web becomes a playlist here, a delete everywhere removes it, a stale delete loses to an edit`() = runTest {
        link()
        web.configure(playlists = Dir.BOTH, ids = listOf("m_WebPlaylist00001"))
        val since = web.head
        web.post(MirrorOp.Pl("m_WebPlaylist00001", web.at(), null, "Gym", listOf(song("G1"))), MirrorOp.Joined(Kind.PLAYLISTS, since))
        run()
        val gym = lib.playlists.entries.single { it.value.name == "Gym" }.key
        assertThat(lib.playlists.getValue(gym).items.map { it.title }).containsExactly("G1")

        // The phone edits it; a delete stamped before that edit doesn't win.
        lib.playlists.getValue(gym).items = listOf(song("G1"), song("G2"))
        web.post(MirrorOp.Pl("m_WebPlaylist00001", Hlc(1_000, 0, web.id), "h_AAAAAAAAAAAAAAAAAAAAAA", "", null))
        run()
        assertThat(lib.playlists).containsKey(gym)
        // A newer delete removes it here.
        web.wall = server.serverNow + 300_000
        web.post(MirrorOp.Pl("m_WebPlaylist00001", web.at(), null, "", null))
        run()
        assertThat(lib.playlists).doesNotContainKey(gym)
    }

    @Test fun `a Spotify playlist goes phone to web read-only, and the phone ignores ops for it`() = runTest {
        link()
        val synced = lib.addPlaylist("Discover Weekly", listOf(song("S1")), ro = true)
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.TO_PHONE), add = listOf(synced)))
        val mid = web.config()!!.ids.single()
        val sent = web.ops(me).filterIsInstance<MirrorOp.Pl>().single()
        assertThat(sent.ro).isTrue()
        web.post(MirrorOp.Pl(mid, web.at(), null, "Hacked", listOf(song("W"))))
        run()
        assertThat(lib.playlists.getValue(synced).name).isEqualTo("Discover Weekly")
        assertThat(lib.playlists.getValue(synced).items.map { it.title }).containsExactly("S1")
    }

    @Test fun `playlists turned off and on again start from a fresh base`() = runTest {
        link()
        val p = lib.addPlaylist("Run", listOf(song("R1")))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.BOTH), add = listOf(p)))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.OFF)))
        lib.playlists.getValue(p).items = listOf(song("R1"), song("R2"))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.BOTH)))
        val last = web.ops(me).filterIsInstance<MirrorOp.Pl>().last()
        assertThat(last.items!!.map { it.title }).containsExactly("R1", "R2").inOrder()
        assertThat(last.parent).isNull() // the old base was dropped: sent again as new
    }

    // ------------------------------------------------------------------------------------------------ compaction, keys, offline

    @Test fun `409 compact, the phone writes a snapshot of the whole space, then sends, and a fresh reader starts from it`() = runTest {
        link()
        likesMirroring()
        server.logLimit = server.spaces.getValue(web.spaceId).head.toInt()
        lib.like(song("E"))
        assertThat(run()).isEqualTo(MirrorRun.Done)
        val snap = web.snapshot()!!
        assertThat(snap.likes.filter { it.on }.map { it.s.title }).containsAtLeast("A", "B", "C")
        assertThat(likeOps(me).map { it.s.title }).containsExactly("E") // after the snapshot, the log holds only the new batch

        // A phone that lost its state reads the snapshot again and changes nothing.
        records.record = null
        val before = lib.likedTitles()
        assertThat(run()).isEqualTo(MirrorRun.Done)
        assertThat(lib.likedTitles()).isEqualTo(before)
    }

    @Test fun `after the phone removes a device and rotates, it compacts under the new key before its next batch`() = runTest {
        val chrome = link()
        likesMirroring()
        // A second browser joins and is removed: the phone rotates (no inline snapshot), so the space is compactDue.
        val space = server.spaces.getValue(web.spaceId)
        space.devices["d_gone00000000000"] = FakeSyncServer.Dev("d_gone00000000000", "web", "x", "y", null)
        repo.remove("d_gone00000000000")
        assertThat(space.epoch).isEqualTo(2)
        assertThat(space.compactDue).isTrue()
        lib.like(song("F"))
        assertThat(run()).isEqualTo(MirrorRun.Done)
        assertThat(space.compactDue).isFalse()
        web.catchUp()
        assertThat(web.snapshot()!!.likes.map { it.s.title }).containsAtLeast("A", "B", "C")
        assertThat(likeOps(me).map { it.s.title }).containsExactly("F")
        assertThat(chrome.deviceId).isEqualTo(web.id)
    }

    @Test fun `a log entry under a key the phone never had is a gap, it never compacts over it`() = runTest {
        link()
        likesMirroring()
        // An entry sealed under an epoch older than any the phone holds.
        val space = server.spaces.getValue(web.spaceId)
        space.log += com.stash.core.data.weblink.LogEntry(++space.head, web.id, ++server.serverNow, com.stash.core.data.weblink.SyncEnvelope(0, "AAAAAAAAAAAAAAAA", "AAAA"))
        server.logLimit = space.head.toInt()
        lib.like(song("G"))
        assertThat(run()).isEqualTo(MirrorRun.Failed(MirrorEngine.CANT_COMPACT))
        assertThat(space.snapshot).isEmpty()
    }

    @Test fun `unlinked, the mirror's state goes, the library stays`() = runTest {
        link()
        likesMirroring()
        val liked = lib.likedTitles()
        repo.unlinkEverything()
        assertThat(run()).isEqualTo(MirrorRun.NotLinked)
        assertThat(records.record).isNull()
        assertThat(lib.likedTitles()).isEqualTo(liked)
    }

    @Test fun `a key change in the middle of a run is settled, and the run goes again`() = runTest {
        link()
        server.failNext("logAfter", SyncResult.Error(409, SyncErrorCode.ROTATION_DUE))
        assertThat(run()).isEqualTo(MirrorRun.Done) // a key change in the middle is settled and the run goes again
    }
}
