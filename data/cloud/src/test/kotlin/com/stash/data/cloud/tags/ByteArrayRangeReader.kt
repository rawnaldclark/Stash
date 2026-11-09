package com.stash.data.cloud.tags

/** A [RangeReader] over bytes already in memory (tests). */
class ByteArrayRangeReader(private val bytes: ByteArray) : RangeReader {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}
