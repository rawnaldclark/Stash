package com.stash.feature.record.archive

import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.entity.RecordPlayEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The plays brought into Your record ([RecordPlayDao], `record_plays`: a Spotify export today) as the record's rows, read a
 * page at a time in id order and handed on as they are: they were written by the web's rules already.
 */
class ImportedArchive(
    private val dao: RecordPlayDao,
    private val pageSize: Int = PAGE,
) : RecordArchiveSource {
    override suspend fun stream(chunkSize: Int, emit: suspend (rows: List<ArchivePlay>, last: Boolean) -> Unit) {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val out = Rechunk(chunkSize, emit)
        var after = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.pageAfter(after, pageSize)
            for (r in page) out.add(r.toArchivePlay())
            if (page.size < pageSize) break
            after = page.last().id
        }
        out.finish()
    }

    companion object {
        /** Rows read at a time. */
        const val PAGE = 5_000
    }
}

fun RecordPlayEntity.toArchivePlay(): ArchivePlay = ArchivePlay(id = id, t = t, a = a, al = al, sid = sid, s = s, ms = ms, f = f)

fun ArchivePlay.toEntity(): RecordPlayEntity = RecordPlayEntity(id = id, t = t, a = a, al = al, sid = sid, s = s, ms = ms, f = f)

/**
 * Several sources as one archive, in the order given, re-chunked to the size asked for (the record keeps the first row of an
 * id, so the order is which source's row it keeps when two share one: an imported play before the phone's own, though the two
 * never overlap, being different players).
 */
class StackedArchive(private val sources: List<RecordArchiveSource>) : RecordArchiveSource {
    override suspend fun stream(chunkSize: Int, emit: suspend (rows: List<ArchivePlay>, last: Boolean) -> Unit) {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val out = Rechunk(chunkSize, emit)
        for (source in sources) source.stream(chunkSize) { rows, _ -> rows.forEach { out.add(it) } }
        out.finish()
    }
}

/**
 * Rows handed on in chunks of exactly [size]; a full chunk waits for the next row before it goes, so the last chunk is the
 * one [finish] marks (no rows: one empty chunk, marked last).
 */
internal class Rechunk(private val size: Int, private val emit: suspend (List<ArchivePlay>, Boolean) -> Unit) {
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
