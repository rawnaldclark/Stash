package com.stash.core.data.backcover

import com.stash.core.common.VersionWords
import com.stash.core.common.primaryArtist
import com.stash.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** [text] lowercased, accents and punctuation gone, "&" read as "and": "Short n' Sweet" -> "short n sweet". */
private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase().replace("&", " and "), Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace(NOT_WORD, " ")
        .trim()

/**
 * An album title as a back cover lookup compares it: the title without its edition
 * ("Rumours (Super Deluxe)", "Nevermind - Remastered" are "rumours", "nevermind"), but with any
 * performance it names, so "1989 (Taylor's Version)" never takes the original's back.
 */
internal fun albumTitleKey(title: String): String {
    val base = fold(title.replace(BRACKETS, " ").replace(VersionWords.DASH_SUFFIX, ""))
        .ifEmpty { fold(title) } // a title that is all brackets: "[Untitled]"
    val versions = VersionWords.of(title).sorted()
    return (listOf(base) + versions).joinToString("|")
}

/**
 * [title] as a search asks for it: without brackets or a dash suffix, so a release group search
 * for "Rumours (Super Deluxe)" can find "Rumours" (a phrase search needs every word).
 */
internal fun plainAlbumTitle(title: String): String =
    title.replace(BRACKETS, " ").replace(VersionWords.DASH_SUFFIX, "").trim().replace(Regex("\\s+"), " ")
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
 * The release group of [album] among the releases of an ISRC's recordings
 * ([com.stash.core.data.musicbrainz.MusicBrainzClient.searchRecordingsByIsrc]): one whose title, or
 * whose release's title, is the album's. A song is on many release groups (its album, singles,
 * compilations); the plain album beats one with secondary types (Compilation, Live, Demo...), then
 * the one the most releases are on. Null when none is titled like the album.
 */
internal fun releaseGroupFromIsrcSearch(body: JsonObject, album: String): String? {
    val want = albumTitleKey(album)
    data class Seen(val id: String, val plain: Boolean, var releases: Int)
    val groups = LinkedHashMap<String, Seen>()
    for (rec in body["recordings"]?.jsonArray.orEmpty()) {
        for (rel in rec.jsonObject["releases"]?.jsonArray.orEmpty()) {
            val release = rel.jsonObject
            val group = release["release-group"] as? JsonObject ?: continue
            val id = group.string("id") ?: continue
            val titled = listOfNotNull(group.string("title"), release.string("title")).any { albumTitleKey(it) == want }
            if (!titled) continue
            groups.getOrPut(id) { Seen(id, group.strings("secondary-types").isEmpty(), 0) }.releases++
        }
    }
    return groups.values.sortedWith(compareByDescending<Seen> { it.plain }.thenByDescending { it.releases })
        .firstOrNull()?.id
}

/**
 * The release group of [album] by [artist] in a release group search
 * ([com.stash.core.data.musicbrainz.MusicBrainzClient.searchReleaseGroups]): titled like the
 * album, credited to the artist and scored at least [MIN_SEARCH_SCORE]. The plain album beats one
 * with secondary types (a search for "My Beautiful Dark Twisted Fantasy" also finds a Demo), then
 * the higher score, then the one with more releases. Null when none fits.
 */
internal fun releaseGroupFromSearch(body: JsonObject, album: String, artist: String): String? {
    val want = albumTitleKey(album)
    data class Hit(val id: String, val plain: Boolean, val score: Int, val releases: Int)
    val hits = body["release-groups"]?.jsonArray.orEmpty().mapNotNull { el ->
        val group = el.jsonObject
        val id = group.string("id") ?: return@mapNotNull null
        val score = group["score"]?.jsonPrimitive?.intOrNull ?: 0
        val title = group.string("title") ?: return@mapNotNull null
        if (score < MIN_SEARCH_SCORE || albumTitleKey(title) != want) return@mapNotNull null
        if (!artistMatches(artist, group.creditLine())) return@mapNotNull null
        Hit(id, group.strings("secondary-types").isEmpty(), score, group["count"]?.jsonPrimitive?.intOrNull ?: 0)
    }
    return hits.sortedWith(
        compareByDescending<Hit> { it.plain }.thenByDescending { it.score }.thenByDescending { it.releases },
    ).firstOrNull()?.id
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
 * The back cover in a release's listing: the image marked as the main back, else one typed Back
 * that isn't an unedited scan (a scanner's lid, a crooked photo), else any typed Back.
 */
internal fun backCoverImage(listing: CaaListing): CaaImage? =
    listing.images.firstOrNull { it.back }
        ?: listing.images.firstOrNull { "Back" in it.types && "Raw/Unedited" !in it.types }
        ?: listing.images.firstOrNull { "Back" in it.types }
