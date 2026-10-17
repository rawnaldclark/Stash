package com.stash.core.model

import java.net.URLDecoder

/**
 * Ids at or below this are All Songs scans. Radio/search hash ids are Int-ranged, so they never reach it.
 *
 * A scanned song is a file on this phone that Stash only plays. It has no `tracks` row, so it must never
 * be persisted, liked, mirrored, downloaded, blocked, shared, or swapped for an online match. Everything
 * that would do one of those checks [isScannedSong] / [isScannedTrackId] first.
 */
const val SCANNED_ID_BASE = -4294967296L

/** The player id of the `scanned_songs` row [rowId] (always <= [SCANNED_ID_BASE] - 1). */
fun scannedTrackId(rowId: Long): Long = SCANNED_ID_BASE - rowId

/** True for an id made by [scannedTrackId]: a file from All Songs, not a library song. */
fun isScannedTrackId(id: Long): Boolean = id <= SCANNED_ID_BASE

val Track.isScannedSong: Boolean get() = isScannedTrackId(id)

/** The artist the scanner writes for a file with no artist tag. Not a real artist: nothing looks it up. */
const val SCANNED_UNKNOWN_ARTIST = "Unknown Artist"

/**
 * The lowercase file extension of a scanned song's SAF document URI, or null when the URI doesn't
 * carry one (some providers use opaque document ids such as `msf:123`).
 *
 * SAF encodes the document id into the last path segment (`primary%3AMusic%2Fa.flac`), so this
 * decodes it and takes the part after the last `/` and `.`.
 */
fun scannedFileExtension(uri: String): String? {
    val lastSegment = uri.substringBefore('?').substringAfterLast('/')
    val decoded = try {
        URLDecoder.decode(lastSegment.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        lastSegment
    }
    val name = decoded.substringAfterLast('/').substringAfterLast(':')
    if ('.' !in name) return null
    val ext = name.substringAfterLast('.').lowercase()
    return ext.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
}
