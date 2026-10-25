package com.stash.core.data.backcover

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/*
 * Picking an album's back and inside from a Discogs release.
 *
 * Discogs doesn't say which image is the back: every image but one is "secondary". Its guidelines
 * (Database Guidelines 13.8.3) suggest an order (front, back, the labels, then the booklet; for a
 * gatefold: front, the inside (one spread or its two halves), then the back) and editors mostly
 * follow it. Measured 2026-10-24 by eye on 44 physical releases of 30 albums (the main release of
 * each Discogs master and its most-collected vinyl), 41 of them with a back:
 * - the back was image 1 in 19;
 * - image 1 was the gatefold spread as one wide picture and the back came next in 5;
 * - images 1 and 2 were the gatefold's two halves and the back was image 3 in 15 (DSOTM, 1989,
 *   SOUR, Short n' Sweet...: modern vinyl is mostly gatefold, and editors rarely say so: the
 *   format text named "Gatefold" for 4 of the 15);
 * - 2 broke the order (a second front first; a Led Zeppelin IV with the back last).
 * So "the first secondary" alone is right for under half. [discogsBackIndex] reads the spread from
 * the image's shape, and the two halves from the format text or from their edges meeting
 * ([halvesJoin]): 34 of 41 right (83%), 7 wrong, all of them a gatefold's inside shown as the back
 * (album art from the same sleeve, at least). Digital releases ("File") never get a back: theirs
 * is an alternate cover or a booklet page.
 */

/** The Discogs master a MusicBrainz release group links to (`inc=url-rels`), or null. */
internal fun discogsMasterId(rels: JsonObject): Long? =
    (rels["relations"] as? JsonArray).orEmpty().firstNotNullOfOrNull { el ->
        val rel = el as? JsonObject ?: return@firstNotNullOfOrNull null
        val url = ((rel["url"] as? JsonObject)?.get("resource") as? JsonPrimitive)?.content
            ?: return@firstNotNullOfOrNull null
        DISCOGS_MASTER.find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

private val DISCOGS_MASTER = Regex("""^https?://(?:www\.)?discogs\.com/master/(\d+)""")

/**
 * The Discogs releases a release group's releases link to, best for a back first (vinyl, then CD:
 * [rankReleasesForBack]), from a browse with `inc=url-rels`. Many older albums link Discogs only
 * from their releases, not from the group (On Top, Four Tops, 2026-10-25).
 */
internal fun discogsReleaseIds(releases: JsonObject): List<Long> {
    val linked = (releases["releases"] as? JsonArray).orEmpty().mapNotNull { el ->
        val release = el as? JsonObject ?: return@mapNotNull null
        val mbid = (release["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val discogs = (release["relations"] as? JsonArray).orEmpty().firstNotNullOfOrNull { rel ->
            val url = (((rel as? JsonObject)?.get("url") as? JsonObject)?.get("resource") as? JsonPrimitive)?.content
            url?.let { DISCOGS_RELEASE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        } ?: return@mapNotNull null
        mbid to discogs
    }.toMap()
    return rankReleasesForBack(releases).mapNotNull { linked[it] }.distinct()
}

private val DISCOGS_RELEASE = Regex("""^https?://(?:www\.)?discogs\.com/release/(\d+)""")

/**
 * The version of an album whose images are read: the Accepted one the most Discogs users own (the
 * best-photographed, and the pressing most people have), among [versions]. Null when none.
 */
internal fun mostCollected(versions: List<DiscogsVersion>): Long? =
    versions.filter { it.accepted }.maxByOrNull { it.inCollection }?.id

/** A gatefold opened flat, photographed as one picture: about 2:1 (measured 2.00-2.06). */
private fun DiscogsImage.isSpread() = width > 1.5 * height

/** A spine, a hype sticker or a barcode strip (600x24, 600x223): no page of the album. */
private fun DiscogsImage.isSliver() = width > 2.5 * height || height > 2.5 * width

private fun DiscogsRelease.isDigital() = formats.isNotEmpty() && formats.all { it == "File" }

private fun DiscogsRelease.saysGatefold() = "gatefold" in formatText.lowercase()

/**
 * True when telling the back from a gatefold's inside needs [halvesJoin]: a physical release
 * whose image 1 isn't a spread and whose format text doesn't say gatefold, with an image 3 to fall
 * back on. Saves the two thumbnail downloads otherwise.
 */
internal fun needsSeamCheck(release: DiscogsRelease): Boolean {
    val images = release.images
    return !release.isDigital() && images.size > 3 && !images[1].isSpread() && !release.saysGatefold() &&
        images[1].thumb != null && images[2].thumb != null
}

/**
 * The index of the back in [release]'s images, or null when there's no likely one (see the measure
 * above this file's functions):
 * - a digital release, or one with only its front: none;
 * - image 1 a spread: the first image after it that isn't one;
 * - images 1 and 2 a gatefold's halves (the format text says gatefold, or [halvesJoin], the
 *   caller's [seamGap] check, says their edges meet): image 3;
 * - else image 1.
 */
internal fun discogsBackIndex(release: DiscogsRelease, halvesJoin: Boolean): Int? {
    val images = release.images
    if (release.isDigital() || images.size < 2) return null
    if (images[1].isSpread()) {
        return (2 until images.size).firstOrNull { !images[it].isSpread() && !images[it].isSliver() }
    }
    if (release.saysGatefold() || halvesJoin) {
        return 3.takeIf { images.size > 3 && !images[3].isSpread() && !images[3].isSliver() }
    }
    return 1.takeIf { !images[1].isSliver() }
}

/**
 * The inside of the album from [release]: every image but the front (image 0), the back, and
 * slivers. LOW CONFIDENCE as "inside": Discogs' images aren't typed, so beside the gatefold, inner
 * sleeves and booklet pages this keeps the photos of the labels and discs (most releases have
 * them, right after the back), and now and then a second front or a hype sticker. Better than
 * nothing for an album the Cover Art Archive has nothing inside for.
 */
internal fun discogsInside(release: DiscogsRelease, backIndex: Int?): List<DiscogsImage> =
    release.images.filterIndexed { i, img -> i != 0 && i != backIndex && !img.isSliver() }

/**
 * How far apart the right edge of [left] and the left edge of [right] are, 0-255: the mean RGB
 * distance between their edge columns (each 1-3 pixels in from the edge, past a scan's border),
 * each column averaged into [SEAM_BANDS] bands so the two images needn't be the same height. Two
 * halves of one picture meet at a low gap.
 *
 * Measured 2026-10-24 on the 150px thumbnails of images 1 and 2 of 41 releases: the two halves of a
 * gatefold 4.7-22.8 for 8 of 15 (the other 7, 40-103, are halves split by a fold or a margin), a
 * back beside a label 34.5 and up. [HALVES_JOIN_BELOW] sits between.
 */
internal fun seamGap(left: Pixels, right: Pixels): Double {
    var total = 0.0
    for (inset in 1..3) {
        val a = bandedColumn(left, (left.width - 1 - inset).coerceAtLeast(0))
        val b = bandedColumn(right, inset.coerceAtMost(right.width - 1))
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i] - b[i])
        total += sum / a.size
    }
    return total / 3
}

/** A seam gap below this is two halves of one picture. */
internal const val HALVES_JOIN_BELOW = 28.0

private const val SEAM_BANDS = 32

/** Column [x] of [img] as [SEAM_BANDS] row bands, each its mean R, G and B: 3 * bands values. */
private fun bandedColumn(img: Pixels, x: Int): DoubleArray {
    val out = DoubleArray(SEAM_BANDS * 3)
    for (band in 0 until SEAM_BANDS) {
        val y0 = band * img.height / SEAM_BANDS
        val y1 = maxOf(y0 + 1, (band + 1) * img.height / SEAM_BANDS)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (y in y0 until y1) {
            val p = img.argb[y * img.width + x]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = (y1 - y0).toDouble()
        out[band * 3] = r / n
        out[band * 3 + 1] = g / n
        out[band * 3 + 2] = b / n
    }
    return out
}
