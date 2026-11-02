package com.stash.core.media.streaming

/**
 * Whether [this] and [other] point at the SAME audio file — the question every
 * re-open of a playing item has to answer before it reads a single byte.
 *
 * Media3 builds one DataSource per media period and re-opens it at byte offset N
 * for every seek and reconnect, but it chooses the extractor once, from the first
 * bytes it read. A re-open that lands on a different file (another source, another
 * tier, another YouTube itag) is spliced in at offset N: FLAC frames that disagree
 * with the STREAMINFO already parsed, AAC/MP4 bytes fed into a WebM extractor. The
 * result is a stall or a decoder error, and under the stream cache it is written
 * to disk under the original key. Re-resolving the same URL lifetime later is
 * fine; re-resolving to a different file is only fine from byte 0 in a NEW period.
 *
 * Compared: the serving source, the codec and format the source reported, and the
 * file-identifying URL params googlevideo carries (see [sameUrlFileParams]). A
 * false "different" costs a clean re-prepare from the start; a false "same" costs a
 * corrupted stream, so every field the sources report has to agree.
 */
internal fun StreamUrl.isSameFileAs(other: StreamUrl): Boolean =
    origin == other.origin &&
        codec == other.codec &&
        bitsPerSample == other.bitsPerSample &&
        sampleRateHz == other.sampleRateHz &&
        bitrateKbps == other.bitrateKbps &&
        sameUrlFileParams(url, other.url)

/**
 * Whether two stream URLs agree on every file-identifying query param they BOTH
 * carry. googlevideo URLs name the encoding (`itag`: 251 Opus vs 140 AAC, or 249/250
 * under Data saver) and the exact byte length (`clen`), so two URLs that agree on both
 * serve the same bytes even though every signature param differs. A param missing
 * from either URL is not compared: the other sources' URLs carry neither, and their
 * identity is decided by the [StreamUrl] fields in [isSameFileAs].
 */
internal fun sameUrlFileParams(a: String, b: String): Boolean {
    val pa = fileParams(a)
    val pb = fileParams(b)
    return FILE_IDENTITY_PARAMS.all { key ->
        val x = pa[key]
        val y = pb[key]
        x == null || y == null || x == y
    }
}

private val FILE_IDENTITY_PARAMS = setOf("itag", "clen")

private fun fileParams(url: String): Map<String, String> =
    url.substringBefore('#').substringAfter('?', "")
        .split('&')
        .mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
        }
        .filter { it.first in FILE_IDENTITY_PARAMS }
        .toMap()
