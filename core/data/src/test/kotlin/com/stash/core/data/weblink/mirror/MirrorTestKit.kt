package com.stash.core.data.weblink.mirror

import com.stash.core.data.weblink.FakeBrowser
import com.stash.core.data.weblink.FakeSyncServer
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.Follow
import com.stash.core.data.weblink.merge.LikedSong
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.data.weblink.merge.PlaysMerge
import com.stash.core.model.weblink.Hlc
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongKey

fun song(title: String, artist: String = "Artist", isrc: String? = null) = WireSong(title, artist, isrc = isrc)

/** The phone's library in memory, with the same rules as [RoomMirrorLibrary] (only a Stash like can be cleared). */
class FakeMirrorLibrary : MirrorLibrary {
    class Pl(var name: String, var items: List<WireSong>, val ro: Boolean = false, val follow: Follow? = null, val createdAt: Long = 0)

    /** Liked songs, newest first; [LikedSong.external]: only through Spotify / YouTube Music. */
    val liked = mutableListOf<LikedSong<WireSong>>()
    val plays = mutableListOf<PlayRec<WireSong>>()
    val playlists = linkedMapOf<Long, Pl>()
    private var nextId = 100L

    /** Titles this library can't hold (no row could be made for them here). */
    val unholdable = mutableSetOf<String>()

    fun like(s: WireSong, external: Boolean = false) {
        liked.add(0, LikedSong(s, external))
    }

    fun unlike(s: WireSong) {
        liked.removeAll { SongKey.sameSong(it.s, s) }
    }

    fun likedTitles() = liked.map { it.s.title }.toSet()

    fun addPlaylist(name: String, items: List<WireSong>, ro: Boolean = false, createdAt: Long = 0): Long {
        val id = nextId++
        playlists[id] = Pl(name, items, ro, createdAt = createdAt)
        return id
    }

    override suspend fun likes() = liked.toList()

    override suspend fun setLikes(on: List<WireSong>, off: List<WireSong>, at: Long) {
        val idx = SongIndex.of(liked.map { it.s to true })
        for (s in on) if (!idx.has(s) && s.title !in unholdable) {
            liked.add(0, LikedSong(s))
            idx.add(s, true)
        }
        for (s in off) liked.removeAll { !it.external && SongKey.sameSong(it.s, s) }
    }

    override suspend fun ownPlays(since: Long, limit: Int) =
        plays.filter { it.origin == null && it.playedAt >= since }.sortedByDescending { it.playedAt }.take(limit)

    override suspend fun ownPlayAt(n: Int): Long = plays.filter { it.origin == null }.sortedByDescending { it.playedAt }.getOrNull(n - 1)?.playedAt ?: 0

    override suspend fun addPlays(plays: List<PlayRec<WireSong>>) {
        val here = this.plays.mapTo(HashSet()) { PlaysMerge.playId(it.s, it.playedAt) }
        for (p in plays) if (here.add(PlaysMerge.playId(p.s, p.playedAt))) this.plays += p
    }

    override suspend fun dropPlaysFrom(origin: String, before: Long) {
        plays.removeAll { it.origin == origin && it.playedAt < before }
    }

    override suspend fun dropPlaysBefore(before: Long, floor: Long) {
        plays.removeAll { it.playedAt < before && (it.origin != null || it.playedAt >= floor) }
    }

    override suspend fun playlists() = playlists.map { (id, p) -> LocalPlaylist(id, p.name, p.createdAt, p.ro, p.follow, p.items.size) }

    override suspend fun playlist(localId: Long) = playlists[localId]?.let { LocalVersion(it.name, it.items, it.follow, it.ro) }

    /** The next putPlaylist throws, as a matcher batch that failed (nothing written). */
    var failPut = false

    override suspend fun putPlaylist(localId: Long?, mirrorId: String, name: String, items: List<WireSong>): PutPlaylist {
        if (failPut) throw com.stash.core.data.weblink.handoff.MirrorMatchException(IllegalStateException("db"))
        // A song this library can't hold, and a repeat (one row per song, as the real library keeps it), aren't held.
        val seen = HashSet<String>()
        val held = items.map { it.title !in unholdable && seen.add(it.title) }
        val kept = items.filterIndexed { i, _ -> held[i] }
        val p = localId?.let { playlists[it] }
        if (p != null) {
            p.name = name
            p.items = kept
            return PutPlaylist(localId, held)
        }
        return PutPlaylist(addPlaylist(name, kept), held)
    }

    override suspend fun deletePlaylist(localId: Long) {
        playlists.remove(localId)
    }
}

/**
 * Stash on the web's mirror, scripted against [FakeSyncServer]: writes settings and batches as the browser does (sealed with the
 * space key it got in the pairing), and reads back what the phone wrote.
 */
class WebSide(private val server: FakeSyncServer, private val browser: FakeBrowser) {
    private val grant get() = browser.answer!!.space!!
    val spaceId: String get() = grant.id
    val id: String get() = browser.deviceId
    private var k: ByteArray = grant.k
    private var epoch: Int = grant.epoch
    private var counter = 0
    /** The browser's corrected clock: at least the server's (a test moves it ahead to make an edit newer). */
    var wall = 0L

    private fun key() = SyncCrypto.dataKey(k, spaceId)

    fun at(): Hlc = Hlc(maxOf(wall, server.serverNow), counter++, id)

    /** After the phone rotated: the browser reads its key envelope. */
    fun catchUp() {
        val space = server.spaces.getValue(spaceId)
        while (epoch < space.epoch) {
            val next = browser.openKey(spaceId, epoch + 1, k)
            k = next.k
            epoch = next.epoch
        }
    }

    val head: Long get() = server.spaces.getValue(spaceId).head

    /** Writes the settings as the browser (a kind turned on here gets `since` = head and `by` = this browser). */
    suspend fun configure(likes: Dir? = null, plays: Dir? = null, playlists: Dir? = null, ids: List<String>? = null, by: String? = null) {
        val cur = config() ?: MirrorConfig.off(Hlc(0, 0, id))
        fun turn(k: KindConfig, d: Dir?) = when {
            d == null -> k
            k.dir == Dir.OFF && d != Dir.OFF -> KindConfig(d, head, by ?: id)
            else -> k.copy(dir = d)
        }
        val next = cur.copy(
            at = Hlc(maxOf(wall, server.serverNow, cur.at.wall + 1), 0, id),
            likes = turn(cur.likes, likes), plays = turn(cur.plays, plays), playlists = turn(cur.playlists, playlists),
            ids = ids ?: cur.ids,
        )
        wall = next.at.wall + 1
        val env = SyncCrypto.seal(key(), spaceId, epoch, SyncCrypto.Place.CONFIG, MirrorWire.configJson(next))
        val r = server.putConfig(browser.auth, spaceId, env, server.spaces.getValue(spaceId).config?.serverAt ?: 0)
        check(r is SyncResult.Ok) { "config: $r" }
    }

    fun config(): MirrorConfig? {
        val slot = server.spaces.getValue(spaceId).config ?: return null
        return MirrorWire.readConfig(SyncCrypto.open(SyncCrypto.dataKey(keyFor(slot.env.e), spaceId), spaceId, SyncCrypto.Place.CONFIG, slot.env))
    }

    private fun keyFor(e: Int): ByteArray = if (e == epoch) k else grant.k

    suspend fun post(vararg ops: MirrorOp) {
        val env = SyncCrypto.seal(key(), spaceId, epoch, SyncCrypto.Place.LOG, MirrorWire.opsJson(id, ops.toList()))
        val r = server.postLog(browser.auth, spaceId, env)
        check(r is SyncResult.Ok) { "post: $r" }
    }

    /** Every batch in the log (after the snapshot) written by [device], opened. */
    fun batches(device: String): List<MirrorBatch> = server.spaces.getValue(spaceId).log.filter { it.device == device }.map {
        MirrorWire.readOps(SyncCrypto.open(SyncCrypto.dataKey(keyFor(it.env.e), spaceId), spaceId, SyncCrypto.Place.LOG, it.env))
    }

    fun ops(device: String): List<MirrorOp> = batches(device).flatMap { it.ops }

    /** The snapshot as the browser would read it. */
    fun snapshot(): MirrorState? {
        val parts = server.spaces.getValue(spaceId).snapshot.takeIf { it.isNotEmpty() } ?: return null
        val upto = parts[0].uptoSeq
        return MirrorWire.readState(SyncCrypto.openParts(SyncCrypto.dataKey(keyFor(parts[0].epoch), spaceId), spaceId, { i, n -> SyncCrypto.Place.snapshot(upto, i, n) }, parts.map { it.env }))
    }
}
