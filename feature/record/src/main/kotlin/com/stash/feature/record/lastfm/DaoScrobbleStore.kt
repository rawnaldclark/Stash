package com.stash.feature.record.lastfm

import com.stash.core.data.db.SQLITE_BIND_LIMIT
import com.stash.core.data.db.dao.RecordLengthDao
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.entity.RecordLengthEntity
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.toEntity
import com.stash.feature.record.imports.RecordImportStore

/**
 * The Last.fm import's archive in the database (`record_plays`) and its mark beside the import record ([RecordImportStore]),
 * for one run. A page is checked against the database as it is (which ids are kept, a title's plays by a range on the primary
 * key), so a heavy archive is never all in memory. A title's plays are kept for the run once asked: the run holds the write
 * lock, and its own writes are scrobbles, which never count as a title's plays.
 */
internal class DaoScrobbleStore(
    private val dao: RecordPlayDao,
    private val store: RecordImportStore,
    /** The lengths pass's songs it couldn't time: they go with the scrobbles. */
    private val lengths: RecordLengthDao? = null,
) : ScrobbleStore {
    private val titles = HashMap<String, List<PlaySpan>>()

    override suspend fun kept(ids: List<String>): Set<String> {
        val out = HashSet<String>()
        for (part in ids.chunked(SQLITE_BIND_LIMIT)) out += dao.keptIds(part)
        return out
    }

    override suspend fun playsOf(titleFold: String): List<PlaySpan> = titles.getOrPut(titleFold) {
        dao.playsIn("$titleFold|", "$titleFold}").map { PlaySpan(it.s, it.s + it.ms) }
    }

    override suspend fun mark(): LastfmMark? = store.info.value.lastfm

    override suspend fun setMark(mark: LastfmMark) = store.setLastfm(mark)

    override suspend fun clearMark() = store.clearLastfm()

    override suspend fun knownLengths(): Map<String, Long> = Scrobbles.knownLengths(dao.doneLengths().map { Triple(it.t, it.a, it.ms) })

    override suspend fun held(): Int = dao.scrobbleCount()

    override suspend fun add(rows: List<ArchivePlay>) {
        if (rows.isNotEmpty()) dao.apply(rows.map { it.toEntity() }, emptyList())
    }

    override suspend fun deleteScrobbles(): Int {
        val n = dao.deleteScrobbles()
        lengths?.clear()
        return n
    }
}

/**
 * The lengths pass's archive in the database, for one slice: the guessed scrobbles by spelling ([RecordPlayDao.guessed]), a
 * song's rows timed by their id range ([RecordPlayDao.timeAll]), and its mark: the songs it couldn't time in `record_lengths`
 * ([RecordLengthDao]), its count and time beside the import record ([RecordImportStore.setLengths]).
 *
 * The web saves its whole mark after every answer; a long history leaves thousands of songs Last.fm can't time, so each save here
 * writes only what moved since the last (the first, everything the plan pruned away): the same mark, row for row.
 */
internal class DaoLengthsStore(
    private val dao: RecordPlayDao,
    private val lengths: RecordLengthDao,
    private val store: RecordImportStore,
) : LengthsStore {
    /** The mark as the table holds it now, once read. */
    private var written: LengthsMark? = null
    private var read = false

    override suspend fun guessed(): List<GuessedSpelling> = dao.guessed().map { GuessedSpelling(it.t, it.a, it.n) }

    override suspend fun knownLengths(): Map<String, Long> = Scrobbles.knownLengths(dao.doneLengths().map { Triple(it.t, it.a, it.ms) })

    override suspend fun mark(): LengthsMark? {
        val rows = lengths.all()
        val at = store.lengthsAt
        val m = if (rows.isEmpty() && at == null) {
            null
        } else {
            LengthsMark(
                none = rows.filter { it.kind == RecordLengthEntity.NONE }.associate { it.key to it.at },
                failed = rows.filter { it.kind == RecordLengthEntity.FAILED }.associate { it.key to it.at },
                timed = store.info.value.lengthsTimed,
                at = at ?: 0,
            )
        }
        written = m
        read = true
        return m
    }

    override suspend fun setMark(mark: LengthsMark) {
        if (!read) mark()
        val before = written
        val was = HashMap<String, Pair<Int, Long>>()
        before?.none?.forEach { (k, at) -> was[k] = RecordLengthEntity.NONE to at }
        before?.failed?.forEach { (k, at) -> was[k] = RecordLengthEntity.FAILED to at }
        val now = HashMap<String, Pair<Int, Long>>()
        mark.none.forEach { (k, at) -> now[k] = RecordLengthEntity.NONE to at }
        mark.failed.forEach { (k, at) -> now[k] = RecordLengthEntity.FAILED to at }
        val put = now.filter { (k, v) -> was[k] != v }.map { (k, v) -> RecordLengthEntity(k, v.first, v.second) }
        val drop = was.keys.filter { it !in now }
        if (put.isNotEmpty() || drop.isNotEmpty()) lengths.apply(put, drop)
        store.setLengths(mark.timed, mark.at)
        written = mark
    }

    override suspend fun time(spellings: List<GuessedSpelling>, ms: Long): Int {
        if (spellings.isEmpty()) return 0
        return dao.timeAll(
            spellings.map {
                val fold = SongKey.fold(it.t)
                RecordPlayDao.Spelling(it.t, it.a, "$fold|", "$fold}")
            },
            ms,
        )
    }
}
