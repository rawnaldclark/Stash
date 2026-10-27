package com.stash.feature.record.lastfm

import com.stash.core.data.db.SQLITE_BIND_LIMIT
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.toEntity
import com.stash.feature.record.imports.RecordImportStore

/**
 * The Last.fm import's archive in the database (`record_plays`) and its mark beside the import record ([RecordImportStore]),
 * for one run. A page is checked against the database as it is (which ids are kept, a title's plays by a range on the primary
 * key), so a heavy archive is never all in memory. A title's plays are kept for the run once asked: the run holds the write
 * lock, and its own writes are scrobbles, which never count as a title's plays.
 */
internal class DaoScrobbleStore(private val dao: RecordPlayDao, private val store: RecordImportStore) : ScrobbleStore {
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

    override suspend fun add(rows: List<ArchivePlay>) {
        if (rows.isNotEmpty()) dao.apply(rows.map { it.toEntity() }, emptyList())
    }

    override suspend fun deleteScrobbles(): Int = dao.deleteScrobbles()
}
