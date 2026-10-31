package com.stash.feature.record.archive

import com.stash.core.data.db.entity.RecordPlayEntity

/** A row of `record_plays` as the record's row (they were written by the web's rules already). */
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
