package com.stash.feature.record.lastfm

import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags

/** [ScrobbleArchive] over rows in memory (the tests; the web's own `have` is the same idea). */
class MemoryScrobbleArchive(rows: Iterable<ArchivePlay>) : ScrobbleArchive {
    private val ids = HashSet<String>()
    private val plays = HashMap<String, MutableList<PlaySpan>>()

    init {
        rows.forEach(::add)
    }

    /** [r] is kept now (a page's rows once written). */
    fun add(r: ArchivePlay) {
        ids += r.id
        if (r.f and RecordFlags.LASTFM == 0) plays.getOrPut(SongKey.fold(r.t)) { ArrayList() } += PlaySpan(r.s, r.s + r.ms)
    }

    override suspend fun kept(ids: List<String>): Set<String> = ids.filterTo(HashSet()) { it in this.ids }

    override suspend fun playsOf(titleFold: String): List<PlaySpan> = plays[titleFold].orEmpty()
}
