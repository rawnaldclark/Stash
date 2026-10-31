package com.stash.feature.record.art

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URLEncoder

/** An HTTP answer as the lookup reads it: the status (0 for no answer at all) and, when it was a success, the body. */
data class ArtReply(val status: Int, val body: String? = null)

/** One GET (the lookup's only kind of request). Never throws for the network: no answer is status 0. */
fun interface ArtHttp {
    suspend fun get(url: String): ArtReply
}

/** What one lookup found. */
sealed interface Found {
    /** A cover's address, or null: none known (kept as a miss for a while). */
    data class Cover(val url: String?) : Found

    /** Deezer wouldn't look it up just now (its quota): nothing kept, asked again in a while. */
    data object Later : Found

    /** No answer, or a refusal (429, 5xx): nothing kept, and no more asking for a while. */
    data object Refused : Found
}

/**
 * A search service's pace: one search at a time, [gapMs] apart, across every lookup in the app (Deezer allows 50 in 5 s from
 * one address; Apple about 20 a minute). A search that would wait longer than [maxWaitMs] for its turn isn't queued.
 */
class Pace(
    private val gapMs: Long = GAP_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val now: () -> Long = System::currentTimeMillis,
    val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var next = 0L

    /** Waits for this search's turn; false (no wait) when the turn is too far off. */
    suspend fun turn(): Boolean {
        val wait = lock.withLock {
            val t = maxOf(now(), next)
            if (t - now() > maxWaitMs) return false
            next = t + gapMs
            t - now()
        }
        if (wait > 0) sleep(wait)
        return true
    }

    companion object {
        /** worker art.ts `DEEZER_GAP_MS`. */
        const val GAP_MS = 110L

        /** worker art.ts `DEEZER_MAX_WAIT_MS`. */
        const val MAX_WAIT_MS = 8_000L

        /** Apple's search: about 20 a minute. */
        const val ITUNES_GAP_MS = 3_000L

        /** No more than a few of Apple's searches queued; the rest are asked later. */
        const val ITUNES_MAX_WAIT_MS = 12_000L

        /** No asking Apple for this long after it refused (Deezer is asked as usual meanwhile). */
        const val ITUNES_PAUSE_MS = 60_000L
    }
}

/**
 * An album's cover, a song's, or an artist's picture, found by their words: stash-player `worker/art.ts` `lookUp`, run on the
 * phone (the app has no Worker in between). Deezer first (its search allows far more than Apple's ~20 a minute): the album by
 * its artist and name, then the song by its words; then Apple's iTunes search, sized 600. An artist's picture comes from
 * Deezer.
 *
 * What leaves the phone: an album's, an artist's or a song's words, sent straight to Deezer and Apple from the phone's own
 * address (the web's Worker asks for the player, so there they see the Worker's; here they see the listener's IP).
 *
 * Deezer's song search with field filters (`artist:"…" track:"…"`, even `artist:"…"` alone) answers nothing since 2026-10
 * (checked 10-26; its album and artist searches still take them), so the song step searches the words as free text and the
 * hits are still held to the artist and the title.
 *
 * Only covers the record may show are taken ([isAllowedCover]): its page allows images from Deezer's CDNs and Apple's mzstatic
 * alone, so a hit elsewhere counts as no hit.
 *
 * Apple refusing (403, 429) stops the asking of Apple alone, for [Pace.ITUNES_PAUSE_MS]: Deezer, which finds most covers,
 * goes on, and what only Apple could find is "later" meanwhile.
 */
class ArtLookup(
    private val http: ArtHttp,
    private val pace: Pace = Pace(),
    private val applePace: Pace = Pace(gapMs = Pace.ITUNES_GAP_MS, maxWaitMs = Pace.ITUNES_MAX_WAIT_MS),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Apple refused: none of its searches until then. */
    @Volatile private var appleUntil = 0L

    /** What Deezer said: its body, or why there is none. */
    private sealed interface Dz {
        data class Ok(val body: JsonObject) : Dz
        data class No(val found: Found) : Dz
    }

    suspend fun lookUp(s: ArtSong): Found {
        if (s.isArtist) return artistPicture(s)
        val artist = ArtText.primary(s.artist)
        if (!s.album.isNullOrEmpty()) {
            // TODO(art): Deezer's fielded album search is partly broken too (the web found 28 Oct 2026: Sepultura "Roots", Kendrick
            //  Lamar "DAMN." and Metallica "Master of Puppets" all miss; free text finds them). Change it with the web, not alone.
            val q = "artist:\"$artist\" album:\"${ArtText.bareAlbum(s.album)}\""
            when (val r = deezer("$DEEZER/search/album?q=${ArtText.encodeUriComponent(q)}&limit=10")) {
                is Dz.No -> return r.found
                is Dz.Ok -> best(r.body.list("data"), { it.obj("artist")?.str("name") }, { it.str("title") }, s.album, ArtText::bareAlbum, s.artist) {
                    it.str("cover_big")
                }?.let { return Found.Cover(it) }
            }
        }
        run {
            val q = "$artist ${ArtText.searchTitle(s.title)}"
            when (val r = deezer("$DEEZER/search?q=${ArtText.encodeUriComponent(q)}&limit=10")) {
                is Dz.No -> return r.found
                is Dz.Ok -> best(r.body.list("data"), { it.obj("artist")?.str("name") }, { it.str("title") }, s.title, ArtText::searchTitle, s.artist) {
                    it.obj("album")?.str("cover_big")
                }?.let { return Found.Cover(it) }
            }
        }
        val album = s.album?.takeIf { it.isNotEmpty() }
        val term = if (album != null) "$artist ${ArtText.bareAlbum(album)}" else "$artist ${ArtText.searchTitle(s.title)}"
        // Apple's limit is low: a search that can't have its turn soon, or that Apple refuses or doesn't answer, is "don't
        // know yet" (asked again later), never "none"
        if (now() < appleUntil || !applePace.turn()) return Found.Later
        val r = http.get("$ITUNES/search?${form("term" to term, "media" to "music", "entity" to if (album != null) "album" else "song", "limit" to "10")}")
        if (r.status == 403 || r.status == 429) {
            appleUntil = now() + Pace.ITUNES_PAUSE_MS
            return Found.Later
        }
        if (r.status == 0 || r.status >= 500) return Found.Later
        if (r.status in 200..299) {
            val hits = parse(r.body)?.list("results").orEmpty()
            val sized: (JsonObject) -> String? = { h -> h.str("artworkUrl100")?.replace(ITUNES_SIZE, "/600x600bb.jpg") }
            val hit = if (album != null) {
                best(hits, { it.str("artistName") }, { it.str("collectionName") }, album, ArtText::bareAlbum, s.artist, sized)
            } else {
                best(hits, { it.str("artistName") }, { it.str("trackName") }, s.title, ArtText::searchTitle, s.artist, sized)
            }
            if (hit != null) return Found.Cover(hit)
        }
        // Deezer answered and has none, and so does Apple: none, for a while
        return Found.Cover(null)
    }

    /** An artist's picture on Deezer (Apple's search has none): the same name first, else one that starts the same. */
    /** An artist's picture on Deezer (worker art.ts `artistPicture`: [artistSearch], then [pickArtist]). */
    private suspend fun artistPicture(s: ArtSong): Found {
        val body = when (val r = deezer(artistSearch(s.artist))) {
            is Dz.No -> return r.found
            is Dz.Ok -> r.body
        }
        return Found.Cover(pickArtist(body.list("data"), s.artist))
    }

    /**
     * A Deezer search, in its turn, asked again after a quota refusal ([RETRY_MS] apart). No answer or a 429/5xx is
     * [Found.Refused]; any other trouble (an error in the answer, the quota still spent, no turn soon) is [Found.Later].
     */
    private suspend fun deezer(url: String): Dz {
        var attempt = 0
        while (true) {
            if (!pace.turn()) return Dz.No(Found.Later)
            val r = http.get(url)
            if (r.status == 0 || r.status == 429 || r.status >= 500) return Dz.No(Found.Refused)
            if (r.status !in 200..299) return Dz.No(Found.Later)
            val body = parse(r.body) ?: return Dz.No(Found.Later)
            val error = body["error"]
            val code = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (code == QUOTA && attempt < RETRY_MS.size) {
                pace.sleep(RETRY_MS[attempt++])
                continue
            }
            return if (error.truthy()) Dz.No(Found.Later) else Dz.Ok(body)
        }
    }

    /** worker art.ts `best`: by artist, then name; an exact name first, else the first that starts the same. */
    private fun best(
        hits: List<JsonObject>,
        artistOf: (JsonObject) -> String?,
        nameOf: (JsonObject) -> String?,
        want: String,
        bare: (String) -> String,
        artist: String,
        cover: (JsonObject) -> String?,
    ): String? {
        var fallback: String? = null
        for (h in hits) {
            if (!sameArtist(artistOf(h), artist)) continue
            val c = allowed(cover(h)) ?: continue
            when (nameMatch(nameOf(h), want, bare)) {
                2 -> return c
                1 -> if (fallback == null) fallback = c
            }
        }
        return fallback
    }

    internal companion object {
        const val DEEZER = "https://api.deezer.com"
        const val ITUNES = "https://itunes.apple.com"

        /** Deezer's error code for "quota exceeded". */
        const val QUOTA = 4.0

        /** worker art.ts `DEEZER_RETRY_MS`. */
        val RETRY_MS = longArrayOf(1200, 2500)

        val ITUNES_SIZE = Regex("/\\d+x\\d+(bb)?\\.(jpg|png|webp)\\z", RegexOption.IGNORE_CASE)
        val EMPTY_ARTIST = Regex("/artist//")

        val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String?): JsonObject? = try {
            body?.let { json.parseToJsonElement(it) as? JsonObject }
        } catch (e: IllegalArgumentException) {
            null
        }

        /** `URLSearchParams` serialised: form encoding, a space as `+`. */
        fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

        /** Is [found] (an artist's name as a catalogue has it) the song's artist? Either contains the other, folded. */
        fun sameArtist(found: String?, artist: String): Boolean {
            val a = found?.let(ArtText::fold).orEmpty()
            val b = ArtText.fold(ArtText.primary(artist))
            return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
        }

        /** 2: the same name; 1: one that starts the same (a fallback); 0: another. */
        fun nameMatch(found: String?, want: String, bare: (String) -> String): Int {
            if (found == null) return 0
            val n = ArtText.fold(bare(found))
            val w = ArtText.fold(bare(want))
            return when {
                n == w -> 2
                n.isNotEmpty() && (n.startsWith(w) || w.startsWith(n)) -> 1
                else -> 0
            }
        }

        /** An https address the record's page may show (its CSP: Deezer's two CDNs, Apple's mzstatic), else null. */
        fun allowed(url: String?): String? = url?.takeIf { isAllowedCover(it) }

        /**
         * worker art.ts `artistSearch`: Deezer's artist search for [artist], the first credited name ([ArtText.primary]) as free
         * text. Deezer's fielded form (`artist:"…"`) leaves the artist out of its own answer (28 Oct 2026: Daft Punk, Björk,
         * Nirvana, The Cure and Nina Simone came back without themselves).
         */
        fun artistSearch(artist: String): String =
            "$DEEZER/search/artist?q=${ArtText.encodeUriComponent(ArtText.primary(artist))}&limit=10"

        /**
         * worker art.ts `pickArtist`: the picture of the artist named [artist] among Deezer's [hits]. The artist is the hit of that
         * name ([ArtText.fold] on both sides: accents, case and punctuation don't count; "The" does) with the most fans (`nb_fan`,
         * a JSON number above 0, else 0; a tie, the one listed first): a common name has small namesakes listed before the real
         * one ("Queen": one of 21 fans first, the band's 12.8M sixth). Their picture, unless it is Deezer's placeholder for an
         * artist with none ([EMPTY_ARTIST]) or not a cover this app takes ([allowed]: https, Deezer's or Apple's hosts): then none,
         * never a namesake's. No hit of that name: none, never a near one ("Nirvana (UK)", "Cure"). A wrong face is worse than
         * initials.
         */
        fun pickArtist(hits: List<JsonObject>, artist: String): String? {
            val want = ArtText.fold(ArtText.primary(artist))
            var best: JsonObject? = null
            var most = -1.0
            for (a in hits) {
                val name = a.str("name") ?: continue
                if (ArtText.fold(name) != want) continue
                val n = (a["nb_fan"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                val fans = if (n != null && n.isFinite() && n > 0) n else 0.0
                if (fans > most) {
                    best = a
                    most = fans
                }
            }
            val url = allowed(best?.str("picture_big")) ?: return null
            return url.takeIf { !EMPTY_ARTIST.containsMatchIn(it) }
        }

        fun JsonElement?.truthy(): Boolean = when (this) {
            null, JsonNull -> false
            is JsonPrimitive -> if (isString) content.isNotEmpty() else content != "false" && content.toDoubleOrNull() != 0.0
            else -> true
        }

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }
}

/** The image hosts the record's page allows (embed.html's `img-src`): Deezer's CDNs and Apple's mzstatic, over https. */
fun isAllowedCover(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: java.net.URISyntaxException) {
        return false
    }
    if (uri.scheme != "https" || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "cdn-images.dzcdn.net" || host == "e-cdns-images.dzcdn.net" || host.endsWith(".mzstatic.com")
}
