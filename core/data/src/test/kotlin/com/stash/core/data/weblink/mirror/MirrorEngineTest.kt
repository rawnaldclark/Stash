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

    @Test fun `a like the phone can't hold isn't sent back as an unlike`() = runTest {
        link()
        lib.unholdable += "Ghost"
        likesMirroring()
        web.post(MirrorOp.Like(song("Ghost"), true, web.at()))
        run()
        lib.like(song("Real"))
        run()
        assertThat(likeOps(me).filter { !it.on }.map { it.s.title }).isEmpty()
    }

    @Test fun `a playlist song the phone can't hold isn't sent back as removed`() = runTest {
        link()
        lib.unholdable += "Ghost"
        web.configure(playlists = Dir.BOTH, ids = listOf("m_WebPlaylist00002"))
        val since = web.head
        web.post(MirrorOp.Pl("m_WebPlaylist00002", web.at(), null, "Mix", listOf(song("One"), song("Ghost"))), MirrorOp.Joined(Kind.PLAYLISTS, since))
        run()
        run()
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Pl>()).isEmpty()
    }

    // ------------------------------------------------------------------------------------------------ Phase 6 review

    /** The web sends playlist [items] under [mid]; returns the phone's local id for it. */
    private suspend fun webPlaylist(mid: String, items: List<String>): Long {
        web.configure(playlists = Dir.BOTH, ids = listOf(mid))
        val since = web.head
        web.post(MirrorOp.Pl(mid, web.at(), null, "Mix", items.map { song(it) }), MirrorOp.Joined(Kind.PLAYLISTS, since))
        run()
        return lib.playlists.entries.single { it.value.name == "Mix" }.key
    }

    private fun lastPl() = web.ops(me).filterIsInstance<MirrorOp.Pl>().lastOrNull()

    private fun lastWebHash(mid: String): String {
        val pl = web.ops(web.id).filterIsInstance<MirrorOp.Pl>().last { it.id == mid }
        return SyncHashes.playlistHash(pl.name, pl.items, pl.follow)
    }

    @Test fun `B2 - a song the phone can't hold stays in place in every version it sends after a local edit`() = runTest {
        link()
        lib.unholdable += "Ghost"
        val id = webPlaylist("m_WebPlaylist00003", listOf("One", "Ghost", "Two"))
        assertThat(lib.playlists.getValue(id).items.map { it.title }).containsExactly("One", "Two").inOrder()
        lib.playlists.getValue(id).items = lib.playlists.getValue(id).items + song("Three")
        run()
        assertThat(lastPl()!!.items!!.map { it.title }).containsExactly("One", "Ghost", "Two", "Three").inOrder()
        // And again after another edit (the carried song survives every push).
        lib.playlists.getValue(id).items = listOf(song("Zero")) + lib.playlists.getValue(id).items
        run()
        assertThat(lastPl()!!.items!!.map { it.title }).containsExactly("Zero", "One", "Ghost", "Two", "Three").inOrder()
    }

    @Test fun `B2 - a repeated song the library keeps once is carried, not removed`() = runTest {
        link()
        val id = webPlaylist("m_WebPlaylist00004", listOf("A", "A", "B"))
        assertThat(lib.playlists.getValue(id).items.map { it.title }).containsExactly("A", "B").inOrder()
        lib.playlists.getValue(id).items = lib.playlists.getValue(id).items + song("C")
        run()
        assertThat(lastPl()!!.items!!.map { it.title }).containsExactly("A", "A", "B", "C").inOrder()
    }

    @Test fun `B2 - a matcher failure aborts the run, writes nothing and never pushes an emptied playlist`() = runTest {
        link()
        val id = webPlaylist("m_WebPlaylist00005", listOf("One", "Two"))
        lib.failPut = true
        web.post(MirrorOp.Pl("m_WebPlaylist00005", web.at(), lastWebHash("m_WebPlaylist00005"), "Mix", listOf(song("One"), song("Two"), song("New"))))
        assertThat(run()).isInstanceOf(MirrorRun.Retry::class.java)
        assertThat(lib.playlists.getValue(id).items.map { it.title }).containsExactly("One", "Two").inOrder()
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Pl>()).isEmpty()
        lib.failPut = false
        assertThat(run()).isEqualTo(MirrorRun.Done)
        assertThat(lib.playlists.getValue(id).items.map { it.title }).containsExactly("One", "Two", "New").inOrder()
    }

    @Test fun `B5 S5 - a mirrored playlist gone without a choice (unfollow, a Home delete) is Only here, never deleted elsewhere`() = runTest {
        link()
        val p = lib.addPlaylist("Run", listOf(song("R1")))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.BOTH), add = listOf(p)))
        val mid = web.config()!!.ids.single()
        lib.playlists.remove(p) // deleted by a path that asked nothing
        run()
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Pl>().none { it.items == null }).isTrue()
        assertThat(web.config()!!.ids).doesNotContain(mid)
    }

    @Test fun `B5 - Everywhere, chosen in the dialog, deletes it on every device`() = runTest {
        link()
        val p = lib.addPlaylist("Run", listOf(song("R1")))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.BOTH), add = listOf(p)))
        val mid = web.config()!!.ids.single()
        engine.beforeDelete(p, everywhere = true)
        lib.playlists.remove(p)
        run()
        assertThat(lastPl()!!.items).isNull()
        // Later runs keep its id chosen, so a device reading the delete afterwards still applies it (found on the Pixel 5).
        run()
        run()
        assertThat(web.config()!!.ids).contains(mid)
        assertThat(web.ops(me).filterIsInstance<MirrorOp.Pl>().count { it.items == null }).isEqualTo(1)
    }

    @Test fun `S2 - Only here made offline stays deleted, the settings change is retried, and an edit elsewhere doesn't bring it back`() = runTest {
        link()
        val id = webPlaylist("m_WebPlaylist00006", listOf("G1"))
        engine.beforeDelete(id, everywhere = false)
        lib.playlists.remove(id)
        server.failNext("putConfig", SyncResult.Error(503, "unavailable"))
        assertThat(run()).isInstanceOf(MirrorRun.Retry::class.java)
        web.post(MirrorOp.Pl("m_WebPlaylist00006", web.at(), lastWebHash("m_WebPlaylist00006"), "Mix", listOf(song("G1"), song("G2"))))
        assertThat(run()).isEqualTo(MirrorRun.Done)
        assertThat(lib.playlists.values.none { it.name == "Mix" }).isTrue()
        assertThat(web.config()!!.ids).doesNotContain("m_WebPlaylist00006")
    }

    @Test fun `S1 - a push cut short after its first batch never re-sends that batch`() = runTest {
        link()
        likesMirroring()
        val n = 20_000
        val rnd = java.util.Random(7)
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        fun word() = (1..24).map { chars[rnd.nextInt(62)] }.joinToString("")
        repeat(n) { lib.like(song(word(), word())) }
        server.failPostsAfter = 1
        assertThat(run()).isInstanceOf(MirrorRun.Retry::class.java)
        val landed = likeOps(me).count { it.on }
        assertThat(landed).isGreaterThan(3) // the first batch landed...
        assertThat(landed).isLessThan(n) // ...and the rest didn't
        server.failPostsAfter = null
        assertThat(run()).isEqualTo(MirrorRun.Done)
        val sent = likeOps(me).filter { it.on }.map { it.s.title }
        assertThat(sent.size).isEqualTo(sent.toSet().size) // nothing went twice
        assertThat(sent.toSet().size).isAtLeast(n)
    }

    @Test fun `S3 - Also on Pixel clears every own play the mirror sent, not just the last two days`() = runTest {
        link()
        server.serverNow = 30L * 86_400_000
        repeat(3) { lib.plays += PlayRec(song("Old $it"), 1_000L + it) }
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYS to Dir.BOTH)))
        clock += 10L * 86_400_000
        server.serverNow += 10L * 86_400_000
        run()
        web.post(MirrorOp.Joined(Kind.PLAYS, web.config()!!.plays.since), MirrorOp.ClearPlays(5_000, web.at(), all = true))
        run()
        assertThat(lib.plays).isEmpty()
    }

    @Test fun `S11 - an answer given to other counts is not applied, the question comes back with the new ones`() = runTest {
        link()
        browserTurnsLikesOn()
        run()
        assertThat(engine.status.value.question!!.there).isEqualTo(2)
        web.post(MirrorOp.Like(song("D"), true, web.at())) // the browser likes one more before the answer runs
        engine.answer(FirstMergeChoice.MINE)
        assertThat(likeOps(me).none { !it.on }).isTrue() // nothing removed on the strength of the old counts
        assertThat(engine.status.value.question!!.there).isEqualTo(3)
        engine.answer(FirstMergeChoice.MINE)
        assertThat(likeOps(me).filter { !it.on }.map { it.s.title }).containsExactly("C", "D")
    }

    @Test fun `N7 - one run that would unlike a big part of the likes is held until the listener sends it`() = runTest {
        link()
        repeat(40) { lib.like(song("L$it")) }
        engine.configure(MirrorChange(dirs = mapOf(Kind.LIKES to Dir.BOTH)))
        repeat(15) { lib.unlike(song("L$it")) }
        run()
        assertThat(engine.status.value.heldRemovals).isEqualTo(15)
        assertThat(likeOps(me).none { !it.on }).isTrue()
        engine.releaseRemovals(send = true)
        assertThat(likeOps(me).count { !it.on }).isEqualTo(15)
        assertThat(engine.status.value.heldRemovals).isEqualTo(0)
    }

    @Test fun `N1 - a state file that can't be read back keeps the playlist mapping, so a rejoin doesn't copy the phone's playlists`() = runTest {
        link()
        val p = lib.addPlaylist("Run", listOf(song("R1")))
        engine.configure(MirrorChange(dirs = mapOf(Kind.PLAYLISTS to Dir.BOTH), add = listOf(p)))
        records.record = null // unreadable; the map backup survives
        run()
        assertThat(lib.playlists.values.count { it.name == "Run" }).isEqualTo(1)
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
