package com.stash.feature.record.importer

import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags

/**
 * The record's archive in memory (archive.ts `memoryArchive`), for tests: plays by id. Pages come in an order of its own (by a
 * hash of the id), never the order written, so a merge that depended on the archive's order would show it.
 */
class MemoryArchive(private val pageSize: Int = 997) : RecordArchiveStore {
    val plays = LinkedHashMap<String, ArchivePlay>()
    var batches = 0
        private set

    override suspend fun apply(add: List<ArchivePlay>, drop: List<String>) {
        batches++
        for (p in add) plays[p.id] = p
        for (id in drop) plays.remove(id)
    }

    override suspend fun count(): Int = plays.size

    override suspend fun listens(): ArchiveListens {
        val l = plays.values.filter { it.ms >= SpotifyImporter.LISTEN_MS }
        return ArchiveListens(
            spotify = l.count { it.f and (RecordFlags.LASTFM or RecordFlags.STASH) == 0 },
            lastfm = l.count { it.f and RecordFlags.LASTFM != 0 },
            stash = l.count { it.f and RecordFlags.LASTFM == 0 && it.f and RecordFlags.STASH != 0 },
            first = l.minOfOrNull { it.s },
            last = l.maxOfOrNull { it.s },
        )
    }

    override suspend fun clear() = plays.clear()

    override suspend fun forEachPage(block: (List<KeptPlay>) -> Unit) {
        plays.values.sortedBy { it.id.hashCode() }.map { KeptPlay(it.id, it.t, it.s, it.ms, it.f) }.chunked(pageSize).forEach(block)
    }

    /** Every id, sorted as JavaScript's `sort()` sorts strings (UTF-16 units). */
    fun sortedIds(): List<String> = plays.keys.sorted()
}
