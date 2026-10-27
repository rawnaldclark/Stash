package com.stash.feature.record.importer

import com.stash.feature.record.archive.ArchivePlay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A file the person brought: its name as they saw it, and its copy in the app's own storage. */
data class PickedFile(val name: String, val file: File)

/** Where the record's imported plays are kept (the `record_plays` table; a map in tests). */
interface RecordArchiveStore : KeptPlays {
    /** One batch of an import, in one transaction: [add] written, then [drop] removed. */
    suspend fun apply(add: List<ArchivePlay>, drop: List<String>)

    suspend fun count(): Int

    /** The archive's listens (30 s or more) by source, and the first and last listen's start. */
    suspend fun listens(): ArchiveListens

    /** Every imported play gone (Remove import). */
    suspend fun clear()
}

/** The archive's listens (30 s or more) by source, and when the first and last started (null with none). */
data class ArchiveListens(val spotify: Int, val lastfm: Int, val stash: Int, val first: Long?, val last: Long?) {
    val total: Int get() = spotify + lastfm + stash
}

/** What an import is doing (importer.ts `ImportProgress`). */
sealed interface ImportProgress {
    /** Copying the files in and opening them. */
    data object Open : ImportProgress

    /** Reading: [done] of [total] files, [part] of all their bytes, the latest [year] of the plays read so far. */
    data class Read(val done: Int, val total: Int, val part: Float, val year: Int?) : ImportProgress

    /** Saving: [done] of [total] plays written. */
    data class Write(val done: Int, val total: Int) : ImportProgress

    /** Bringing the playlists and liked songs into the library: [done] of [total] songs matched. */
    data class Library(val done: Int, val total: Int) : ImportProgress
}

/**
 * What the person chose in the preview (the web's `commit` options): [playlists], the indexes of the export's playlists to bring
 * into the library (null: all of them); [likes], its liked songs or not. Kept with `pending`, so a commit taken up again after the
 * app's death brings in the same.
 */
data class ImportChoice(val playlists: Set<Int>? = null, val likes: Boolean = true) {
    /** Whether the export's playlist [i] comes in. */
    fun takes(i: Int): Boolean = playlists == null || i in playlists

    companion object {
        val ALL = ImportChoice()
    }
}

/**
 * What the files would add, before anything is written (importer.ts `Preview`): the listens they add and the plays already here,
 * the time and span of the new listens, the playlists and liked songs in them, and what couldn't be read.
 */
data class ImportPreview(
    /** New listens (30 s or more). */
    val plays: Int,
    /** Plays already here (or repeated in the files). */
    val same: Int,
    /** Account-data plays that are Extended plays in the same files: neither new nor "already here". */
    val overlap: Int,
    val listenedMs: Long,
    /** First and last new listen's start (0 when none). */
    val first: Long,
    val last: Long,
    /** UTC calendar years with new listens. */
    val years: Int,
    val playlists: List<ExportPlaylistCount>,
    val likes: Int,
    val hidden: Int,
    /** Extended history found: without it only the last year is here. */
    val extended: Boolean,
    /** Plays from Stash on the phone this import adds: the app's own plays are already in the record, so always 0. */
    val phone: Int,
    val unreadable: Int,
    /** Stash's Liked Songs is kept on this phone: the liked songs brought in would download too (the preview says so). */
    val likesDownload: Boolean = false,
) {
    /** It brings something: plays to listen back to, or playlists or liked songs for the library (service.ts `choose`). */
    val anything: Boolean get() = plays > 0 || phone > 0 || playlists.isNotEmpty() || likes > 0
}

/** What [SpotifyImporter.prepare] read, waiting for [SpotifyImporter.commit]: the export itself, never a plan. */
internal class Prepared(val preview: ImportPreview, val bundle: ExportBundle, val files: List<PickedFile>)

/** What a commit wrote. */
data class CommitResult(
    /** New listens written. */
    val listens: Int,
    /** Plays written (listens and skips) and dropped. */
    val written: Int,
    val dropped: Int,
)

/** The files hold nothing of a Spotify export (importer.ts `NotAnExport`). */
class NotAnExport : Exception("not a Spotify export")

/**
 * What the imports have brought, for Settings and the Home card (archive.ts `ImportRecord`). [plays] counts the listens over the
 * whole archive, the number every screen shows.
 */
data class ImportRecord(
    /** First import, latest import (epoch ms). */
    val firstAt: Long,
    val lastAt: Long,
    val imports: Int,
    val plays: Int,
    val bySource: BySource? = null,
    /** The first and last listen (epoch ms; 0 with none): the archive's and this phone's own plays together, as taste has them. */
    val first: Long,
    val last: Long,
    /** Playlists and liked songs brought in (counted; the library holds them). */
    val playlists: Int = 0,
    val likes: Int = 0,
) {
    data class BySource(val spotify: Int, val lastfm: Int, val stash: Int)
}

/**
 * Bring your Spotify, the work itself (stash-player `src/lib/spotify/importer.ts`), in two steps (spec §5): [prepare] reads the
 * files and works out what they would add, writing nothing (the preview, which waits for the person); [commit] plans the merge
 * again against the archive as it is then (never the preview's plan), and writes it in batches of [BATCH], adds before drops.
 */
internal class SpotifyImporter(
    private val archive: RecordArchiveStore,
    /** Where zips inside zips are inflated while they are read (emptied after). */
    private val scratch: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * Reads [files] (zips opened, nested ones too; loose `.json` files as they are) and plans the merge. Throws [NotAnExport]
     * when none of them is a file of an export.
     */
    suspend fun prepare(
        files: List<PickedFile>,
        now: Long,
        checkpoint: () -> Unit = {},
        /** The zips as the intake's look inside opened them (what it inflated is read, not inflated again); this call closes it. */
        zip: ExportZip? = null,
        progress: (ImportProgress) -> Unit = {},
    ): Prepared {
        progress(ImportProgress.Open)
        val bundle = read(files, now, checkpoint, zip, progress)
        if (bundle.files.found == 0) throw NotAnExport()
        val plan = PlanMerge.plan(archive, bundle.plays)
        val plays = bundle.plays
        var listens = 0
        var listenedMs = 0L
        var first = 0L
        var last = 0L
        val years = HashSet<Int>()
        for (i in plan.add) {
            val ms = plays.ms(i)
            if (ms < LISTEN_MS) continue
            val s = plays.start(i)
            listens++
            listenedMs += ms
            first = if (first != 0L) minOf(first, s) else s
            last = maxOf(last, s)
            years += Instant.ofEpochMilli(s).atOffset(ZoneOffset.UTC).year
        }
        val preview = ImportPreview(
            plays = listens,
            same = plan.same,
            overlap = plan.overlap,
            listenedMs = listenedMs,
            first = first,
            last = last,
            years = years.size,
            playlists = bundle.playlists.map { ExportPlaylistCount(it.name, it.items.size) },
            likes = bundle.likes.size,
            hidden = bundle.hidden.size,
            extended = bundle.files.extended > 0,
            phone = 0,
            unreadable = bundle.unreadable,
        )
        return Prepared(preview, bundle, files)
    }

    /**
     * The files read into an export, under one [ImportBudget] for the whole import (zips' scratch files gone once read). [zip]:
     * the intake's, with its budget and what it opened; a fresh one otherwise.
     */
    fun read(
        files: List<PickedFile>,
        now: Long,
        checkpoint: () -> Unit = {},
        zip: ExportZip? = null,
        progress: (ImportProgress) -> Unit = {},
    ): ExportBundle {
        val z = zip ?: newZip()
        return z.use {
            val entries = openFiles(files, it)
            ExportReader(now, zone, checkpoint, it.budget).read(entries) { done, total, bytes, totalBytes, year ->
                progress(ImportProgress.Read(done, total, if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f, year))
            }
        }
    }

    /** A zip reader for one import, its budget measured against the scratch folder's free space. */
    fun newZip(): ExportZip = ExportZip(scratch, ImportBudget(freeSpace = { scratch.apply { mkdirs() }.usableSpace }))

    /**
     * Writes what [prep] read: the merge planned again against the archive now, written in batches of [BATCH] (each its own
     * transaction), adds before drops, so an import stopped halfway leaves at worst an Account play beside its Extended twin,
     * which the next merge removes, and never loses one.
     */
    suspend fun commit(prep: Prepared, checkpoint: () -> Unit = {}, progress: (ImportProgress) -> Unit = {}): CommitResult {
        val plays = prep.bundle.plays
        val plan = PlanMerge.plan(archive, plays)
        val total = plan.add.size
        progress(ImportProgress.Write(0, total))
        var done = 0
        var listens = 0
        val batch = ArrayList<ArchivePlay>(minOf(BATCH, total))
        for (i in plan.add) {
            val row = plays.toArchive(i, plan.textKeys[plays.songOf(i)])
            if (row.ms >= LISTEN_MS) listens++
            batch += row
            if (batch.size == BATCH) {
                checkpoint()
                archive.apply(batch.toList(), emptyList())
                done += batch.size
                batch.clear()
                progress(ImportProgress.Write(done, total))
            }
        }
        if (batch.isNotEmpty()) {
            // The last batch too (an import under 5,000 plays is only this one): a cancel before it writes nothing
            checkpoint()
            archive.apply(batch.toList(), emptyList())
            done += batch.size
            progress(ImportProgress.Write(done, total))
        }
        for (part in plan.drop.chunked(BATCH)) {
            checkpoint()
            archive.apply(emptyList(), part)
        }
        return CommitResult(listens = listens, written = total, dropped = plan.drop.size)
    }

    companion object {
        /** Plays per write (archive.ts `BATCH`). */
        const val BATCH = 5_000

        /** A play shorter than this is a skip, not a listen (plays.ts `LISTEN_MS`). */
        const val LISTEN_MS = 30_000L

        /**
         * importer.ts `openFiles`: each picked file as the export's files, zips opened (nested ones too; one that can't be opened
         * is kept as broken, so it counts as unreadable), loose `.json` files as they are; anything else isn't looked at.
         */
        internal fun openFiles(files: List<PickedFile>, zip: ExportZip): List<ExportEntry> {
            val out = ArrayList<ExportEntry>()
            for (f in files) {
                when {
                    ExportText.isZipName(f.name) -> try {
                        out += zip.entries(f.file, "${f.name}!/")
                    } catch (e: ZipError) {
                        out += ExportEntry(f.name, f.file.length(), broken = true) { f.file.inputStream() }
                    }
                    ExportText.isJsonName(f.name) -> out += ExportEntry(f.name, f.file.length()) { f.file.inputStream() }
                }
            }
            return out
        }

        /**
         * archive.ts's import record over the archive as it is now (importer.ts `storeRecord`), [added] imports on top of [prev]'s
         * count, and the [playlists] and [likes] this import brought into the library on top of its. The first and last listen are
         * the archive's and this phone's own plays' together ([ownFirst], [ownLast]): taste counts both (an own play is always a
         * listen there).
         */
        fun record(
            prev: ImportRecord?,
            listens: ArchiveListens,
            ownFirst: Long?,
            ownLast: Long?,
            now: Long,
            added: Int,
            playlists: Int = 0,
            likes: Int = 0,
        ): ImportRecord {
            val firsts = listOfNotNull(listens.first, ownFirst)
            val lasts = listOfNotNull(listens.last, ownLast)
            return ImportRecord(
                firstAt = prev?.firstAt ?: now,
                lastAt = now,
                imports = (prev?.imports ?: 0) + added,
                plays = listens.total,
                bySource = ImportRecord.BySource(listens.spotify, listens.lastfm, listens.stash),
                first = firsts.minOrNull() ?: 0,
                last = lasts.maxOrNull() ?: 0,
                playlists = (prev?.playlists ?: 0) + playlists,
                likes = (prev?.likes ?: 0) + likes,
            )
        }
    }
}
