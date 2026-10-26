package com.stash.feature.record.imports

import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.toEntity
import com.stash.feature.record.importer.ArchiveListens
import com.stash.feature.record.importer.KeptPlay
import com.stash.feature.record.importer.RecordArchiveStore

/** The record's archive in the database (`record_plays`), as the import reads and writes it: a page at a time, a batch at a time. */
internal class DaoArchiveStore(private val dao: RecordPlayDao, private val pageSize: Int = PAGE) : RecordArchiveStore {
    override suspend fun forEachPage(block: (List<KeptPlay>) -> Unit) {
        var after = ""
        while (true) {
            val page = dao.keptAfter(after, pageSize)
            if (page.isEmpty()) break
            block(page.map { KeptPlay(it.id, it.t, it.s, it.ms, it.f) })
            if (page.size < pageSize) break
            after = page.last().id
        }
    }

    override suspend fun apply(add: List<ArchivePlay>, drop: List<String>) = dao.apply(add.map { it.toEntity() }, drop)

    override suspend fun count(): Int = dao.count()

    override suspend fun listens(): ArchiveListens = dao.listens().let { ArchiveListens(it.spotify, it.lastfm, it.stash, it.first, it.last) }

    override suspend fun clear() = dao.clear()

    private companion object {
        const val PAGE = 5_000
    }
}
