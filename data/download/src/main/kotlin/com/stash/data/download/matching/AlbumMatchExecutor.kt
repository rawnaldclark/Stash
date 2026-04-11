package com.stash.data.download.matching

import android.util.Log
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Album-based track matching executor for YouTube Music.
 *
 * Instead of searching for individual tracks (which often returns unreliable
 * video IDs due to InnerTube's fuzzy song-matching), this executor:
 * 1. Searches for the album by name
 * 2. Browses the album's structured tracklist
 * 3. Finds the exact track within it by title + duration matching
 *
 * Album tracklists contain the canonical video IDs for each track, which are
 * far more reliable than search-based IDs. This approach also benefits from
 * caching: once an album is fetched, all tracks from that album can be matched
 * without additional API calls.
 */
@Singleton
class AlbumMatchExecutor @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
    private val trackMatcher: TrackMatcher,
) {
    companion object {
        private const val TAG = "AlbumMatch"

        /** Minimum title similarity for pass 1 (lenient duration). */
        private const val PASS1_TITLE_THRESHOLD = 0.85

        /** Maximum duration difference in seconds for pass 1. */
        private const val PASS1_DURATION_TOLERANCE_SEC = 5

        /** Minimum title similarity for pass 2 (strict duration). */
        private const val PASS2_TITLE_THRESHOLD = 0.70

        /** Maximum duration difference in seconds for pass 2. */
        private const val PASS2_DURATION_TOLERANCE_SEC = 3

        /** Minimum album title similarity to accept a search result as the correct album. */
        private const val ALBUM_TITLE_THRESHOLD = 0.80

        /** Regex to strip parenthetical/bracketed suffixes like "(Deluxe Edition)", "[Remastered]". */
        private val PARENTHETICAL_REGEX = Regex("""\s*[\(\[][^)\]]*[\)\]]""")
    }

    /**
     * Represents a single track extracted from an album's browse response.
     *
     * @property videoId  The YouTube video ID for this track.
     * @property title    The track title as listed in the album.
     * @property durationSeconds The track duration in seconds, or 0.0 if unparseable.
     */
    private data class AlbumTrack(
        val videoId: String,
        val title: String,
        val durationSeconds: Double,
    )

    /**
     * Wrapper to distinguish "never looked up" from "looked up, album not found".
     */
    private sealed class CacheEntry {
        data class Found(val tracks: List<AlbumTrack>) : CacheEntry()
        data object NotFound : CacheEntry()
    }

    /**
     * LRU cache of album tracklists keyed by "artist|album" (lowercased).
     * Capped at 200 entries to prevent unbounded memory growth during large syncs.
     */
    private val albumCache = object : LinkedHashMap<String, CacheEntry>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 200
        }
    }
    private val cacheLock = Any()

    /**
     * Attempts to find a track by searching for its album on YouTube Music and
     * matching the track within the album's tracklist.
     *
     * @param targetTitle     The track title to find (e.g. "Bohemian Rhapsody").
     * @param targetArtist    The track artist (e.g. "Queen").
     * @param targetAlbum     The album name (e.g. "A Night at the Opera").
     * @param targetDurationMs The expected track duration in milliseconds.
     * @return A [YtDlpSearchResult] with the album-sourced video ID, or null
     *         if the album was not found or the track could not be matched.
     */
    suspend fun findTrackInAlbum(
        targetTitle: String,
        targetArtist: String,
        targetAlbum: String,
        targetDurationMs: Long,
    ): YtDlpSearchResult? = withContext(Dispatchers.IO) {
        try {
            if (targetAlbum.isBlank()) {
                Log.d(TAG, "No album info for '$targetTitle' — skipping album match")
                return@withContext null
            }
            if (targetDurationMs <= 0) {
                Log.d(TAG, "No duration info for '$targetTitle' — skipping album match")
                return@withContext null
            }

            val cacheKey = "${targetArtist.lowercase()}|${targetAlbum.lowercase()}"
            Log.d(TAG, "findTrackInAlbum: '$targetTitle' by '$targetArtist' on '$targetAlbum' (cache key: $cacheKey)")

            // Check cache first — returns cached tracklist or fetches it
            val tracks = getOrFetchAlbumTracks(cacheKey, targetArtist, targetAlbum)
            if (tracks == null) {
                Log.d(TAG, "Album not found or unparseable: '$targetAlbum' by '$targetArtist'")
                return@withContext null
            }

            Log.d(TAG, "Album tracklist has ${tracks.size} tracks, matching '$targetTitle'")

            // Match the target track within the album tracklist
            val targetDurationSec = targetDurationMs / 1000.0
            val matched = matchTrackInList(targetTitle, targetDurationSec, tracks)

            if (matched == null) {
                Log.d(TAG, "No match for '$targetTitle' (${targetDurationSec}s) in album '$targetAlbum'")
                return@withContext null
            }

            Log.d(TAG, "Matched: '${matched.title}' (${matched.durationSeconds}s) -> videoId=${matched.videoId}")

            // Convert to YtDlpSearchResult for the scoring pipeline
            YtDlpSearchResult(
                id = matched.videoId,
                title = matched.title,
                uploader = targetArtist,
                uploaderId = "",
                // Album tracks are official studio recordings — give them the Topic bonus
                channel = "$targetArtist - Topic",
                duration = matched.durationSeconds,
                viewCount = 0,
                webpageUrl = "https://www.youtube.com/watch?v=${matched.videoId}",
                url = "",
                likeCount = null,
                description = "",
                album = targetAlbum,
            )
        } catch (e: Exception) {
            Log.e(TAG, "findTrackInAlbum failed for '$targetTitle' on '$targetAlbum'", e)
            null
        }
    }

    // ── Album Lookup ────────────────────────────────────────────────────

    /**
     * Returns cached album tracks or fetches them from InnerTube.
     *
     * Uses [albumCache] to avoid redundant API calls when multiple tracks
     * from the same album are being matched in sequence.
     *
     * @param cacheKey     The "artist|album" cache key (lowercased).
     * @param targetArtist The artist name for the search query.
     * @param targetAlbum  The album name for the search query.
     * @return List of album tracks, or null if the album could not be found/parsed.
     */
    private suspend fun getOrFetchAlbumTracks(
        cacheKey: String,
        targetArtist: String,
        targetAlbum: String,
    ): List<AlbumTrack>? {
        // Fast path: check cache atomically
        val cached = synchronized(cacheLock) { albumCache[cacheKey] }
        if (cached != null) {
            Log.d(TAG, "Cache hit for '$cacheKey': ${if (cached is CacheEntry.Found) "${cached.tracks.size} tracks" else "not found"}")
            return (cached as? CacheEntry.Found)?.tracks
        }

        // Build query variations to try
        val queries = buildSearchQueries(targetArtist, targetAlbum)
        var browseId: String? = null

        for (query in queries) {
            Log.d(TAG, "Searching InnerTube for album: '$query'")
            val response = innerTubeClient.search(query) ?: continue
            browseId = extractAlbumBrowseId(response, targetAlbum, targetArtist)
            if (browseId != null) {
                Log.d(TAG, "Found album browseId=$browseId from query '$query'")
                break
            }
        }

        if (browseId == null) {
            Log.d(TAG, "No album browseId found for '$targetAlbum' by '$targetArtist'")
            synchronized(cacheLock) { albumCache[cacheKey] = CacheEntry.NotFound }
            return null
        }

        // Browse the album to get the tracklist
        val browseResponse = innerTubeClient.browse(browseId)
        if (browseResponse == null) {
            Log.w(TAG, "Album browse failed for browseId=$browseId")
            synchronized(cacheLock) { albumCache[cacheKey] = CacheEntry.NotFound }
            return null
        }

        val tracks = extractAlbumTracks(browseResponse)
        if (tracks.isEmpty()) {
            Log.w(TAG, "No tracks parsed from album browse for browseId=$browseId")
            synchronized(cacheLock) { albumCache[cacheKey] = CacheEntry.NotFound }
            return null
        }

        Log.d(TAG, "Parsed ${tracks.size} tracks from album '$targetAlbum': ${tracks.map { "'${it.title}' (${it.durationSeconds}s)" }}")
        synchronized(cacheLock) { albumCache[cacheKey] = CacheEntry.Found(tracks) }
        return tracks
    }

    /**
     * Builds a list of search query variations to try, in order of specificity.
     *
     * 1. "Artist Album" (exact album name)
     * 2. "Artist CleanAlbum" (parenthetical suffixes stripped, e.g. "(Deluxe Edition)")
     *
     * The second variation is only added if it differs from the first.
     *
     * @param artist The artist name.
     * @param album  The album name.
     * @return Ordered list of search queries to try.
     */
    private fun buildSearchQueries(artist: String, album: String): List<String> {
        val queries = mutableListOf("$artist $album")

        val cleanAlbum = album.replace(PARENTHETICAL_REGEX, "").trim()
        if (cleanAlbum != album && cleanAlbum.isNotBlank()) {
            queries.add("$artist $cleanAlbum")
        }

        return queries
    }

    // ── Album Search Response Parsing ───────────────────────────────────

    /**
     * Extracts an album browseId from an InnerTube search response.
     *
     * Checks two locations where album results can appear:
     * 1. **Top result card** (`musicCardShelfRenderer`) — the highlighted result
     * 2. **Albums shelf** (`musicShelfRenderer` with title "Albums") — the dedicated section
     *
     * Each candidate is verified against the target album title (via Jaro-Winkler
     * similarity) and the target artist (via substring check in the subtitle).
     *
     * @param response      The full InnerTube search response.
     * @param targetAlbum   The album name to verify against.
     * @param targetArtist  The artist name to verify against.
     * @return The browseId (e.g. "MPREb_...") of the matched album, or null.
     */
    private fun extractAlbumBrowseId(
        response: JsonObject,
        targetAlbum: String,
        targetArtist: String,
    ): String? {
        // Navigate to the sections list
        val sections = response
            .navigatePath("contents", "tabbedSearchResultsRenderer", "tabs")
            ?.jsonArray?.getOrNull(0)?.jsonObject
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.jsonArray

        if (sections == null) {
            Log.d(TAG, "No sections in search response")
            return null
        }

        // Strategy 1: Check musicCardShelfRenderer (top result card)
        for (section in sections) {
            val cardShelf = section.jsonObject["musicCardShelfRenderer"]?.jsonObject
                ?: continue

            val browseId = extractBrowseIdFromCardShelf(cardShelf, targetAlbum, targetArtist)
            if (browseId != null) return browseId
        }

        // Strategy 2: Check musicShelfRenderer with title "Albums"
        for (section in sections) {
            val shelf = section.jsonObject["musicShelfRenderer"]?.jsonObject
                ?: continue

            val shelfTitle = shelf.navigatePath("title", "runs")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: continue

            if (!shelfTitle.equals("Albums", ignoreCase = true)) continue

            val browseId = extractBrowseIdFromAlbumShelf(shelf, targetAlbum, targetArtist)
            if (browseId != null) return browseId
        }

        Log.d(TAG, "No matching album found in search response sections")
        return null
    }

    /**
     * Extracts a browseId from a `musicCardShelfRenderer` (top result card).
     *
     * The card shelf contains:
     * - `title.runs[0].text` — the album title
     * - `title.runs[0].navigationEndpoint.browseEndpoint.browseId` — the browseId
     * - `subtitle.runs[]` — array containing ["Album", " . ", "Artist Name", ...]
     *
     * @return The browseId if the card matches our target album+artist, null otherwise.
     */
    private fun extractBrowseIdFromCardShelf(
        cardShelf: JsonObject,
        targetAlbum: String,
        targetArtist: String,
    ): String? {
        val titleRuns = cardShelf.navigatePath("title", "runs")?.jsonArray
        if (titleRuns.isNullOrEmpty()) return null

        val firstRun = titleRuns[0].jsonObject
        val albumTitle = firstRun["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val browseId = firstRun
            .navigatePath("navigationEndpoint", "browseEndpoint", "browseId")
            ?.jsonPrimitive?.contentOrNull

        if (browseId == null || !browseId.startsWith("MPRE")) {
            Log.d(TAG, "Card shelf browseId is null or not an album: $browseId")
            return null
        }

        // Verify this is actually an album by checking subtitle contains "Album"
        val subtitleRuns = cardShelf.navigatePath("subtitle", "runs")?.jsonArray
        val subtitleTexts = subtitleRuns
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?: emptyList()
        val subtitleJoined = subtitleTexts.joinToString("")

        val isAlbumType = subtitleTexts.any { it.equals("Album", ignoreCase = true) || it.equals("EP", ignoreCase = true) }
        if (!isAlbumType) {
            Log.d(TAG, "Card shelf is not Album/EP type: subtitle='$subtitleJoined'")
            return null
        }

        // Verify album title similarity
        if (!isAlbumTitleMatch(albumTitle, targetAlbum)) {
            Log.d(TAG, "Card shelf title mismatch: found='$albumTitle', target='$targetAlbum'")
            return null
        }

        // Verify artist is mentioned in subtitle
        if (!isArtistInSubtitle(subtitleJoined, targetArtist)) {
            Log.d(TAG, "Card shelf artist mismatch: subtitle='$subtitleJoined', target='$targetArtist'")
            return null
        }

        Log.d(TAG, "Card shelf match: '$albumTitle' by subtitle='$subtitleJoined' -> $browseId")
        return browseId
    }

    /**
     * Extracts a browseId from a `musicShelfRenderer` (the "Albums" shelf section).
     *
     * Each item in the shelf is a `musicResponsiveListItemRenderer` with:
     * - `navigationEndpoint.browseEndpoint.browseId` — the browseId
     * - `flexColumns[0]` — album title
     * - `flexColumns[1]` — "Album . Artist . Year"
     *
     * @return The browseId of the first item matching our target, or null.
     */
    private fun extractBrowseIdFromAlbumShelf(
        shelf: JsonObject,
        targetAlbum: String,
        targetArtist: String,
    ): String? {
        val contents = shelf["contents"]?.jsonArray ?: return null

        for (item in contents) {
            val renderer = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject
                ?: continue

            // Extract browseId from navigation endpoint
            val browseId = renderer
                .navigatePath("navigationEndpoint", "browseEndpoint", "browseId")
                ?.jsonPrimitive?.contentOrNull

            if (browseId == null || !browseId.startsWith("MPRE")) continue

            // Extract album title from flexColumns[0]
            val flexColumns = renderer["flexColumns"]?.jsonArray ?: continue
            val albumTitle = flexColumns.getOrNull(0)?.jsonObject
                ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: continue

            // Extract subtitle info from flexColumns[1] for artist verification
            val subtitleRuns = flexColumns.getOrNull(1)?.jsonObject
                ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                ?.jsonArray
            val subtitleJoined = subtitleRuns
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                ?: ""

            // Verify album title
            if (!isAlbumTitleMatch(albumTitle, targetAlbum)) {
                Log.d(TAG, "Shelf item title mismatch: found='$albumTitle', target='$targetAlbum'")
                continue
            }

            // Verify artist
            if (!isArtistInSubtitle(subtitleJoined, targetArtist)) {
                Log.d(TAG, "Shelf item artist mismatch: subtitle='$subtitleJoined', target='$targetArtist'")
                continue
            }

            Log.d(TAG, "Shelf item match: '$albumTitle' -> $browseId")
            return browseId
        }

        return null
    }

    /**
     * Checks whether a found album title sufficiently matches the target album title.
     *
     * Uses Jaro-Winkler similarity on canonicalized (lowercased, stripped) titles.
     * Also tries stripping parentheticals from both sides before comparing.
     *
     * @param found  The album title from the search result.
     * @param target The album title we are looking for.
     * @return True if the similarity meets the [ALBUM_TITLE_THRESHOLD].
     */
    private fun isAlbumTitleMatch(found: String, target: String): Boolean {
        val sim = trackMatcher.jaroWinklerSimilarity(
            found.lowercase().trim(),
            target.lowercase().trim(),
        )
        if (sim >= ALBUM_TITLE_THRESHOLD) return true

        // Try with parentheticals stripped from both sides
        val cleanFound = found.replace(PARENTHETICAL_REGEX, "").lowercase().trim()
        val cleanTarget = target.replace(PARENTHETICAL_REGEX, "").lowercase().trim()
        val cleanSim = trackMatcher.jaroWinklerSimilarity(cleanFound, cleanTarget)

        Log.d(TAG, "Album title similarity: raw=${"%.3f".format(sim)}, clean=${"%.3f".format(cleanSim)} ('$found' vs '$target')")
        return cleanSim >= ALBUM_TITLE_THRESHOLD
    }

    /**
     * Checks whether the target artist name appears in the album subtitle text.
     *
     * Uses a case-insensitive substring check on the canonical (lowercase) forms.
     * This is intentionally lenient — the subtitle may contain "Various Artists",
     * the full artist name, or just a partial match.
     *
     * @param subtitle     The joined subtitle text (e.g. "Album . Queen . 1975").
     * @param targetArtist The artist we are looking for (e.g. "Queen").
     * @return True if the artist appears in the subtitle.
     */
    private fun isArtistInSubtitle(subtitle: String, targetArtist: String): Boolean {
        val lowerSubtitle = subtitle.lowercase()
        val lowerArtist = targetArtist.lowercase()

        // Direct substring match
        if (lowerSubtitle.contains(lowerArtist)) return true

        // Try matching just the primary artist (before any separators)
        val primaryArtist = targetArtist.split(Regex("[,;&/]")).firstOrNull()?.trim()?.lowercase()
        if (primaryArtist != null && lowerSubtitle.contains(primaryArtist)) return true

        return false
    }

    // ── Album Browse Response Parsing ───────────────────────────────────

    /**
     * Extracts all tracks from an album browse response.
     *
     * Album browse responses contain `musicResponsiveListItemRenderer` items
     * with `playlistItemData.videoId`. The exact nesting path varies across
     * different album types (standard, EP, compilation), so we use a recursive
     * finder that searches the entire JSON tree for matching renderers.
     *
     * @param response The full InnerTube browse response.
     * @return List of [AlbumTrack] items found in the response.
     */
    private fun extractAlbumTracks(response: JsonObject): List<AlbumTrack> {
        val renderers = mutableListOf<JsonObject>()
        findRenderers(response, "musicResponsiveListItemRenderer", renderers)

        Log.d(TAG, "Found ${renderers.size} musicResponsiveListItemRenderer items in browse response")

        return renderers.mapNotNull { renderer -> parseAlbumTrack(renderer) }
    }

    /**
     * Recursively finds all JSON objects matching a given key anywhere in the tree.
     *
     * This handles the fact that InnerTube album responses can nest tracks at
     * varying depths depending on album type, disc count, etc.
     *
     * @param element    The current JSON element to search.
     * @param targetKey  The key name to look for (e.g. "musicResponsiveListItemRenderer").
     * @param results    Accumulator list for matched JSON objects.
     */
    private fun findRenderers(
        element: JsonElement,
        targetKey: String,
        results: MutableList<JsonObject>,
    ) {
        when (element) {
            is JsonObject -> {
                // Check if this object has our target key with a playlistItemData child
                val renderer = element[targetKey]?.jsonObject
                if (renderer != null && renderer.containsKey("playlistItemData")) {
                    results.add(renderer)
                }
                // Recurse into all values
                for ((_, value) in element) {
                    findRenderers(value, targetKey, results)
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findRenderers(item, targetKey, results)
                }
            }
            else -> { /* primitive — nothing to recurse into */ }
        }
    }

    /**
     * Parses a single `musicResponsiveListItemRenderer` from an album browse
     * response into an [AlbumTrack].
     *
     * Extracts:
     * - **videoId** from `playlistItemData.videoId`
     * - **title** from `flexColumns[0].musicResponsiveListItemFlexColumnRenderer.text.runs[0].text`
     * - **duration** from `fixedColumns[0].musicResponsiveListItemFixedColumnRenderer.text.runs[0].text`
     *
     * @param renderer The `musicResponsiveListItemRenderer` JSON object.
     * @return An [AlbumTrack], or null if essential fields are missing.
     */
    private fun parseAlbumTrack(renderer: JsonObject): AlbumTrack? {
        val videoId = renderer["playlistItemData"]?.jsonObject
            ?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: return null

        val flexColumns = renderer["flexColumns"]?.jsonArray
        val title = flexColumns?.getOrNull(0)?.jsonObject
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer", "text", "runs"
            )?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: return null

        val durationText = renderer["fixedColumns"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.navigatePath(
                "musicResponsiveListItemFixedColumnRenderer", "text", "runs"
            )?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

        val durationSeconds = parseDurationToSeconds(durationText)

        return AlbumTrack(
            videoId = videoId,
            title = title,
            durationSeconds = durationSeconds,
        )
    }

    // ── Track Matching ──────────────────────────────────────────────────

    /**
     * Matches a target track against an album tracklist using a two-pass approach.
     *
     * **Pass 1** (high confidence): Title similarity >= 0.85 AND duration within 5 seconds.
     * Picks the highest-similarity match.
     *
     * **Pass 2** (relaxed): Title similarity >= 0.70 AND duration within 3 seconds.
     * Tighter duration to compensate for lower title confidence. Only runs if pass 1
     * found nothing.
     *
     * Both passes use [TrackMatcher.canonicalTitle] for normalization and
     * [TrackMatcher.jaroWinklerSimilarity] for fuzzy comparison.
     *
     * @param targetTitle      The track title to find.
     * @param targetDurationSec The expected duration in seconds.
     * @param tracks           The album tracklist to search.
     * @return The best matching [AlbumTrack], or null if no match meets the thresholds.
     */
    private fun matchTrackInList(
        targetTitle: String,
        targetDurationSec: Double,
        tracks: List<AlbumTrack>,
    ): AlbumTrack? {
        val canonTarget = trackMatcher.canonicalTitle(targetTitle)

        // Pass 1: High title similarity + lenient duration
        val pass1Match = tracks
            .map { track ->
                val canonTrack = trackMatcher.canonicalTitle(track.title)
                val sim = trackMatcher.jaroWinklerSimilarity(canonTarget, canonTrack)
                val durationDiff = abs(targetDurationSec - track.durationSeconds)
                Triple(track, sim, durationDiff)
            }
            .filter { (_, sim, durationDiff) ->
                sim >= PASS1_TITLE_THRESHOLD && durationDiff <= PASS1_DURATION_TOLERANCE_SEC
            }
            .maxByOrNull { (_, sim, _) -> sim }

        if (pass1Match != null) {
            val (track, sim, durationDiff) = pass1Match
            Log.d(TAG, "Pass 1 match: '${track.title}' sim=${"%.3f".format(sim)} durationDiff=${"%.1f".format(durationDiff)}s")
            return track
        }

        // Pass 2: Lower title threshold + strict duration
        val pass2Match = tracks
            .map { track ->
                val canonTrack = trackMatcher.canonicalTitle(track.title)
                val sim = trackMatcher.jaroWinklerSimilarity(canonTarget, canonTrack)
                val durationDiff = abs(targetDurationSec - track.durationSeconds)
                Triple(track, sim, durationDiff)
            }
            .filter { (_, sim, durationDiff) ->
                sim >= PASS2_TITLE_THRESHOLD && durationDiff <= PASS2_DURATION_TOLERANCE_SEC
            }
            .maxByOrNull { (_, sim, _) -> sim }

        if (pass2Match != null) {
            val (track, sim, durationDiff) = pass2Match
            Log.d(TAG, "Pass 2 match: '${track.title}' sim=${"%.3f".format(sim)} durationDiff=${"%.1f".format(durationDiff)}s")
            return track
        }

        // Log all candidates for debugging when no match is found
        Log.d(TAG, "No match found. Album tracks vs target '$targetTitle' (${targetDurationSec}s):")
        tracks.forEach { track ->
            val canonTrack = trackMatcher.canonicalTitle(track.title)
            val sim = trackMatcher.jaroWinklerSimilarity(canonTarget, canonTrack)
            val durationDiff = abs(targetDurationSec - track.durationSeconds)
            Log.d(TAG, "  '${track.title}' sim=${"%.3f".format(sim)} durationDiff=${"%.1f".format(durationDiff)}s")
        }

        return null
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**
     * Converts a duration string like "3:45" or "1:02:30" to seconds.
     *
     * @param duration The duration string in "M:SS" or "H:MM:SS" format.
     * @return Duration in seconds, or 0.0 if the format is unrecognized.
     */
    private fun parseDurationToSeconds(duration: String?): Double {
        if (duration == null) return 0.0
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60.0 + parts[1])
            3 -> (parts[0] * 3600.0 + parts[1] * 60.0 + parts[2])
            else -> 0.0
        }
    }

    /**
     * Navigates nested JSON objects by key path, returning null if any key is missing.
     *
     * Identical to the helper in [InnerTubeSearchExecutor] — duplicated here
     * because that version is file-private.
     */
    private fun JsonObject.navigatePath(vararg keys: String): JsonElement? {
        var current: JsonElement = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }
}
