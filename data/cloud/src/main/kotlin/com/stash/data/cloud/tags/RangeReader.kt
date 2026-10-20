package com.stash.data.cloud.tags

import java.io.IOException

/**
 * Random access to a remote file's bytes, fetched in ranges.
 *
 * The tag parsers read through this instead of a stream so they can jump: an MP4's `moov`
 * atom may sit after megabytes of audio, and a FLAC PICTURE block or an ID3v2 tag may be larger
 * than the first read. Implementations decide how bytes arrive (HTTP Range for a cloud file,
 * a byte array in tests).
 */
interface RangeReader {
    /** The file's total length in bytes, from the provider's metadata (never guessed). */
    val size: Long

    /**
     * Up to [length] bytes starting at [offset]. Fewer only at the end of the file; an empty
     * array when [offset] is at or past [size].
     */
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** Thrown when reading a file's tags would fetch more bytes than [CachingRangeReader.maxTotalBytes]. */
class TagReadBudgetExceeded(message: String) : IOException(message)

/**
 * A [RangeReader] that fetches ranges through [fetch] and keeps every fetched window, so the
 * parsers' many small header reads cost one request per window instead of one each.
 *
 * Every miss fetches at least [minChunk] bytes, which is what makes walking a run of small
 * boxes/blocks cheap. [maxTotalBytes] bounds the whole read: a scan must never download a
 * library's worth of audio just to find tags, so a pathological file (a 40 MB "cover", a tag
 * that claims to be huge) fails with [TagReadBudgetExceeded] and the scanner falls back to
 * folder/filename metadata.
 *
 * Not thread-safe: one reader per file, used by one coroutine.
 *
 * @param fetch Fetches exactly `[offset, offset + length)` (clamped by the caller to the file).
 *   May return fewer bytes only at end of file.
 */
class CachingRangeReader(
    override val size: Long,
    private val minChunk: Int = DEFAULT_MIN_CHUNK,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val fetch: suspend (offset: Long, length: Int) -> ByteArray,
) : RangeReader {

    private class Window(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    private val windows = mutableListOf<Window>()

    /** Total bytes fetched so far, for budgeting and diagnostics. */
    var fetchedBytes: Long = 0
        private set

    /** Number of fetches made so far (each one is a network request for a cloud file). */
    var fetchCount: Int = 0
        private set

    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "bad range $offset+$length" }
        if (offset >= size || length == 0) return ByteArray(0)
        val wanted = minOf(length.toLong(), size - offset).toInt()
        windows.firstOrNull { offset >= it.start && offset + wanted <= it.end }?.let { w ->
            val from = (offset - w.start).toInt()
            return w.bytes.copyOfRange(from, from + wanted)
        }
        // A range that starts inside a window we hold (the rest of an ID3 tag that outgrew the
        // head, say) only fetches the part after that window: the "second exact range".
        val overlap = windows.filter { offset >= it.start && offset < it.end }.maxByOrNull { it.end }
        val fetchFrom = overlap?.end ?: offset
        val needed = offset + wanted - fetchFrom
        val fetchLength = minOf(maxOf(needed, minChunk.toLong()), size - fetchFrom).toInt()
        if (fetchedBytes + fetchLength > maxTotalBytes) {
            throw TagReadBudgetExceeded("tag read would exceed $maxTotalBytes bytes")
        }
        val bytes = fetch(fetchFrom, fetchLength)
        fetchCount++
        fetchedBytes += bytes.size
        windows += Window(fetchFrom, bytes)
        if (overlap == null) return if (bytes.size <= wanted) bytes else bytes.copyOfRange(0, wanted)
        val out = java.io.ByteArrayOutputStream(wanted)
        val head = (offset - overlap.start).toInt()
        out.write(overlap.bytes, head, overlap.bytes.size - head)
        out.write(bytes, 0, minOf(bytes.size, wanted - (overlap.bytes.size - head)))
        return out.toByteArray()
    }

    companion object {
        /** Smallest fetch on a miss: one request covers a run of small headers. */
        const val DEFAULT_MIN_CHUNK = 64 * 1024

        /** The first read of every file (Siku: 256 KB holds the tags of 93% of a FLAC library). */
        const val HEAD_BYTES = 256 * 1024

        /** Upper bound for one file's tag read, cover art included. */
        const val DEFAULT_MAX_TOTAL_BYTES = 12L * 1024 * 1024
    }
}

/** A [RangeReader] over bytes already in memory (temp files, tests). */
class ByteArrayRangeReader(private val bytes: ByteArray) : RangeReader {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}
