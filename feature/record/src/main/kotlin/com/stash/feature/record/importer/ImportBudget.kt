package com.stash.feature.record.importer

import java.io.IOException

/** The files hold far more than any Spotify export could: the import is refused whole, nothing written. */
class ImportTooBig(what: String) : IOException("more $what than an import takes")

/** There isn't room on the phone for the import's files (its copies, or a zip inside a zip inflated to disk). */
class StorageFull : IOException("no room for the import's files")

/**
 * One import's ceilings, counted across every file and every zip inside a zip however deep, so a hostile file (a zip bomb, a zip
 * whose directory records share their data, a million tiny entries, a JSON file of digits) is refused early rather than filling
 * the memory or the disk. Each ceiling is several times what the largest real export needs: a heavy listener's Extended history
 * is about half a million plays in 300-400 MB of JSON, split into a few dozen files, compressed into zips of tens of MB.
 *
 * - [maxEntries]: directory records in all the zips together (one zip alone may hold at most 20,000: zip.ts's own cap).
 * - [maxInflated]: bytes read out of the export's JSON files once inflated.
 * - [maxRows]: rows read (plays, playlist items, library songs).
 * - [maxScratch]: bytes of zips inside zips inflated to disk, and never so many that less than [SCRATCH_HEADROOM] stays free.
 * - [maxZips]: zips opened (the files brought, and zips inside them inflated to disk): each holds a file open.
 *
 * One per import, used on the job's own thread.
 */
class ImportBudget(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxInflated: Long = MAX_INFLATED,
    private val maxRows: Long = MAX_ROWS,
    private val maxScratch: Long = MAX_SCRATCH,
    private val maxZips: Int = MAX_ZIPS,
    /** Free space where the scratch files go (asked as they grow). */
    private val freeSpace: () -> Long = { Long.MAX_VALUE },
) {
    private var entries = 0L
    private var inflated = 0L
    private var rows = 0L
    private var scratch = 0L
    private var zips = 0

    fun entries(n: Int) {
        entries += n
        if (entries > maxEntries) throw ImportTooBig("zip entries")
    }

    fun inflated(n: Long) {
        inflated += n
        if (inflated > maxInflated) throw ImportTooBig("data")
    }

    fun row() {
        if (++rows > maxRows) throw ImportTooBig("rows")
    }

    fun zip() {
        if (++zips > maxZips) throw ImportTooBig("zips")
    }

    fun scratch(n: Long) {
        scratch += n
        if (scratch > maxScratch) throw ImportTooBig("nested zip data")
        if (freeSpace() < SCRATCH_HEADROOM) throw StorageFull()
    }

    companion object {
        /** Directory records in all of an import's zips (a real export: a few dozen). */
        const val MAX_ENTRIES = 50_000

        /** 2 GiB of JSON inflated (a heavy export: 300-400 MB). */
        const val MAX_INFLATED = 2L shl 30

        /** Rows (a heavy export: half a million plays). */
        const val MAX_ROWS = 2_000_000L

        /** 1 GiB of zips inside zips on disk (Spotify's inner zip is the compressed export: tens of MB). */
        const val MAX_SCRATCH = 1L shl 30

        /** Zips opened in one import (a real export: two, and the zip inside the Extended one). */
        const val MAX_ZIPS = 256

        /** Free space a scratch file never eats into. */
        const val SCRATCH_HEADROOM = 64L shl 20
    }
}
