package com.stash.feature.record.importer

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** It isn't a zip this reader can open (zip.ts `ZipError`): counted as unreadable, never a crash. */
class ZipError(message: String) : IOException(message)

/**
 * One file of an export: its path (a zip's entries are prefixed with the zip's own name and `!/`, as the web names them), its
 * size as the zip says, and how to read it. [broken] is a zip inside that couldn't be opened: counted as unreadable.
 */
internal class ExportEntry(
    val name: String,
    val size: Long,
    val broken: Boolean = false,
    private val opener: () -> InputStream,
) {
    /** The file's bytes, streamed. Throws [IOException] (a [ZipError] among them) when it can't be read. */
    fun open(): InputStream = opener()
}

/**
 * The web's zip reader (stash-player `src/lib/spotify/zip.ts`), on the phone: the central directory, then each entry read where
 * it lies, stored (0) or deflated (8), Zip64 sizes and offsets, zips inside zips (up to [MAX_DEPTH] deep). Entries are listed
 * in the central directory's order, as the web lists them, so plays come in the web's order too. Nothing is read whole: an
 * entry is a stream, a zip stored inside a zip is read in place, and one deflated inside is inflated to a file in [scratch]
 * (deleted by [close]), never into memory. Encrypted entries and other methods are refused; CRCs aren't checked (a damaged
 * deflate stream fails by itself, and the JSON reader after it refuses garbage).
 *
 * Against hostile files, beyond the web's own checks: a central directory larger than [MAX_DIRECTORY] is refused, so are
 * directory records whose data overlap (a zip bomb's way of making one compressed block count many times), and every directory
 * record (once its directory is read and checked) and every byte inflated to [scratch] counts against the import's [budget], and
 * so does every zip opened (each holds a file open).
 *
 * One instance per import: [sniff] (the intake's look inside) and [entries] (the read) share what they opened, so a zip inside
 * a zip is inflated once.
 */
internal class ExportZip(
    private val scratch: File,
    val budget: ImportBudget = ImportBudget(freeSpace = { scratch.usableSpace }),
) : Closeable {
    private val open = ArrayList<RandomAccessFile>()
    private val temps = ArrayList<File>()

    /** Each picked file's source, by its path. */
    private val tops = HashMap<String, Source>()

    /** Each source's directory, read once (and counted against the budget once); one that couldn't be read, its error. */
    private val listed = HashMap<Source, List<Raw>>()
    private val unlisted = HashMap<Source, ZipError>()

    /** Each zip inside a zip, opened once; [failed] holds the ones that couldn't be. */
    private val inner = HashMap<Raw, Source>()
    private val failed = HashSet<Raw>()

    /** Every file in [file] (nested zips opened), contents unread. Throws [ZipError] when it isn't a readable zip. */
    fun entries(file: File, prefix: String): List<ExportEntry> = expand(top(file), prefix, 0)

    /**
     * Whether [file] holds a file of an export, by the names in it: its own directory first, and a zip inside it only when its own
     * names hold none (Spotify's Extended zip is a zip inside a zip), each under the import's budget. False for a zip that can't be
     * opened. What it opens stays open for [entries].
     */
    fun sniff(file: File, prefix: String): Boolean = try {
        sniffIn(top(file), prefix, 0)
    } catch (e: ZipError) {
        false
    }

    override fun close() {
        open.forEach { runCatching { it.close() } }
        open.clear()
        temps.forEach { it.delete() }
        temps.clear()
        tops.clear()
        listed.clear()
        unlisted.clear()
        inner.clear()
        failed.clear()
    }

    /** Bytes to read from: a file, or part of one (a zip stored inside a zip). Compared by identity. */
    private class Source(val raf: RandomAccessFile, val start: Long, val size: Long) {
        fun bytes(from: Long, to: Long): ByteArray {
            val end = minOf(to, size)
            if (from < 0 || from > end) throw ZipError("broken zip")
            val n = end - from
            if (n > Int.MAX_VALUE) throw ZipError("zip too large")
            val out = ByteArray(n.toInt())
            synchronized(raf) {
                raf.seek(start + from)
                raf.readFully(out)
            }
            return out
        }

        fun within(from: Long, n: Long): Source = Source(raf, start + from, minOf(n, maxOf(0, size - from)))

        /** [n] bytes from [from], as a stream (each read seeks: an entry's stream and the next one's can interleave). */
        fun stream(from: Long, n: Long): InputStream = object : InputStream() {
            private var at = from
            private val end = minOf(from + n, size)

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (at >= end) return -1
                val take = minOf(len.toLong(), end - at).toInt()
                val got = synchronized(raf) {
                    raf.seek(start + at)
                    raf.read(b, off, take)
                }
                if (got < 0) return -1
                at += got
                return got
            }
        }
    }

    /** One directory record: where its data is and how to read it. Compared by identity. */
    private inner class Raw(
        val src: Source,
        val name: String,
        val flags: Int,
        val method: Int,
        val csize: Long,
        val usize: Long,
        val loc: Long,
    ) {
        /** Where the data starts: after the local header's own name and extra (their lengths can differ from the central copy's). */
        fun dataStart(): Long {
            if (flags and 1 != 0) throw ZipError("encrypted zip")
            val lh = src.bytes(loc, loc + 30)
            if (lh.size < 30 || u32(lh, 0) != LOC) throw ZipError("broken zip")
            return loc + 30 + u16(lh, 26) + u16(lh, 28)
        }

        fun read(): InputStream {
            if (usize > MAX_ENTRY) throw ZipError("entry too large")
            val start = dataStart()
            return when (method) {
                0 -> src.stream(start, csize)
                8 -> inflating(src.stream(start, csize), usize)
                else -> throw ZipError("unsupported zip method")
            }
        }
    }

    private fun top(file: File): Source = tops.getOrPut(file.absolutePath) {
        budget.zip()
        val raf = RandomAccessFile(file, "r")
        open += raf
        Source(raf, 0, raf.length())
    }

    private fun sniffIn(src: Source, prefix: String, depth: Int): Boolean {
        val raws = list(src, prefix)
        if (raws.any { ExportText.kindOf(it.name) != ExportKind.IGNORE }) return true
        if (depth >= MAX_DEPTH) return false
        for (r in raws) {
            if (!ExportText.isZipName(r.name)) continue
            val s = innerOf(r) ?: continue
            val found = try {
                sniffIn(s, "${r.name}!/", depth + 1)
            } catch (e: ZipError) {
                false
            }
            if (found) return true
        }
        return false
    }

    private fun expand(src: Source, prefix: String, depth: Int): List<ExportEntry> {
        val out = ArrayList<ExportEntry>()
        for (r in list(src, prefix)) {
            if (depth < MAX_DEPTH && ExportText.isZipName(r.name)) {
                // A zip inside: its own entries instead of it (Spotify's export zip, or a folder of them zipped again)
                val nested = innerOf(r)?.let { s ->
                    try {
                        expand(s, "${r.name}!/", depth + 1)
                    } catch (e: ZipError) {
                        null
                    }
                }
                out += nested ?: listOf(ExportEntry(r.name, r.usize, broken = true, opener = r::read))
                continue
            }
            out += ExportEntry(r.name, r.usize, opener = r::read)
        }
        return out
    }

    /** A zip inside a zip, opened once: read in place when stored, inflated to a scratch file when deflated; null when it can't be. */
    private fun innerOf(r: Raw): Source? {
        inner[r]?.let { return it }
        if (r in failed) return null
        return try {
            (if (r.method == 0) r.src.within(r.dataStart(), r.csize) else inflateToFile(r.read())).also { inner[r] = it }
        } catch (e: ImportTooBig) {
            throw e
        } catch (e: StorageFull) {
            throw e
        } catch (e: IOException) {
            failed += r
            null
        }
    }

    private fun list(src: Source, prefix: String): List<Raw> {
        listed[src]?.let { return it }
        unlisted[src]?.let { throw it }
        return try {
            readDirectory(src, prefix).also { listed[src] = it }
        } catch (e: ZipError) {
            unlisted[src] = e
            throw e
        }
    }

    private fun readDirectory(src: Source, prefix: String): List<Raw> {
        if (src.size < 22) throw ZipError("not a zip")
        // The end record is in the last 22 + 65,535 bytes (its comment is at most 64 KiB)
        val tailStart = maxOf(0L, src.size - (22 + 0xffff))
        val tail = src.bytes(tailStart, src.size)
        var e = -1
        for (i in tail.size - 22 downTo 0) if (u32(tail, i) == EOCD) { e = i; break }
        if (e < 0) throw ZipError("not a zip")
        var count = u16(tail, e + 10).toLong()
        var cdSize = u32(tail, e + 12)
        var cdOff = u32(tail, e + 16)
        if (count == 0xffffL || cdSize == 0xffffffffL || cdOff == 0xffffffffL) {
            val l = e - 20
            if (l < 0 || u32(tail, l) != EOCD64_LOCATOR) throw ZipError("broken zip64")
            val recOff = u64(tail, l + 8)
            val rec = src.bytes(recOff, recOff + 56)
            if (rec.size < 56 || u32(rec, 0) != EOCD64) throw ZipError("broken zip64")
            count = u64(rec, 32)
            cdSize = u64(rec, 40)
            cdOff = u64(rec, 48)
        }
        if (count > MAX_ENTRIES || cdOff + cdSize > src.size) throw ZipError("broken zip")
        if (cdSize > MAX_DIRECTORY) throw ZipError("central directory too large")
        val cd = src.bytes(cdOff, cdOff + cdSize)

        val all = ArrayList<Raw>(count.toInt())
        var p = 0
        for (i in 0 until count) {
            if (p + 46 > cd.size || u32(cd, p) != CEN) throw ZipError("broken zip")
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            var csize = u32(cd, p + 20)
            var usize = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            var loc = u32(cd, p + 42)
            if (p + 46 + nameLen > cd.size) throw ZipError("broken zip")
            // TextDecoder's UTF-8: a bad byte is U+FFFD, never an error
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8).replace('\\', '/')
            // Zip64 extra field: the 0xffffffff values, in this order
            var x = p + 46 + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd && x + 4 <= cd.size) {
                val id = u16(cd, x)
                val len = u16(cd, x + 2)
                if (id == 0x0001) {
                    var q = x + 4
                    if (usize == 0xffffffffL) { usize = u64(cd, q); q += 8 }
                    if (csize == 0xffffffffL) { csize = u64(cd, q); q += 8 }
                    if (loc == 0xffffffffL) loc = u64(cd, q)
                }
                x += 4 + len
            }
            p = xEnd + commentLen
            all += Raw(src, prefix + name, flags, method, csize, usize, loc)
        }
        refuseOverlaps(all)
        // Counted once the directory has been read and checked: a broken one (a 22-byte file claiming 20,000 records) is
        // unreadable, never charged to the import
        budget.entries(all.size)
        // Folders have no data to read
        return all.filter { !it.name.endsWith('/') }
    }

    /**
     * Directory records whose data overlap are refused: each record's local header and data (at least 30 bytes, then the
     * compressed bytes) must end before the next record's local header starts. A real zip's entries follow one another; a zip
     * bomb points many records at one compressed block so it inflates many times over.
     */
    private fun refuseOverlaps(records: List<Raw>) {
        val byStart = records.sortedBy { it.loc }
        for (i in 1 until byStart.size) {
            val prev = byStart[i - 1]
            if (byStart[i].loc < prev.loc + 30 + prev.csize) throw ZipError("overlapping entries")
        }
    }

    /** A deflated zip inside a zip, inflated to a scratch file (the web inflates it into memory; a phone shouldn't), counted. */
    private fun inflateToFile(input: InputStream): Source {
        budget.zip()
        scratch.mkdirs()
        val f = File.createTempFile("nested", ".zip", scratch)
        temps += f
        input.use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    budget.scratch(n.toLong())
                    o.write(buf, 0, n)
                }
            }
        }
        val raf = RandomAccessFile(f, "r")
        open += raf
        return Source(raf, 0, raf.length())
    }

    /**
     * Raw deflate, as the web's `DecompressionStream('deflate-raw')`, refusing more than the entry says it holds (when it says)
     * or [MAX_ENTRY]. A zero byte is fed after the data, as java.util.zip's own ZipFile does: the inflater may want one past the
     * end of a raw stream.
     */
    private fun inflating(compressed: InputStream, expected: Long): InputStream {
        val fed = SequenceInputStream(compressed, ByteArrayInputStream(ByteArray(1)))
        val inflater = Inflater(true)
        val inflated = object : InflaterInputStream(fed, inflater, 64 * 1024) {
            override fun close() {
                super.close()
                inflater.end()
            }
        }
        return Capped(inflated, if (expected > 0) minOf(expected, MAX_ENTRY) else MAX_ENTRY)
    }

    /** Fails once more than [max] bytes come out (zip.ts: "entry larger than it says"). */
    private class Capped(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var n = 0L

        override fun read(): Int {
            val c = super.read()
            if (c >= 0 && ++n > max) throw ZipError("entry larger than it says")
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) {
                n += r
                if (n > max) throw ZipError("entry larger than it says")
            }
            return r
        }
    }

    companion object {
        /** One entry inflated is at most this (zip.ts `MAX_ENTRY`; Spotify splits history into files of about 12 MB). */
        const val MAX_ENTRY = 512L * 1024 * 1024

        /** Zips inside zips, at most this deep (zip.ts `MAX_DEPTH`). */
        const val MAX_DEPTH = 3

        /** Records in one zip's directory (zip.ts `MAX_ENTRIES`). */
        const val MAX_ENTRIES = 20_000

        /**
         * The largest central directory read: 32 MiB, room for 20,000 records with names of over a kilobyte each (a real export's
         * directory is a few KB).
         */
        const val MAX_DIRECTORY = 32L shl 20

        private const val EOCD = 0x06054b50L
        private const val EOCD64_LOCATOR = 0x07064b50L
        private const val EOCD64 = 0x06064b50L
        private const val CEN = 0x02014b50L
        private const val LOC = 0x04034b50L

        private fun u16(b: ByteArray, o: Int): Int {
            if (o + 2 > b.size) throw ZipError("broken zip")
            return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(b: ByteArray, o: Int): Long {
            if (o + 4 > b.size) throw ZipError("broken zip")
            return (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
        }

        private fun u64(b: ByteArray, o: Int): Long {
            val lo = u32(b, o)
            val hi = u32(b, o + 4)
            // zip.ts refuses what isn't a safe integer in JavaScript (2^53)
            if (hi >= (1L shl 21)) throw ZipError("zip too large")
            return lo + hi * (1L shl 32)
        }
    }
}
