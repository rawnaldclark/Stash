package com.stash.feature.record.archive

import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.weblibrary.WebLibraryListens
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Where the record's plays come from: rows in chunks of at most `chunkSize`, the last one marked. */
fun interface RecordArchiveSource {
    /**
     * Calls [emit] for each chunk in turn, at least once: an empty archive is one empty chunk, marked last. Each chunk's list is
     * the receiver's to keep.
     */
    suspend fun stream(chunkSize: Int, emit: suspend (rows: List<ArchivePlay>, last: Boolean) -> Unit)
}

/**
 * This phone's own plays and skips as the record's Stash source (PLAN "Data in the app": read live, never stored twice). The
 * rows are the ones Your record on the web makes from this phone's export: each entry mapped as the export maps it
 * ([WebLibraryListens.play], [WebLibraryListens.skip]: the same queries, the same caps, newest first), then taken by the web's
 * rules ([PhoneRows]).
 *
 * Streamed, so a decade of plays is never all in memory: the plays are read a page at a time and handed on a chunk at a time.
 * planPhone's "each id once" still holds exactly: an id carries its start, so only plays with one start can share one, and the
 * pages bring those together (newest first, by start). Skips are the few, so they are read first and kept aside: a play takes
 * an id before a skip does, as planPhone adds every play first.
 */
class OwnArchive(
    private val dao: WebLibraryExportDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = WebLibraryListens.PAGE,
    private val maxPlays: Int = WebLibraryListens.MAX_PLAYS,
    private val maxSkips: Int = WebLibraryListens.MAX_SKIPS,
) : RecordArchiveSource {

    override suspend fun stream(chunkSize: Int, emit: suspend (rows: List<ArchivePlay>, last: Boolean) -> Unit) {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val at = now()
        val skips = LinkedHashMap<String, ArchivePlay>()
        for (p in readSkips(at).sortedWith(PhoneRows.ORDER)) {
            val r = PhoneRows.phoneRow(p, skip = true)
            skips.putIfAbsent(r.id, r)
        }

        val out = Chunks(chunkSize, emit)
        // The plays of one start, in the file's order, decided together when the next start comes
        val group = ArrayList<PhonePlay>()
        suspend fun flushGroup() {
            val ids = HashSet<String>()
            for (p in group.sortedWith(PhoneRows.ORDER)) {
                val r = PhoneRows.phoneRow(p, skip = false)
                if (!ids.add(r.id)) continue
                skips.remove(r.id)
                out.add(r)
            }
            group.clear()
        }

        var entries = 0
        var cursorAt = Long.MAX_VALUE
        var cursorId = Long.MAX_VALUE
        pages@ while (entries < maxPlays) {
            currentCoroutineContext().ensureActive()
            val rows = dao.ownPlaysBefore(cursorAt, cursorId, pageSize)
            for (row in rows) {
                cursorAt = row.startedAt
                cursorId = row.id
                // The export's entry (null: the file leaves it out), then the web's reading of it (null: a broken row there)
                val e = WebLibraryListens.play(row) ?: continue
                entries++
                PhoneRows.read(e.t, e.a, e.al, e.s, e.ms, e.d, at, skip = false)?.let { p ->
                    if (group.isNotEmpty() && group[0].s != p.s) flushGroup()
                    group += p
                }
                if (entries >= maxPlays) break@pages
            }
            if (rows.size < pageSize) break
        }
        flushGroup()
        for (r in skips.values) out.add(r)
        out.finish()
    }

    /** Every skip the export would carry (the newest [maxSkips]), as the web reads them. */
    private suspend fun readSkips(at: Long): List<PhonePlay> {
        val out = ArrayList<PhonePlay>()
        var entries = 0
        var cursorAt = Long.MAX_VALUE
        var cursorId = Long.MAX_VALUE
        pages@ while (entries < maxSkips) {
            currentCoroutineContext().ensureActive()
            val rows = dao.ownSkipsBefore(cursorAt, cursorId, pageSize)
            for (row in rows) {
                cursorAt = row.skippedAt
                cursorId = row.id
                val e = WebLibraryListens.skip(row) ?: continue
                entries++
                PhoneRows.read(e.t, e.a, e.al, e.s, e.ms, null, at, skip = true)?.let { out += it }
                if (entries >= maxSkips) break@pages
            }
            if (rows.size < pageSize) break
        }
        return out
    }

    /**
     * Rows handed on in chunks of [size]. A full chunk waits for the next row before it goes, so the last chunk is always the
     * one [finish] marks (an empty archive: one empty chunk).
     */
    private class Chunks(private val size: Int, private val emit: suspend (List<ArchivePlay>, Boolean) -> Unit) {
        private var pending = ArrayList<ArchivePlay>(minOf(size, INITIAL))

        suspend fun add(r: ArchivePlay) {
            if (pending.size == size) {
                emit(pending, false)
                pending = ArrayList(minOf(size, INITIAL))
            }
            pending += r
        }

        suspend fun finish() = emit(pending, true)

        private companion object {
            const val INITIAL = 1024
        }
    }
}
