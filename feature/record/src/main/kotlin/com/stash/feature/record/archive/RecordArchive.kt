package com.stash.feature.record.archive

import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.entity.RecordPlayEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The record's archive as the page receives it, every source stacked and a listen counted once (spec §7): the imported plays
 * that aren't scrobbles (the Spotify export), then this phone's own plays and skips ([own], read live), then the Last.fm
 * scrobbles, leaving out each one a play made here covers ([StashCover]).
 *
 * In the app the page doesn't stack the sources itself (its `stack` only drops scrobbles a `stash:live` play covers, and the app
 * sends no `stash:live`): this is where it happens. A scrobble a Spotify play covers is never written in the first place
 * (plays.ts `planScrobbles`, and `planMerge` for an export brought after), so only this phone's plays are checked here; they are
 * read live and keep growing after any import (a song played after a top-up brought its scrobble in), so the check is made on
 * every read, never stored.
 *
 * Read a page at a time by id, so a decade of plays is never all in memory. The three never share an id once the covered
 * scrobbles are out, so the order only decides which row the page keeps if two ever did: the Spotify play, then this phone's.
 */
class RecordArchive(
    /** The imported plays that aren't scrobbles (the Spotify export's). */
    private val imported: RecordArchiveSource,
    /** This phone's own plays and skips. */
    private val own: RecordArchiveSource,
    /** The Last.fm scrobbles. */
    private val scrobbles: RecordArchiveSource,
) : RecordArchiveSource {
    constructor(dao: RecordPlayDao, own: RecordArchiveSource) : this(KindArchive(dao, scrobbles = false), own, KindArchive(dao, scrobbles = true))

    override suspend fun stream(chunkSize: Int, emit: suspend (rows: List<ArchivePlay>, last: Boolean) -> Unit) {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val out = Rechunk(chunkSize, emit)
        imported.stream(chunkSize) { rows, _ -> for (r in rows) out.add(r) }
        val cover = StashCover.Builder()
        own.stream(chunkSize) { rows, _ ->
            for (r in rows) {
                cover.add(r)
                out.add(r)
            }
        }
        val covers = cover.build()
        scrobbles.stream(chunkSize) { rows, _ -> for (r in rows) if (!covers.covers(r)) out.add(r) }
        out.finish()
    }
}

/** One kind of imported play ([scrobbles], or every other), a page at a time by id (the primary key). */
internal class KindArchive(private val dao: RecordPlayDao, private val scrobbles: Boolean) : RecordArchiveSource {
    override suspend fun stream(chunkSize: Int, emit: suspend (rows: List<ArchivePlay>, last: Boolean) -> Unit) {
        require(chunkSize > 0) { "chunkSize must be positive" }
        var after = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page: List<RecordPlayEntity> = dao.pageAfterOf(after, scrobbles, chunkSize)
            val last = page.size < chunkSize
            emit(page.map { it.toArchivePlay() }, last)
            if (last) break
            after = page.last().id
        }
    }
}
