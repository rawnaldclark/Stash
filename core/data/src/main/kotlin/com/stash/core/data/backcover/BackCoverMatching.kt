package com.stash.core.data.backcover

import com.stash.core.common.VersionWords
import com.stash.core.common.primaryArtist
import com.stash.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * The album a song's back cover belongs to: its primary artist and title, plus the song's ISRC,
 * which finds the album on MusicBrainz more surely than a title search.
 */
data class BackCoverAlbum(val artist: String, val title: String, val isrc: String?) {
    /** Every song on the album shares this ([isrc] is the song's, so it stays out). */
    val key: String get() = backCoverAlbumKey(artist, title)

    companion object {
        /** The playing song's album, or null for a song without one (a single YouTube upload). */
        fun of(track: Track): BackCoverAlbum? {
            val title = track.album.trim().takeIf { it.isNotEmpty() } ?: return null
            val artist = track.albumArtist.ifBlank { track.artist }.primaryArtist().trim()
                .takeIf { it.isNotEmpty() } ?: return null
            return BackCoverAlbum(artist, title, track.isrc?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/** The `album_back_covers` row key for an album: artist and title, trimmed and lowercased. */
fun backCoverAlbumKey(artist: String, title: String): String =
    "${artist.trim().lowercase()}\u001F${title.trim().lowercase()}"

private val BRACKETS = Regex("""\([^)]*\)|\[[^]]*]""")
private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
private val MARKS = Regex("""\p{M}+""")

/**
 * An edition named at a title's end, after a colon or with nothing between: ": Deluxe Edition",
 * " 50th Anniversary Edition", " Remastered". Only edition words, at least one of them a sure one
 * ([EDITION_MARK]), so a subtitle ("OK Computer: OKNOTOK") or a title that merely ends in a number
 * keeps its words. Measured 2026-10-25: Spotify titles Sebadoh's reissue "Bakesale: Deluxe
 * Edition", MusicBrainz "Bakesale".
 */
private val EDITION_SUFFIX = Regex(
    """(?:\s*:\s*|\s+)(?:(?:deluxe|super|expanded|remaster(?:ed)?|anniversary|bonus|special|collector['’]?s|""" +
        """legacy|reissue|edition|tracks?|\d{1,4}(?:st|nd|rd|th)?|year|years|the)\b\s*)+$""",
    RegexOption.IGNORE_CASE,
)

private val EDITION_MARK = Regex("""\b(deluxe|expanded|remaster(ed)?|anniversary|edition|reissue)\b""", RegexOption.IGNORE_CASE)

/**
 * A span of years at a title's end, "1991–1992": MusicBrainz's "Songs to No One 1991–1992" is
 * Spotify's "Songs To No One" (2026-10-25). A single year is left alone ("Rumours 1977" is rare,
 * "1999" a title).
 */
private val YEAR_SPAN_SUFFIX = Regex("""\s+\d{4}\s*[-–—]\s*\d{2,4}$""")

/** [title] without an edition at its end ([EDITION_SUFFIX], [YEAR_SPAN_SUFFIX]); unchanged when nothing would be left. */
private fun withoutEditionSuffix(title: String): String {
    YEAR_SPAN_SUFFIX.find(title)?.let { span ->
        title.substring(0, span.range.first).takeIf { it.isNotBlank() }?.let { return withoutEditionSuffix(it) }
    }
    val match = EDITION_SUFFIX.find(title) ?: return title
    if (!EDITION_MARK.containsMatchIn(match.value)) return title
    return title.substring(0, match.range.first).takeIf { it.isNotBlank() } ?: title
}

/** [text] lowercased, accents and punctuation gone, "&" read as "and": "Short n' Sweet" -> "short n sweet". */
private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase().replace("&", " and "), Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace(NOT_WORD, " ")
        .trim()

/**
 * An album title as a back cover lookup compares it: the title without its edition
 * ("Rumours (Super Deluxe)", "Nevermind - Remastered", "Bakesale: Deluxe Edition" are "rumours",
 * "nevermind", "bakesale"), but with any
 * performance it names, so "1989 (Taylor's Version)" never takes the original's back.
 */
internal fun albumTitleKey(title: String): String {
    val base = fold(withoutEditionSuffix(title.replace(BRACKETS, " ").replace(VersionWords.DASH_SUFFIX, "").trim()))
        .ifEmpty { fold(title) } // a title that is all brackets: "[Untitled]"
    val versions = VersionWords.of(title).sorted()
    return (listOf(base) + versions).joinToString("|")
}

/**
 * [title] as a search asks for it: without brackets or a dash suffix, so a release group search
 * for "Rumours (Super Deluxe)" can find "Rumours" (a phrase search needs every word).
 */
internal fun plainAlbumTitle(title: String): String =
    withoutEditionSuffix(title.replace(BRACKETS, " ").replace(VersionWords.DASH_SUFFIX, "").trim())
        .trim().replace(Regex("\\s+"), " ")
        .ifEmpty { title.trim() }

/** True when MusicBrainz's [credit] names [artist]: the whole name, or it as one of the acts. */
internal fun artistMatches(artist: String, credit: String): Boolean {
    val ours = fold(artist)
    val theirs = fold(credit)
    if (ours.isEmpty() || theirs.isEmpty()) return false
    return theirs == ours || " $theirs ".contains(" $ours ") || " $ours ".contains(" $theirs ")
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content

private fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

/** An artist-credit array as one line: "Kendrick Lamar feat. SZA". */
private fun JsonObject.creditLine(): String =
    (this["artist-credit"] as? JsonArray).orEmpty().joinToString("") {
        val c = it.jsonObject
        (c.string("name") ?: "") + (c.string("joinphrase") ?: "")
    }

/**
 * Secondary types that make a release group another take of the album (a concert, demos, remixes)
 * or not an album at all: its back isn't the album's. Compilation and Soundtrack are not here: the
 * album played can be either (Magical Mystery Tour is a Soundtrack on MusicBrainz).
 */
private val OTHER_TAKES = setOf(
    "Live", "Demo", "Remix", "DJ-mix", "Mixtape/Street", "Interview", "Spokenword", "Audiobook",
    "Audio drama", "Field recording",
)

private fun JsonObject.isOtherTake(): Boolean = strings("secondary-types").any { it in OTHER_TAKES }

/**
 * The release group of [album] among the releases of an ISRC's recordings
 * ([com.stash.core.data.musicbrainz.MusicBrainzClient.searchRecordingsByIsrc]): one whose title, or
 * whose release's title, is the album's. A song is on many release groups (its album, singles,
 * compilations); one that isn't another take ([OTHER_TAKES]) wins, then the one the most releases
 * are on. Null when none is titled like the album.
 */
internal fun releaseGroupFromIsrcSearch(body: JsonObject, album: String): String? {
    val want = albumTitleKey(album)
    data class Seen(val id: String, val otherTake: Boolean, var releases: Int)
    val groups = LinkedHashMap<String, Seen>()
    for (rec in body["recordings"]?.jsonArray.orEmpty()) {
        for (rel in rec.jsonObject["releases"]?.jsonArray.orEmpty()) {
            val release = rel.jsonObject
            val group = release["release-group"] as? JsonObject ?: continue
            val id = group.string("id") ?: continue
            val titled = listOfNotNull(group.string("title"), release.string("title")).any { albumTitleKey(it) == want }
            if (!titled) continue
            groups.getOrPut(id) { Seen(id, group.isOtherTake(), 0) }.releases++
        }
    }
    return groups.values.sortedWith(compareBy<Seen> { it.otherTake }.thenByDescending { it.releases })
        .firstOrNull()?.id
}

/**
 * The release group of [album] by [artist] in a release group search
 * ([com.stash.core.data.musicbrainz.MusicBrainzClient.searchReleaseGroups]): titled like the
 * album, credited to the artist and scored at least [MIN_SEARCH_SCORE]. Of those, one that isn't
 * another take ([OTHER_TAKES]: "My Beautiful Dark Twisted Fantasy" also finds a Demo) wins, then
 * the one with the most releases, then the higher score. The album itself has by far the most
 * releases: "Help!" 95 beside a stray "Help! (Deluxe)" with 1, the White Album 58 beside a dozen
 * one-release compilations titled "The Beatles", all scored 100. Null when none fits.
 */
internal fun releaseGroupFromSearch(body: JsonObject, album: String, artist: String): String? {
    val want = albumTitleKey(album)
    data class Hit(val id: String, val otherTake: Boolean, val score: Int, val releases: Int)
    val hits = body["release-groups"]?.jsonArray.orEmpty().mapNotNull { el ->
        val group = el.jsonObject
        val id = group.string("id") ?: return@mapNotNull null
        val score = group["score"]?.jsonPrimitive?.intOrNull ?: 0
        val title = group.string("title") ?: return@mapNotNull null
        if (score < MIN_SEARCH_SCORE || albumTitleKey(title) != want) return@mapNotNull null
        if (!artistMatches(artist, group.creditLine())) return@mapNotNull null
        Hit(id, group.isOtherTake(), score, group["count"]?.jsonPrimitive?.intOrNull ?: 0)
    }
    return hits.sortedWith(
        compareBy<Hit> { it.otherTake }.thenByDescending { it.releases }.thenByDescending { it.score },
    ).firstOrNull()?.id
}

/**
 * The release group of the edition [album] by [artist] in a release search
 * ([com.stash.core.data.musicbrainz.MusicBrainzClient.searchReleases]), for an edition titled apart
 * from its album: "OK Computer OKNOTOK 1997 2017" is eight releases in the release group "OK
 * Computer" (2026-10-25). Releases titled like it, credited to the artist and scored at least
 * [MIN_SEARCH_SCORE]; the group most of them belong to, one that isn't another take first. Null
 * when none fits.
 */
internal fun releaseGroupFromReleaseSearch(body: JsonObject, album: String, artist: String): String? {
    val want = albumTitleKey(album)
    val groups = body["releases"]?.jsonArray.orEmpty().mapNotNull { el ->
        val release = el.jsonObject
        val score = release["score"]?.jsonPrimitive?.intOrNull ?: 0
        val title = release.string("title") ?: return@mapNotNull null
        if (score < MIN_SEARCH_SCORE || albumTitleKey(title) != want) return@mapNotNull null
        if (!artistMatches(artist, release.creditLine())) return@mapNotNull null
        release["release-group"] as? JsonObject
    }
    return groups.groupBy { it.string("id") }
        .filterKeys { it != null }
        .entries
        .sortedWith(compareBy<Map.Entry<String?, List<JsonObject>>> { it.value.first().isOtherTake() }.thenByDescending { it.value.size })
        .firstOrNull()?.key
}

/**
 * The releases in a browse whose archive art is known to be a front at most: the browse's
 * `cover-art-archive` summary says no artwork, or one image and that the front. Their listings
 * can't hold a back or an inside page. A release without a summary isn't among them.
 */
internal fun releasesWithoutMoreArt(body: JsonObject): Set<String> =
    body["releases"]?.jsonArray.orEmpty().mapNotNullTo(HashSet()) { el ->
        val release = el as? JsonObject ?: return@mapNotNullTo null
        val id = release.string("id") ?: return@mapNotNullTo null
        val art = release["cover-art-archive"] as? JsonObject ?: return@mapNotNullTo null
        fun flag(name: String) = (art[name] as? JsonPrimitive)?.booleanOrNull
        val count = (art["count"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNullTo null
        val front = if (flag("front") == true) 1 else 0
        id.takeIf { flag("artwork") == false || (flag("back") != true && count <= front) }
    }

/** Below this a release group search hit is a guess (MusicBrainz scores 0-100). */
internal const val MIN_SEARCH_SCORE = 80

/**
 * A release group's releases ([com.stash.core.data.musicbrainz.MusicBrainzClient.browseReleases]) in
 * the order their back covers are looked for: vinyl first (a sleeve's back is what the flip
 * imitates, and LP backs are square like the cover), then CD, then the rest; digital releases,
 * whose listings rarely hold more than a front, last. MusicBrainz's own order within each.
 */
internal fun rankReleasesForBack(body: JsonObject): List<String> =
    body["releases"]?.jsonArray.orEmpty().mapNotNull { el ->
        val release = el.jsonObject
        val id = release.string("id") ?: return@mapNotNull null
        val formats = release["media"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.string("format") }
        val rank = when {
            formats.any { "vinyl" in it.lowercase() } -> 0
            formats.any { "cd" in it.lowercase() } -> 1
            formats.any { it.equals("Digital Media", ignoreCase = true) } -> 3
            else -> 2
        }
        id to rank
    }.sortedBy { it.second }.map { it.first }

/**
 * The scans of an album's inside, by the archive's types: a CD's booklet pages, a gatefold's or
 * digipak's panels, a vinyl's inner sleeve (Liner), the tray under the disc.
 */
private val INSIDE_TYPES = setOf("Booklet", "Panel", "Liner", "Tray")

/**
 * The inside pages in a release's listing, in the archive's order (the order editors give the
 * pages): images typed [INSIDE_TYPES] that aren't the front or back (a booklet's cover is the
 * album's front) and aren't unedited scans.
 */
internal fun insidePages(listing: CaaListing): List<CaaImage> =
    listing.images.filter { img ->
        !img.front && !img.back && img.types.any { it in INSIDE_TYPES } &&
            img.types.none { it == "Front" || it == "Back" || it == "Raw/Unedited" }
    }

/**
 * The back cover in a release's listing: the image marked as the main back, else one typed Back
 * that isn't an unedited scan (a scanner's lid, a crooked photo), else any typed Back.
 */
internal fun backCoverImage(listing: CaaListing): CaaImage? =
    listing.images.firstOrNull { it.back }
        ?: listing.images.firstOrNull { "Back" in it.types && "Raw/Unedited" !in it.types }
        ?: listing.images.firstOrNull { "Back" in it.types }
