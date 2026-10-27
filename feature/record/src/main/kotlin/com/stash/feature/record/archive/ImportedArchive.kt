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

/**
 * As stored: each word well-formed first ([wellFormed]), so a lone surrogate (a title the web's `str` cut through an emoji) is
 * stored as U+FFFD rather than handed to SQLite, whose UTF-16 reading pairs it with whatever follows (in an id, the `|`).
 */
fun ArchivePlay.toEntity(): RecordPlayEntity = RecordPlayEntity(
    id = wellFormed(id),
    t = wellFormed(t),
    a = wellFormed(a),
    al = al?.let(::wellFormed),
    sid = sid?.let(::wellFormed),
    s = s,
    ms = ms,
    f = f,
)

/** [s] with each lone surrogate as U+FFFD (as UTF-8 can hold it); [s] itself, not a copy, when it has none (nearly always). */
internal fun wellFormed(s: String): String {
    var out: StringBuilder? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val pair = c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
        if (!pair && c.isSurrogate()) {
            if (out == null) out = StringBuilder(s.length).append(s, 0, i)
            out.append('�')
        } else {
            out?.append(c)
            if (pair) out?.append(s[i + 1])
        }
        i += if (pair) 2 else 1
    }
    return out?.toString() ?: s
}

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
