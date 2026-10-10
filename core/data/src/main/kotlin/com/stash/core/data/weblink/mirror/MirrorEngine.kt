package com.stash.core.data.weblink.mirror

import android.util.Log
import com.stash.core.data.weblink.DeviceAuth
import com.stash.core.data.weblink.LogEntry
import com.stash.core.data.weblink.SnapshotMeta
import com.stash.core.data.weblink.SpaceInfo
import com.stash.core.data.weblink.SyncApi
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncCryptoException
import com.stash.core.data.weblink.SyncEnvelope
import com.stash.core.data.weblink.SyncErrorCode
import com.stash.core.data.weblink.SyncResult
import com.stash.core.data.weblink.WebLinkCopy
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.FirstMerge
import com.stash.core.data.weblink.merge.LikeRec
import com.stash.core.data.weblink.merge.LikesMerge
import com.stash.core.data.weblink.merge.PlVersion
import com.stash.core.data.weblink.merge.PlayOp
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.data.weblink.merge.PlaylistMerge
import com.stash.core.data.weblink.merge.PlaysMerge
import com.stash.core.data.weblink.merge.PlaysState
import com.stash.core.data.weblink.merge.SyncHashes
import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.WebLinkStore
import com.stash.core.model.weblink.ClockOffset
import com.stash.core.model.weblink.Hlc
import com.stash.core.model.weblink.SongIndex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a mirror run ended. */
sealed interface MirrorRun {
    /** Reached the service and did everything there was to do. */
    data object Done : MirrorRun

    /** This phone isn't linked (or was just unlinked). */
    data object NotLinked : MirrorRun

    /** Couldn't reach the service (or it asked to slow down): worth retrying later. Nothing is lost: the diff waits. */
    data class Retry(val message: String) : MirrorRun

    /** Stopped for a reason a retry soon won't fix (a newer format, a full space, a log only another device can compact). */
    data class Failed(val message: String) : MirrorRun
}

/** What Settings › Link Stash on the web › Mirror shows. */
data class MirrorStatus(
    val linked: Boolean = false,
    val config: MirrorConfig? = null,
    /** The first-merge question for likes, when it waits for an answer. */
    val question: LikesQuestion? = null,
    /** Likes are on, but this phone waits for a browser to share its own first. */
    val waiting: Boolean = false,
    val joined: Set<Kind> = emptySet(),
    val busy: Boolean = false,
    val problem: String? = null,
    /** Local time of the last run that reached the service. */
    val syncedAt: Long = 0,
    /** "Chrome on Windows", or "your browsers". */
    val otherName: String = OTHERS,
    /** Mirror id → this phone's playlist id, for the chooser. */
    val mirrored: Map<String, Long> = emptyMap(),
) {
    companion object {
        const val OTHERS = "your browsers"
    }
}

/** A change to the mirror settings (spec §2.4): a kind's direction, playlists chosen or let go, "Mirror new playlists too". */
data class MirrorChange(
    val dirs: Map<Kind, Dir> = emptyMap(),
    /** This phone's playlists (library ids) to mirror. */
    val add: List<Long> = emptyList(),
    /** This phone's playlists to stop mirroring (every copy stays where it is). */
    val remove: List<Long> = emptyList(),
    val newOnes: Boolean? = null,
)

/**
 * Mirroring on this phone (spec §2.4, §7, §12; sync-v1 §5.2–5.4, §7): likes, plays and chosen playlists between the devices of the
 * link, each kind only when the listener turned it on and only in the direction they chose. The rules are in `merge/` and
 * [MirrorView]; this is the run, the network, the keys and the library. The web's `src/lib/sync/mirror.ts` is the same run.
 *
 * A run (one at a time: a [Mutex]):
 *   1. reads the space; a key change due or missed goes through [WebLinkRepository.refresh] (it rotates, or walks the key
 *      envelopes), and the run starts again;
 *   2. reads the shared settings, never going back to an older one;
 *   3. pulls what the others wrote (the snapshot when this phone is behind it, then the log), keeps the view of the space, and
 *      applies what this phone receives; saved;
 *   4. joins a kind that was just turned on (the first merge, sync-v1 §7.4); saved;
 *   5. diffs the library against the base and pushes; the base moves only once a batch is in, so the diff *is* the outbox; saved
 *      after each batch;
 *   6. compacts the log into a snapshot when it is long or after a key change, never over log entries it couldn't read.
 *
 * The phone sends a kind on `both` / `toWeb` and applies it on `both` / `toPhone`; Spotify / YouTube Music-synced playlists go
 * phone → web whatever the direction (`ro`), and the phone never applies an op for one.
 */
@Singleton
class MirrorEngine internal constructor(
    private val api: SyncApi,
    private val store: WebLinkStore,
    private val repo: WebLinkRepository,
    private val records: MirrorStore,
    private val lib: MirrorLibrary,
    private val clock: () -> Long,
    private val mintId: () -> String,
) {
    @Inject constructor(api: SyncApi, store: WebLinkStore, repo: WebLinkRepository, records: MirrorStore, lib: MirrorLibrary) :
        this(api, store, repo, records, lib, System::currentTimeMillis, ::mintMirrorId)

    private val mutex = Mutex()
    private val _status = MutableStateFlow(MirrorStatus())
    val status: StateFlow<MirrorStatus> = _status.asStateFlow()

    private class Ctx(
        val id: LinkIdentity,
        var sp: LinkedSpace,
        val r: MirrorRecord,
        var info: SpaceInfo,
        var cfg: MirrorConfig,
        val roster: Map<String, String>,
    ) {
        val me: String get() = id.deviceId
        val auth: DeviceAuth get() = DeviceAuth(id.deviceId, id.token)
        var pendingLikes: SongIndex<Boolean>? = null
    }

    /** The service said no: [error] is its answer. */
    private class ApiError(val error: SyncResult.Error) : Exception(error.code)

    private class Offline(message: String = WebLinkCopy.OFFLINE) : Exception(message)

    /** The key must be settled first (a rotation due, or one this phone missed). */
    private class Settle : Exception()

    /** The log needs a snapshot this phone can't write (part of it is under a key it never had): a browser writes it. */
    private class CantCompact : Exception(CANT_COMPACT)

    private fun <T> SyncResult<T>.get(): T = when (this) {
        is SyncResult.Ok -> value
        is SyncResult.Error -> throw ApiError(this)
        is SyncResult.Unreachable -> throw Offline()
    }

    // -------------------------------------------------------------------------------------------- what the listener does

    /** One run now. */
    suspend fun sync(): MirrorRun = attempt(null)

    /** Settings › Mirror: a direction, playlists chosen or let go, "Mirror new playlists too". */
    suspend fun configure(change: MirrorChange): MirrorRun = attempt { c -> writeConfig(c, change) }

    /** The first-merge answer for likes. */
    suspend fun answer(choice: FirstMergeChoice): MirrorRun = attempt { c ->
        c.r.question?.let { q -> c.r.answer = LikesAnswer(q.since, choice) }
    }

    /** [localId] is a playlist this phone mirrors now (from the last state read: [load] or a run). */
    fun isMirrored(localId: Long): Boolean {
        val s = _status.value
        val cfg = s.config ?: return false
        return cfg.playlists.dir != Dir.OFF && s.mirrored.any { (mid, id) -> id == localId && mid in cfg.ids }
    }

    /**
     * "Only here" (spec §2.4): [localId] stops mirroring before it is deleted here, so its copies elsewhere stay. Offline, the
     * settings can't change yet; the playlist is let go here at least, so a later run never sends it as deleted everywhere.
     */
    suspend fun stopMirroring(localId: Long) {
        if (!isMirrored(localId)) return
        if (configure(MirrorChange(remove = listOf(localId))) == MirrorRun.Done) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val r = guard(null) { records.load() } ?: return@withLock
                r.map = r.map.filterValues { it.localId != localId }
                guard(Unit) { records.save(r) }
                _status.update { it.copy(mirrored = r.map.mapValues { e -> e.value.localId }) }
            }
        }
    }

    /** Unlinked (here, or another device removed this phone): the mirror's state goes at once; the library stays as it is. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        mutex.withLock {
            guard(Unit) { records.clear() }
            _status.value = MirrorStatus(linked = false)
        }
    }

    /** Reads the saved state for the settings screen (no network). */
    suspend fun load() {
        val sp = guard(null) { store.space() }
        val r = guard(null) { records.load() }?.takeIf { sp != null && it.spaceId == sp.spaceId }
        val cfg = r?.configJson?.let { runCatching { MirrorWire.readConfig(it) }.getOrNull() }
        _status.update {
            it.copy(
                linked = sp != null, config = cfg, question = r?.question, waiting = r?.waiting ?: false,
                joined = if (r != null && cfg != null) Kind.entries.filter { k -> cfg.of(k).dir != Dir.OFF && r.joined[k] == cfg.of(k).since }.toSet() else emptySet(),
                mirrored = r?.map?.mapValues { e -> e.value.localId } ?: emptyMap(),
                syncedAt = r?.lastRunAt?.takeIf { r.lastResult == RESULT_OK } ?: it.syncedAt,
            )
        }
    }

    /** For the diagnostics bundle (never ids, keys or labels). */
    suspend fun record(): MirrorRecord? = guard(null) { records.load() }

    private suspend inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    // -------------------------------------------------------------------------------------------- the run

    private suspend fun attempt(op: (suspend (Ctx) -> Unit)?): MirrorRun = withContext(Dispatchers.IO) { mutex.withLock { attemptLocked(op) } }

    /** Sealing, gzip, JSON and the library work run on IO, never on the caller's (often the main) thread. */
    private suspend fun attemptLocked(op: (suspend (Ctx) -> Unit)?): MirrorRun {
        _status.update { it.copy(busy = true) }
        try {
            val result = run(op)
            _status.update {
                it.copy(
                    problem = (result as? MirrorRun.Failed)?.message ?: (result as? MirrorRun.Retry)?.message,
                    syncedAt = if (result == MirrorRun.Done) clock() else it.syncedAt,
                    linked = result != MirrorRun.NotLinked,
                )
            }
            return result
        } finally {
            _status.update { it.copy(busy = false) }
        }
    }

    private suspend fun run(op: (suspend (Ctx) -> Unit)?): MirrorRun {
        var lastRecord: MirrorRecord? = null
        for (i in 0..2) {
            try {
                val c = context() ?: return notLinked()
                lastRecord = c.r
                once(c, op)
                return finish(c.r, MirrorRun.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Settle) {
                if (i == 2) return finish(lastRecord, MirrorRun.Retry(WebLinkCopy.OFFLINE))
                repo.refresh()
            } catch (e: ApiError) {
                val err = e.error
                when {
                    err.revoked -> {
                        repo.refresh() // forgets the link; the mirror goes with it
                        return notLinked()
                    }
                    (err.code == SyncErrorCode.ROTATION_DUE || err.code == SyncErrorCode.EPOCH) && i < 2 -> repo.refresh()
                    err.status == 429 || err.status >= 500 -> return finish(lastRecord, MirrorRun.Retry(SLOW))
                    err.code == SyncErrorCode.SPACE_FULL -> return finish(lastRecord, MirrorRun.Failed(FULL))
                    else -> return finish(lastRecord, MirrorRun.Failed(UNFINISHED)).also { Log.w(TAG, "run stopped: ${err.status} ${err.code}") }
                }
            } catch (e: Offline) {
                return finish(lastRecord, MirrorRun.Retry(e.message ?: WebLinkCopy.OFFLINE))
            } catch (e: CantCompact) {
                return finish(lastRecord, MirrorRun.Failed(CANT_COMPACT))
            } catch (e: MirrorWireException) {
                // A newer format: nothing is applied, nothing is lost (the log keeps it).
                return finish(lastRecord, MirrorRun.Failed(WebLinkCopy.UPDATE_APP))
            } catch (e: Exception) {
                Log.w(TAG, "run failed: ${e.javaClass.simpleName}")
                return finish(lastRecord, MirrorRun.Retry(UNFINISHED))
            }
        }
        return finish(lastRecord, MirrorRun.Retry(UNFINISHED))
    }

    private suspend fun notLinked(): MirrorRun {
        guard(Unit) { records.clear() }
        _status.value = MirrorStatus(linked = false)
        return MirrorRun.NotLinked
    }

    /** Notes how the run ended (diagnostics) and saves what it got to. */
    private suspend fun finish(r: MirrorRecord?, result: MirrorRun): MirrorRun {
        if (r != null) {
            r.lastRunAt = clock()
            r.lastResult = when (result) {
                MirrorRun.Done -> RESULT_OK
                is MirrorRun.Retry -> "retry"
                is MirrorRun.Failed -> "failed"
                MirrorRun.NotLinked -> "unlinked"
            }
            guard(Unit) { records.save(r) }
        }
        return result
    }

    private suspend fun context(): Ctx? {
        val id = store.identity() ?: return null
        val sp = store.space() ?: return null
        var r = records.load()
        if (r == null || r.v != 1 || r.spaceId != sp.spaceId) r = MirrorRecord(spaceId = sp.spaceId)
        val roster = store.roster().filter { it.deviceId != id.deviceId }.associate { it.deviceId to it.type }
        val sent = clock()
        val info = api.space(DeviceAuth(id.deviceId, id.token), sp.spaceId).get()
        val received = clock()
        if (info.serverTime > 0) r.samples = (r.samples + ClockSample(sent, received, info.serverTime)).takeLast(ClockOffset.SAMPLES)
        if (info.rotationDue || info.epoch > sp.epoch) throw Settle()
        val names = store.roster().filter { it.type == TYPE_WEB }.map { it.displayName }
        _status.update { it.copy(linked = true, otherName = names.singleOrNull() ?: MirrorStatus.OTHERS) }
        return Ctx(id, sp, r, info, configOf(r), roster)
    }

    private fun configOf(r: MirrorRecord): MirrorConfig =
        r.configJson?.let { runCatching { MirrorWire.readConfig(it) }.getOrNull() } ?: MirrorConfig.off(Hlc(0, 0, "d_00000000"))

    private suspend fun once(c: Ctx, op: (suspend (Ctx) -> Unit)?) {
        val r = c.r
        readConfig(c)
        c.pendingLikes = pendingLikes(c)
        pull(c)
        records.save(r)
        if (op != null) op(c)
        join(c)
        mirrorNewOnes(c)
        records.save(r)
        // After a key change the service takes no batch before a snapshot under the new key: that first, then the push.
        if (c.info.compactDue) compact(c, wholeLog = true)
        push(c)
        records.save(r)
        compactIfDue(c)
        val cfg = c.cfg
        _status.update {
            it.copy(
                config = cfg, question = r.question, waiting = r.waiting,
                joined = Kind.entries.filter { k -> joinedNow(c, k) }.toSet(), mirrored = r.map.mapValues { e -> e.value.localId },
            )
        }
        Log.i(TAG, "run: seen ${r.seen}, ${r.view.likes.size} likes, ${r.view.plays.size} plays, ${r.view.playlists.size} playlists in the space")
    }

    /** Server-corrected time (the HLC's wall clock, sync-v1 §4). */
    private fun wall(c: Ctx): Long = clock() + ClockOffset.of(c.r.samples.map { ClockOffset.Sample(it.sent, it.received, it.serverTime) })

    private fun joinedNow(c: Ctx, k: Kind): Boolean = c.cfg.of(k).dir != Dir.OFF && c.r.joined[k] == c.cfg.of(k).since

    private fun dataKey(c: Ctx, epoch: Int): ByteArray? = c.sp.keyFor(epoch)?.let { SyncCrypto.dataKey(it, c.sp.spaceId) }

    /** The data key of an envelope's epoch; a newer epoch than this phone holds means a rotation it hasn't caught up with. */
    private fun keyOrSettle(c: Ctx, epoch: Int): ByteArray? {
        val k = dataKey(c, epoch)
        if (k == null && epoch > c.sp.epoch) throw Settle()
        return k
    }

    // -------------------------------------------------------------------------------------------- settings

    private suspend fun readConfig(c: Ctx) {
        val r = c.r
        val slot = api.config(c.auth, c.sp.spaceId).get()
        if (slot == null) {
            r.configServerAt = 0
            return
        }
        r.configServerAt = slot.serverAt
        val key = keyOrSettle(c, slot.env.e) ?: return
        val cfg = try {
            MirrorWire.readConfig(SyncCrypto.open(key, c.sp.spaceId, SyncCrypto.Place.CONFIG, slot.env))
        } catch (e: MirrorWireException) {
            if (e.newer) throw e
            return // unreadable: keep what this phone had
        } catch (e: SyncCryptoException) {
            return
        }
        if (Hlc.fromTheFuture(cfg.at, slot.serverAt)) return
        r.last = Hlc.recv(r.last, cfg.at, wall(c), c.me)
        val cur = r.configJson?.let { runCatching { MirrorWire.readConfig(it) }.getOrNull() }
        if (cur != null && cur.at > cfg.at) return // never back to an older one (sync-v1 §5.2)
        if (cfg.newOnes && cur?.newOnes != true) r.newOnesFrom = r.newOnesFrom ?: clock()
        if (!cfg.newOnes) r.newOnesFrom = null
        // A playlist no longer mirrored: its base goes; every copy stays where it is.
        r.basePlaylists = r.basePlaylists.filterKeys { it in cfg.ids }
        r.configJson = MirrorWire.configJson(cfg)
        c.cfg = cfg
    }

    /** Writes the settings with [change] (`If-Match` the slot as read; `412 changed` reads it again and makes the change on top). */
    private suspend fun writeConfig(c: Ctx, change: MirrorChange) {
        val r = c.r
        repeat(3) { attempt ->
            val cur = c.cfg
            var next = cur
            for ((k, dir) in change.dirs) {
                val kc = cur.of(k)
                // Turned on: what the space held of it from before is stale (since = the log's head, which this run pulled to).
                next = next.with(k, if (kc.dir == Dir.OFF && dir != Dir.OFF) KindConfig(dir, r.seen, c.me) else kc.copy(dir = dir))
            }
            change.newOnes?.let { next = next.copy(newOnes = it) }
            val ids = next.ids.toMutableList()
            if (change.add.isNotEmpty()) {
                val local = lib.playlists().associateBy { it.id }
                for (pid in change.add) {
                    val p = local[pid] ?: continue
                    val mid = r.map.entries.firstOrNull { it.value.localId == pid }?.key ?: mintId()
                    if (mid !in ids) ids += mid
                    r.map = r.map + (mid to MappedPl(pid, FollowRec.of(p.follow)))
                }
            }
            if (change.remove.isNotEmpty()) {
                val gone = r.map.filterValues { it.localId in change.remove }.keys
                ids.removeAll(gone)
                r.basePlaylists = r.basePlaylists - gone
            }
            next = next.copy(ids = ids.distinct())
            val stamp = Hlc.tick(maxOf(r.last ?: cur.at, cur.at), wall(c), c.me)
            next = next.copy(at = stamp)
            r.last = stamp
            val key = dataKey(c, c.sp.epoch) ?: throw Settle()
            val env = SyncCrypto.seal(key, c.sp.spaceId, c.sp.epoch, SyncCrypto.Place.CONFIG, MirrorWire.configJson(next))
            when (val res = api.putConfig(c.auth, c.sp.spaceId, env, r.configServerAt)) {
                is SyncResult.Ok -> {
                    r.configServerAt = res.value.serverAt
                    if (next.newOnes && !cur.newOnes) r.newOnesFrom = clock()
                    r.configJson = MirrorWire.configJson(next)
                    c.cfg = next
                    return
                }
                is SyncResult.Error -> {
                    if (res.code != SyncErrorCode.CHANGED || attempt == 2) throw ApiError(res)
                    readConfig(c) // someone else wrote meanwhile: apply theirs, then make the change again on top
                }
                is SyncResult.Unreachable -> throw Offline()
            }
        }
    }

    // -------------------------------------------------------------------------------------------- pull

    private fun likeRecs(r: MirrorRecord) = r.baseLikes.map { LikeRec(it.s, it.on, it.at) }

    /** Songs this phone changed (likes) since the base and hasn't sent: a remote op read in the same run doesn't undo them. */
    private suspend fun pendingLikes(c: Ctx): SongIndex<Boolean>? {
        if (!joinedNow(c, Kind.LIKES) || !c.cfg.likes.dir.phoneSends) return null
        val diff = LikesMerge.diff(likeRecs(c.r), lib.likes(), Hlc(0, 0, c.me))
        return SongIndex.of(diff.map { it.s to true })
    }

    private suspend fun pull(c: Ctx) {
        val r = c.r
        val snap = c.info.snapshot?.takeIf { it.parts > 0 }
        // A snapshot now covers what this phone couldn't read, written by a device that could: the view starts from it again.
        val closes = snap != null && r.gaps.isNotEmpty() && r.gaps.max() <= snap.uptoSeq && c.sp.keyFor(snap.epoch) != null
        if (snap != null && (r.seen < snap.uptoSeq || closes)) readSnapshot(c, snap)
        for (page in 0 until 1_000) {
            val res = api.logAfter(c.auth, c.sp.spaceId, r.seen)
            if (res is SyncResult.Error && res.code == SyncErrorCode.SNAPSHOT) {
                c.info = api.space(c.auth, c.sp.spaceId).get()
                val again = c.info.snapshot?.takeIf { it.parts > 0 } ?: throw ApiError(res)
                readSnapshot(c, again)
                continue
            }
            val pageBody = res.get()
            for (e in pageBody.entries) {
                if (e.seq <= r.seen) continue
                applyEntry(c, e)
            }
            if (!pageBody.more || pageBody.entries.isEmpty()) break
        }
    }

    private suspend fun applyEntry(c: Ctx, e: LogEntry) {
        val r = c.r
        val key = keyOrSettle(c, e.env.e)
        if (key == null && e.seq !in r.gaps) r.gaps = r.gaps + e.seq // sealed under a key this phone never had: a gap
        var batch: MirrorBatch? = null
        if (key != null) {
            try {
                batch = MirrorWire.readOps(SyncCrypto.open(key, c.sp.spaceId, SyncCrypto.Place.LOG, e.env))
            } catch (x: MirrorWireException) {
                if (x.newer) throw x // stop here: nothing is applied, nothing is lost (the log keeps it)
            } catch (x: SyncCryptoException) {
                Log.w(TAG, "log entry ${e.seq} didn't open")
            }
        }
        r.logBytes += e.env.c.length
        if (batch != null && batch.device == e.device) {
            val w = wall(c)
            val ops = batch.ops.filter { op -> op.stamp?.let { !Hlc.fromTheFuture(it, e.serverAt) } ?: true }
            for (op in ops) op.stamp?.let { r.last = Hlc.recv(r.last, it, w, c.me) }
            val fromPhone = e.device == c.me
            r.view = MirrorView.applyBatch(r.view, e.device, e.seq, ops, fromPhone)
            if (e.device != c.me) applyToLibrary(c, e.device, ops)
        }
        r.seen = e.seq
    }

    /** A snapshot (this phone is behind it): the view starts from it, and what this phone receives is applied. */
    private suspend fun readSnapshot(c: Ctx, meta: SnapshotMeta) {
        val r = c.r
        val envs = ArrayList<SyncEnvelope>(meta.parts)
        for (i in 0 until meta.parts) {
            val part = api.snapshotPart(c.auth, c.sp.spaceId, i).get()
            if (part.uptoSeq != meta.uptoSeq) throw Offline(UNFINISHED) // replaced while reading: the next run reads the new one
            envs += part.env
        }
        val key = keyOrSettle(c, meta.epoch)
        if (key == null) {
            // Under a key from before this phone had it: it goes on from after it, with a gap there.
            if (meta.uptoSeq !in r.gaps) r.gaps = r.gaps + meta.uptoSeq
            r.seen = maxOf(r.seen, meta.uptoSeq)
            r.snapUpto = meta.uptoSeq
            return
        }
        val st = MirrorWire.readState(SyncCrypto.openParts(key, c.sp.spaceId, { i, n -> SyncCrypto.Place.snapshot(meta.uptoSeq, i, n) }, envs))
        if (st.uptoSeq != meta.uptoSeq) throw Offline(UNFINISHED)
        r.view = MirrorView.fromState(st)
        val cfg = c.cfg
        if (joinedNow(c, Kind.LIKES) && cfg.likes.dir.phoneApplies && st.uptoSeq > cfg.likes.since) {
            applyLikeOps(c, st.likes.map { MirrorOp.Like(it.s, it.on, it.at) })
        }
        if (joinedNow(c, Kind.PLAYS) && cfg.plays.dir.phoneApplies && st.uptoSeq > cfg.plays.since) {
            if (st.playsClearedBefore > r.clearMark) {
                // A clear for every device this phone didn't see happen: plays from other devices before it go; its own stay.
                r.clearMark = st.playsClearedBefore
                r.basePlays = r.basePlays.copy(clearedBefore = maxOf(r.basePlays.clearedBefore, st.playsClearedBefore))
                r.view.plays.map { it.device }.distinct().filter { it != c.me }.forEach { lib.dropPlaysFrom(it, st.playsClearedBefore) }
            }
            lib.addPlays(st.plays.filter { it.device != c.me && it.playedAt >= r.clearMark }.map { PlayRec(it.s, it.playedAt, it.device) })
        }
        if (joinedNow(c, Kind.PLAYLISTS) && st.uptoSeq > cfg.playlists.since && cfg.playlists.dir.phoneApplies) {
            for (p in st.playlists) {
                if (p.id !in cfg.ids || p.ro) continue
                applyPl(c, MirrorOp.Pl(p.id, p.at, p.hash, p.name, p.items, p.follow, p.ro), p.hash)
            }
        }
        r.seen = st.uptoSeq
        r.snapUpto = st.uptoSeq
        r.logBytes = 0
        r.gaps = r.gaps.filter { it > st.uptoSeq }
    }

    private suspend fun applyToLibrary(c: Ctx, device: String, ops: List<MirrorOp>) {
        val r = c.r
        val cfg = c.cfg
        if (joinedNow(c, Kind.LIKES) && cfg.likes.dir.phoneApplies) {
            val likes = ops.filterIsInstance<MirrorOp.Like>()
            if (likes.isNotEmpty()) applyLikeOps(c, likes)
        }
        if (joinedNow(c, Kind.PLAYS) && cfg.plays.dir.phoneApplies) {
            val adds = mutableListOf<PlayRec<WireSong>>()
            suspend fun flush() {
                if (adds.isNotEmpty()) lib.addPlays(adds.toList())
                adds.clear()
            }
            for (o in ops) {
                when (o) {
                    is MirrorOp.Play -> if (o.playedAt >= r.clearMark) adds += PlayRec(o.s, o.playedAt, device)
                    is MirrorOp.ClearPlays -> {
                        flush()
                        if (o.all) {
                            // "Also on Pixel 6": the listener chose to clear this phone's History too, up to that moment.
                            if (o.before > r.clearMark) {
                                lib.dropPlaysBefore(o.before, r.basePlays.floor)
                                r.clearMark = o.before
                            }
                            r.basePlays = r.basePlays.copy(clearedBefore = maxOf(r.basePlays.clearedBefore, minOf(o.before, r.clearMark)))
                        } else {
                            lib.dropPlaysFrom(device, o.before)
                        }
                    }
                    else -> Unit
                }
            }
            flush()
        }
        if (cfg.playlists.dir != Dir.OFF && joinedNow(c, Kind.PLAYLISTS) && cfg.playlists.dir.phoneApplies) {
            for (o in ops) {
                if (o !is MirrorOp.Pl || o.id !in cfg.ids) continue
                applyPl(c, o, MirrorView.hashOf(o))
            }
        }
    }

    /** Remote like ops: the base takes the newer stamp; the library follows, except songs changed here and not sent yet. */
    private suspend fun applyLikeOps(c: Ctx, ops: List<MirrorOp.Like>) {
        val recs = c.r.baseLikes.toMutableList()
        val idx = SongIndex.of(recs.mapIndexed { i, x -> x.s to i })
        val touched = LinkedHashMap<Int, WireSong>()
        for (op in ops) {
            var i = idx.find(op.s)
            if (i == null) {
                i = recs.size
                idx.add(op.s, i)
                recs += BaseLike(op.s, op.on, op.at)
            } else if (op.at > recs[i].at) {
                recs[i] = BaseLike(recs[i].s, op.on, op.at)
            } else {
                continue
            }
            if (c.pendingLikes?.has(op.s) != true) touched[i] = op.s
        }
        c.r.baseLikes = recs
        val on = touched.filterKeys { recs[it].on }.values.toList()
        val off = touched.filterKeys { !recs[it].on }.values.toList()
        if (on.isNotEmpty() || off.isNotEmpty()) lib.setLikes(on, off, wall(c))
        settleUnheld(c, on)
    }

    /**
     * Likes this phone couldn't take (no row could hold the song here) must not look like unlikes made here: their base records
     * say `on: false`, so the next diff doesn't send them back as removed.
     */
    private suspend fun settleUnheld(c: Ctx, on: List<WireSong>) {
        if (on.isEmpty()) return
        val held = SongIndex.of(lib.likes().map { it.s to true })
        val missing = on.filter { !held.has(it) }
        if (missing.isEmpty()) return
        val miss = SongIndex.of(missing.map { it to true })
        c.r.baseLikes = c.r.baseLikes.map { if (it.on && miss.has(it.s)) it.copy(on = false) else it }
        Log.w(TAG, "${missing.size} mirrored like(s) couldn't be held here")
    }

    private fun BasePl.version() = PlVersion(name, items, at, follow?.follow(), ro)

    /** This phone's version of mirrored playlist [mid], or null when it has none. */
    private suspend fun localVersion(c: Ctx, mid: String): PlVersion<WireSong>? {
        val m = c.r.map[mid] ?: return null
        val v = lib.playlist(m.localId) ?: return null
        val base = c.r.basePlaylists[mid]?.version()
        val pv = PlVersion(v.name, v.items, Hlc(0, 0, c.me), v.follow ?: m.follow?.follow(), v.ro)
        val at = if (base != null && PlaylistMerge.sameVersion(pv, base)) base.at else PlaylistMerge.localVersionAt(base?.at, null, c.r.last, wall(c), c.me)
        return pv.copy(at = at)
    }

    private suspend fun applyPl(c: Ctx, op: MirrorOp.Pl, hash: String) {
        val r = c.r
        val local = localVersion(c, op.id)
        val localHash = local?.let { SyncHashes.playlistHash(it.name, it.items, it.follow) }
        val base = r.basePlaylists[op.id]?.version()
        val remote = PlVersion(op.name, op.items, op.at, op.follow, op.ro)
        val res = PlaylistMerge.merge(phone = true, base = base, local = local, localHash = localHash, remote = remote, parent = op.parent, fromPhone = false)
        var held: LocalVersion? = null
        if (res.action == PlaylistMerge.Action.TAKE || res.action == PlaylistMerge.Action.MERGE) {
            val v = res.local
            val mapped = r.map[op.id]
            if (v?.items == null) {
                if (mapped != null) {
                    lib.deletePlaylist(mapped.localId)
                    r.map = r.map - op.id
                }
            } else {
                val id = lib.putPlaylist(mapped?.localId, op.id, v.name, v.items)
                r.map = r.map + (op.id to MappedPl(id, FollowRec.of(v.follow)))
                held = lib.playlist(id)
            }
        }
        if (res.base === base) return // an ignored read-only op keeps the old base
        // Songs this phone couldn't hold (no row for them here) mustn't go back as removed: the base is what it holds.
        val nb = res.base?.let { b ->
            val h = held
            if (h != null && b.items != null && res.local?.items != null && !PlaylistMerge.sameVersion(PlVersion(h.name, h.items, b.at, b.follow), PlVersion(res.local!!.name, res.local!!.items, b.at, b.follow))) {
                Log.w(TAG, "a mirrored playlist lost ${res.local!!.items!!.size - h.items.size} song(s) here")
                b.copy(name = h.name, items = h.items)
            } else {
                b
            }
        }
        r.basePlaylists = if (nb == null) {
            r.basePlaylists - op.id
        } else {
            r.basePlaylists + (op.id to BasePl(nb.name, nb.items, FollowRec.of(nb.follow), nb.ro, nb.at, hash))
        }
    }

    // -------------------------------------------------------------------------------------------- first merge

    private suspend fun join(c: Ctx) {
        val r = c.r
        val cfg = c.cfg
        for (k in Kind.entries) {
            val kc = cfg.of(k)
            if (kc.dir == Dir.OFF) {
                r.joined = r.joined - k
                if (k == Kind.LIKES) {
                    r.question = null
                    r.answer = null
                    r.waiting = false
                }
                continue
            }
            if (r.joined[k] == kc.since) continue
            val view = MirrorView.sinceFilter(r.view, cfg)
            when (k) {
                Kind.LIKES -> joinLikes(c, kc, view)
                Kind.PLAYS -> {
                    // Plays are a grow-only set: the first merge always combines (sync-v1 §7.2, §7.4).
                    if (kc.dir.phoneApplies) {
                        if (view.clearedBefore > r.clearMark) r.clearMark = view.clearedBefore // older plays from others don't come in
                        lib.addPlays(view.plays.filter { it.device != c.me && it.playedAt >= r.clearMark }.map { PlayRec(it.s, it.playedAt, it.device) })
                    }
                    // Only the newest 5,000 of this phone's own plays go on a first merge; older ones count as in the base.
                    val floor = lib.ownPlayAt(FIRST_PLAYS)
                    val own = lib.ownPlays(floor).mapTo(HashSet()) { PlaysMerge.playId(it.s, it.playedAt) }
                    val ids = view.plays.filter { it.device == c.me && it.playedAt >= floor }.map { PlaysMerge.playId(it.s, it.playedAt) }.filter { it in own }
                    r.basePlays = BasePlays(floor, ids.toSet(), r.clearMark)
                    joinedKind(c, k)
                }
                Kind.PLAYLISTS -> {
                    // Joined again (turned off and on): the old base describes another time, so it goes (sync-v1 §7.4).
                    r.basePlaylists = emptyMap()
                    for ((id, vp) in view.playlists) {
                        if (id !in cfg.ids) continue
                        val op = MirrorOp.Pl(id, vp.at, vp.hash, vp.name, vp.items, vp.follow?.follow(), vp.ro)
                        if (kc.dir.phoneApplies && !vp.ro) {
                            applyPl(c, op, vp.hash)
                        } else if (r.basePlaylists[id] == null) {
                            r.basePlaylists = r.basePlaylists + (id to BasePl(vp.name, vp.items, vp.follow, vp.ro, vp.at, vp.hash))
                        }
                    }
                    joinedKind(c, k)
                }
            }
        }
    }

    private suspend fun joinLikes(c: Ctx, kc: KindConfig, view: SpaceView) {
        val r = c.r
        if (!kc.dir.phoneApplies) {
            // Phone → web only: everything here goes; the browser decides how to combine it (it receives).
            r.baseLikes = emptyList()
            joinedKind(c, Kind.LIKES)
            return
        }
        // Who waits for whose `joined` mark (sync-v1 §7.4): the phone compares only once the browsers' likes are in.
        val browsers = c.roster.filterValues { it == TYPE_WEB }.keys
        val markOf = { d: String -> MirrorView.joinMark(Kind.LIKES, kc.since, d) in view.joined }
        r.waiting = when (kc.dir) {
            Dir.BOTH -> {
                val by = kc.by
                by != null && by != c.me && by in browsers && !markOf(by)
            }
            Dir.TO_PHONE -> {
                val by = kc.by
                if (by != null && by in browsers) !markOf(by) else browsers.none(markOf)
            }
            else -> false
        }
        if (r.waiting) {
            r.question = null
            return
        }
        val there = view.likes.filter { it.on }.map { it.s }
        val here = lib.likes()
        val n = FirstMerge.counts(here.map { it.s }, there)
        val choice = when {
            there.isEmpty() || (n.onlyA == 0 && n.onlyB == 0) -> FirstMergeChoice.COMBINE
            here.isEmpty() -> FirstMergeChoice.THEIRS
            r.answer?.since == kc.since -> r.answer!!.choice
            else -> null
        }
        if (choice == null || (choice == FirstMergeChoice.MINE && !kc.dir.phoneSends)) {
            r.question = LikesQuestion(
                oneWay = !kc.dir.phoneSends, here = n.a, there = n.b, both = n.both, combined = n.combined,
                thereName = _status.value.otherName, since = kc.since, externalHere = here.count { it.external },
            )
            return
        }
        val hereIdx = SongIndex.of(here.map { it.s to it })
        val thereIdx = SongIndex.of(there.map { it to true })
        val at = wall(c)
        val taken = there.filter { !hereIdx.has(it) }
        when (choice) {
            FirstMergeChoice.COMBINE -> lib.setLikes(taken, emptyList(), at)
            FirstMergeChoice.THEIRS -> lib.setLikes(taken, here.filter { !thereIdx.has(it.s) }.map { it.s }, at)
            FirstMergeChoice.MINE -> Unit // the library stays; the diff sends this phone's likes and an unlike for each extra there
        }
        r.baseLikes = view.likes.map { BaseLike(it.s, it.on, it.at) }
        if (choice != FirstMergeChoice.MINE) settleUnheld(c, taken)
        if (choice == FirstMergeChoice.THEIRS) {
            // Liked here only through Spotify / YouTube Music: a mirror can't un-like there, so they stay liked here, and the base
            // says `on: false` so they aren't sent back as likes (sync-v1 §7.1).
            val stamp = Hlc.tick(c.r.last, at, c.me).also { c.r.last = it }
            r.baseLikes = r.baseLikes + here.filter { it.external && !thereIdx.has(it.s) }.map { BaseLike(it.s, false, stamp) }
        }
        joinedKind(c, Kind.LIKES)
        r.question = null
        r.answer = null
        c.pendingLikes = null
    }

    /** This phone joined [k]: the next batch says so (`joined`), so a side waiting for it can compare. */
    private fun joinedKind(c: Ctx, k: Kind) {
        val since = c.cfg.of(k).since
        c.r.joined = c.r.joined + (k to since)
        c.r.marks = c.r.marks.filter { it.kind != k } + PendingMark(k, since)
    }

    /** "Mirror new playlists too": playlists made here since it was turned on join the chosen ones. */
    private suspend fun mirrorNewOnes(c: Ctx) {
        val cfg = c.cfg
        if (!cfg.newOnes || !cfg.playlists.dir.phoneSends || !joinedNow(c, Kind.PLAYLISTS)) return
        val from = c.r.newOnesFrom ?: clock()
        c.r.newOnesFrom = from
        val mapped = c.r.map.values.mapTo(HashSet()) { it.localId }
        val fresh = lib.playlists().filter { it.id !in mapped && !it.ro && it.createdAt >= from }
        if (fresh.isNotEmpty()) writeConfig(c, MirrorChange(add = fresh.map { it.id }))
    }

    // -------------------------------------------------------------------------------------------- push

    private suspend fun push(c: Ctx) {
        val r = c.r
        val cfg = c.cfg
        val at = Hlc.tick(r.last, wall(c), c.me)
        val ops = mutableListOf<MirrorOp>()
        var likeOps: List<LikeRec<WireSong>> = emptyList()
        if (joinedNow(c, Kind.LIKES) && cfg.likes.dir.phoneSends) {
            likeOps = LikesMerge.diff(likeRecs(r), lib.likes(), at)
            ops += likeOps.map { MirrorOp.Like(it.s, it.on, it.at) }
        }
        var playIds: Set<String>? = null
        if (joinedNow(c, Kind.PLAYS) && cfg.plays.dir.phoneSends) {
            val own = lib.ownPlays(r.basePlays.floor)
            val local = PlaysState(own, r.clearMark)
            val diff = PlaysMerge.diff(r.basePlays.ids, r.basePlays.clearedBefore, local, at)
            for (o in diff) {
                ops += when (o) {
                    is PlayOp.Play -> MirrorOp.Play(o.s, o.playedAt)
                    is PlayOp.ClearPlays -> MirrorOp.ClearPlays(o.before, o.at, o.all)
                }
            }
            val here = own.mapTo(HashSet()) { PlaysMerge.playId(it.s, it.playedAt) }
            playIds = r.basePlays.ids.filterTo(HashSet()) { it in here } + diff.filterIsInstance<PlayOp.Play<WireSong>>().map { PlaysMerge.playId(it.s, it.playedAt) }
        }
        val plBases = mutableListOf<Pair<String, BasePl>>()
        if (joinedNow(c, Kind.PLAYLISTS) && cfg.playlists.dir != Dir.OFF) {
            for ((mid, m) in r.map) {
                if (mid !in cfg.ids) continue
                val base = r.basePlaylists[mid]
                val v = lib.playlist(m.localId)
                if (v == null) {
                    // Deleted here while it still mirrors: deleted on every device.
                    if (base != null && base.items != null && (cfg.playlists.dir.phoneSends || base.ro)) {
                        val del = MirrorOp.Pl(mid, at, base.hash, base.name, null, null, base.ro)
                        ops += del
                        plBases += mid to BasePl(base.name, null, null, base.ro, at, SyncHashes.playlistHash(base.name, null, null))
                    }
                    continue
                }
                if (!cfg.playlists.dir.phoneSends && !v.ro) continue // only the read-only ones go phone → web whatever the direction
                val follow = v.follow ?: m.follow?.follow()
                val pv = PlVersion(v.name, v.items, at, follow, v.ro)
                if (base != null && base.items != null && PlaylistMerge.sameVersion(pv, base.version()) && base.ro == v.ro) continue
                ops += MirrorOp.Pl(mid, at, base?.hash, v.name, v.items, follow, v.ro)
                plBases += mid to BasePl(v.name, v.items, FollowRec.of(follow), v.ro, at, SyncHashes.playlistHash(v.name, v.items, follow))
            }
        }
        val marks = r.marks.filter { cfg.of(it.kind).dir != Dir.OFF && cfg.of(it.kind).since == it.since }
        ops += marks.map { MirrorOp.Joined(it.kind, it.since) }
        if (ops.isEmpty()) {
            r.marks = emptyList()
            playIds?.let { r.basePlays = movedFloor(c, r.basePlays.copy(ids = it)) }
            return
        }
        for (o in ops) o.stamp?.let { if (r.last == null || it > r.last!!) r.last = it }
        send(c, ops)
        // In: the base moves.
        if (likeOps.isNotEmpty()) {
            r.baseLikes = LikesMerge.apply(likeRecs(r), likeOps).map { BaseLike(it.s, it.on, it.at) }
        }
        playIds?.let { r.basePlays = movedFloor(c, r.basePlays.copy(ids = it, clearedBefore = maxOf(r.basePlays.clearedBefore, r.clearMark))) }
        for ((id, b) in plBases) r.basePlaylists = r.basePlaylists + (id to b)
        r.marks = r.marks.filter { it !in marks }
        r.lastPushAt = clock()
        Log.i(TAG, "pushed ${ops.size} op(s)")
    }

    /** Every own play is in the base now: the floor moves up to two days ago, and the ids before it go (sync-v1 §7.2). */
    private fun movedFloor(c: Ctx, b: BasePlays): BasePlays {
        val floor = maxOf(b.floor, wall(c) - FLOOR_LAG_MS)
        // Keyed `textKey|playedAt`: an id's time is after its last `|`.
        return BasePlays(floor, b.ids.filterTo(HashSet()) { (it.substringAfterLast('|').toLongOrNull() ?: Long.MAX_VALUE) >= floor }, b.clearedBefore)
    }

    /** Seals and posts [ops], in as many batches as one part each needs (768,000 bytes of gzip); saved after each. */
    private suspend fun send(c: Ctx, ops: List<MirrorOp>) {
        val key = dataKey(c, c.sp.epoch) ?: throw Settle()
        val batches = mutableListOf<SyncEnvelope>()
        fun cut(part: List<MirrorOp>) {
            val env = SyncCrypto.seal(key, c.sp.spaceId, c.sp.epoch, SyncCrypto.Place.LOG, MirrorWire.opsJson(c.me, part))
            if (env.c.length.toLong() * 3 / 4 - 16 <= SyncCrypto.PART_BYTES || part.size < 2) {
                batches += env
                return
            }
            cut(part.subList(0, part.size / 2))
            cut(part.subList(part.size / 2, part.size))
        }
        cut(ops)
        for (env in batches) {
            var res = api.postLog(c.auth, c.sp.spaceId, env)
            if (res is SyncResult.Error && res.code == SyncErrorCode.COMPACT) {
                compact(c, wholeLog = true)
                res = api.postLog(c.auth, c.sp.spaceId, env)
            }
            val posted = res.get()
            // The batch is next in the log: the view takes it now (else the next pull reads it back, as anyone's).
            if (posted.seq == c.r.seen + 1) applyEntry(c, LogEntry(posted.seq, c.me, posted.serverAt.takeIf { it > 0 } ?: wall(c), env))
            records.save(c.r)
        }
    }

    // -------------------------------------------------------------------------------------------- compaction

    private suspend fun compactIfDue(c: Ctx) {
        val r = c.r
        if (r.gaps.isNotEmpty()) return // it can't read all of the log: a browser compacts
        val snapUpto = maxOf(r.snapUpto, c.info.snapshot?.uptoSeq ?: 0)
        if (c.info.compactDue || r.seen - snapUpto >= COMPACT_BATCHES || r.logBytes >= COMPACT_BYTES) compact(c, c.info.compactDue)
    }

    /**
     * Writes the view as the snapshot after everything this phone has read (`uptoSeq` = the head after a key change), in parts.
     * Never over log entries this phone couldn't read: that would erase them for everyone (sync-v1 §5.4).
     */
    private suspend fun compact(c: Ctx, wholeLog: Boolean) {
        for (i in 0 until 2) {
            if (wholeLog || i > 0) {
                c.info = api.space(c.auth, c.sp.spaceId).get()
                if (c.info.rotationDue || c.info.epoch > c.sp.epoch) throw Settle()
                pull(c)
            }
            val r = c.r
            val uptoSeq = r.seen
            if (uptoSeq == 0L) return
            if (r.gaps.isNotEmpty()) throw CantCompact()
            val key = dataKey(c, c.sp.epoch) ?: throw Settle()
            val st = MirrorView.stateOf(MirrorView.sinceFilter(r.view, c.cfg), uptoSeq, wall(c))
            val envs = try {
                SyncCrypto.sealParts(key, c.sp.spaceId, c.sp.epoch, { p, n -> SyncCrypto.Place.snapshot(uptoSeq, p, n) }, MirrorWire.stateJson(st))
            } catch (e: SyncCryptoException) {
                throw ApiError(SyncResult.Error(413, SyncErrorCode.SPACE_FULL))
            }
            var stale = false
            for ((p, env) in envs.withIndex()) {
                when (val res = api.putSnapshot(c.auth, c.sp.spaceId, uptoSeq, p, envs.size, env)) {
                    is SyncResult.Ok -> Unit
                    is SyncResult.Error -> if (res.code == SyncErrorCode.STALE && i == 0) {
                        stale = true
                        break
                    } else {
                        throw ApiError(res)
                    }
                    is SyncResult.Unreachable -> throw Offline()
                }
            }
            if (stale) continue
            r.snapUpto = uptoSeq
            r.logBytes = 0
            c.info = c.info.copy(compactDue = false, snapshot = SnapshotMeta(uptoSeq, envs.size, c.sp.epoch))
            Log.i(TAG, "compacted the log up to $uptoSeq in ${envs.size} part(s)")
            return
        }
    }

    companion object {
        private const val TAG = "WebLinkMirror"
        private const val TYPE_WEB = "web"
        const val RESULT_OK = "ok"

        /** After this many log batches since the snapshot, a device compacts (sync-v1 §5.4; the server refuses past 2,000). */
        const val COMPACT_BATCHES = 500
        const val COMPACT_BYTES = 4L * 1024 * 1024

        /** Plays a first merge sends at most (spec §7.2). */
        const val FIRST_PLAYS = 5_000

        /** How far behind now the plays base's floor stays (a play is recorded when it ends, with when it started). */
        const val FLOOR_LAG_MS = 2L * 86_400_000

        const val UNFINISHED = "Mirroring didn't finish. It tries again shortly."
        const val SLOW = "Mirroring waits a little: too many changes at once."
        const val FULL = "Your linked devices hold too much to mirror more."
        const val CANT_COMPACT = "Mirroring waits for your browser to catch up."

        private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** `m_` + 16 of A–Z a–z 0–9 (sync-v1 §1), unbiased. */
        fun mintMirrorId(): String {
            val sb = StringBuilder("m_")
            while (sb.length < 18) {
                val b = SyncCrypto.randomBytes(1)[0].toInt() and 0xff
                if (b < 248) sb.append(ID_CHARS[b % 62])
            }
            return sb.toString()
        }
    }
}
