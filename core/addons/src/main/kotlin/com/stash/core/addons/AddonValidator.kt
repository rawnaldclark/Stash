package com.stash.core.addons

import com.stash.addons.contract.AccountCount
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Album
import com.stash.addons.contract.AlbumSummary
import com.stash.addons.contract.Artist
import com.stash.addons.contract.ArtistAlbum
import com.stash.addons.contract.ArtistSummary
import com.stash.addons.contract.Artwork
import com.stash.addons.contract.Auth
import com.stash.addons.contract.Bundle
import com.stash.addons.contract.CatalogGenre
import com.stash.addons.contract.CatalogGenres
import com.stash.addons.contract.CatalogInfo
import com.stash.addons.contract.CatalogPage
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Container
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.ErrorInfo
import com.stash.addons.contract.Limits
import com.stash.addons.contract.LyricLine
import com.stash.addons.contract.LyricWord
import com.stash.addons.contract.Lyrics
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.PhoneSpec
import com.stash.addons.contract.Quota
import com.stash.addons.contract.RESERVED_PARAMS
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.ResolveResponse
import com.stash.addons.contract.SearchResponse
import com.stash.addons.contract.SearchTop
import com.stash.addons.contract.Setting
import com.stash.addons.contract.SettingOption
import com.stash.addons.contract.SettingType
import com.stash.addons.contract.SignIn
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.addons.contract.TokenPlacement
import com.stash.addons.contract.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A message that breaks Stash Add-ons v1 (SPEC.md 1.1). [path] is a JSON Pointer to the offending
 * value, "" for the message itself, following the JSON Schema convention the spec's fixtures use: a
 * missing required field is reported at the object that lacks it.
 */
class AddonContractException(val path: String, val reason: String) :
    Exception("${path.ifEmpty { "(message)" }}: $reason")

/**
 * The host's check of every add-on answer, ported from the web player's
 * `stash-web/player/src/lib/sources/contract.ts` and grown to v1 (stash-addon-spec `schema/`).
 *
 * **Reject, don't repair.** Anything that is not exactly the documented shape throws
 * [AddonContractException] and the whole message is dropped. The only changes made to a valid
 * message are the two SPEC.md 1.1 allows: unsafe characters (controls, bidi, zero-width) become a
 * space with whitespace collapsed, and text is cut at its cap. Counts over a cap, URLs, numbers and
 * enums out of range are rejections, never truncations.
 *
 * The answer is built from the checked JSON by hand, never decoded with kotlinx.serialization, so
 * nothing is coerced on the way in.
 */
object AddonValidator {

    /**
     * [allowLocalHttp]: accept `http:` and `https:` on 127.0.0.1, localhost and [::1] (SPEC.md 1.1,
     * development only: debug builds and tests).
     *
     * [publicOnly]: the base rules for an add-on Stash doesn't vouch for (add-ons plan phase 4 §2.6,
     * review N7). A URL's host must be a public DNS name ([isPublicHost]), so neither a link the
     * user added nor any URL such an add-on hands back (stream, download, artwork, icon, a redirect
     * hop) can point the phone at the home network: `https://192.168.1.1/…` is forgery against a
     * router through a plain GET. The client pairs it with [PublicOnlyDns] for names that resolve
     * there. Off for the signed config's defaults, which are the owner's own choices.
     */
    data class Options(val allowLocalHttp: Boolean = false, val publicOnly: Boolean = false)

    private val DEFAULT = Options()

    // ---------------------------------------------------------------------------------------------- text and URL rules

    private val UNSAFE_RANGES = listOf(
        0x00..0x1f, 0x7f..0x9f, 0x61c..0x61c, 0x200b..0x200f, 0x2028..0x202e, 0x2060..0x2069, 0xfeff..0xfeff,
    )
    // Unicode white space without the inline U flag (UNICODE_CHARACTER_CLASS): Android's ICU regex refuses it (a PatternSyntaxException in
    // this object's initializer failed every add-on call on a phone). `\s`, U+0085 and the separators
    // (`\p{Z}`: NBSP, the em and en spaces, U+2028/9) read the same on the JVM and on ICU.
    private val WHITESPACE = Regex("[\\s\\u0085\\p{Z}]+")
    private val INLINE_SPACE = Regex("[ \\t]+")
    private val THREE_NEWLINES = Regex("\\n{3,}")

    private fun isUnsafe(cp: Int) = UNSAFE_RANGES.any { cp in it }

    private fun stripUnsafe(s: String, replacement: String, keepNewline: Boolean): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when {
                keepNewline && cp == 0x0a -> out.append('\n')
                isUnsafe(cp) -> out.append(replacement)
                else -> out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** SPEC.md 1.1 rules 1 and 2 for a one-line text field. */
    fun cleanText(s: String, max: Int): String = cut(stripUnsafe(s, " ", false).replace(WHITESPACE, " ").trim(), max)

    /** As [cleanText] for plain lyrics: line breaks are kept, at most one blank line in a row. */
    fun cleanMultiline(s: String, max: Int): String {
        val unified = s.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
        val lines = stripUnsafe(unified, "", true).split('\n').map { it.replace(INLINE_SPACE, " ").trim() }
        return cut(lines.joinToString("\n").replace(THREE_NEWLINES, "\n\n").trim(), max)
    }

    /** Cuts [s] at [max] code points, never splitting a surrogate pair. */
    private fun cut(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }

    private const val URL_TAIL = "([/?#][^\\u0000-\\u0020\\u007f-\\u009f\\\\]*)?"
    private val HTTPS_URL = Regex("^https://([A-Za-z0-9.\\-]+)(:[0-9]{1,5})?$URL_TAIL$")
    private val LOCAL_URL = Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1])(:[0-9]{1,5})?$URL_TAIL$")

    /**
     * The URL rule (SPEC.md 1.1): https with a host, no credentials, whitespace, controls or
     * backslashes. With [Options.publicOnly] the host must also be a public DNS name
     * ([isPublicHost]). With [Options.allowLocalHttp], http and https on 127.0.0.1, localhost and
     * [::1] pass too (debug builds: an add-on under `wrangler dev` reached over `adb reverse`).
     */
    fun isAllowedUrl(u: String, options: Options = DEFAULT, max: Int = Limits.URL): Boolean {
        if (u.isEmpty() || u.length > max) return false
        if (options.allowLocalHttp && LOCAL_URL.matches(u)) return true
        val host = HTTPS_URL.matchEntire(u)?.groupValues?.get(1) ?: return false
        return !options.publicOnly || isPublicHost(host)
    }

    /**
     * True when [u] fails the URL rule only on the base rules' host check: an address on the
     * user's own network (an IP literal, `localhost`, a single-label or local name). For a refusal
     * that says so in plain words.
     */
    fun isLocalAddressUrl(u: String, options: Options = DEFAULT): Boolean =
        options.publicOnly && !isAllowedUrl(u, options) && isAllowedUrl(u, options.copy(publicOnly = false))

    /** Names that only ever mean the user's own network: RFC 6761, RFC 8375, ICANN's `.internal`, home routers' `.lan`. */
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa", ".lan")

    /**
     * The base rules' host check (add-ons plan phase 4 §2.6): a DNS name with a dot. Refused: IPv4
     * and IPv6 literals (and any spelling a resolver might read as an address: a numeric or `0x`
     * last label), `localhost` and `*.localhost`, single-label names, and `.local`, `.internal`,
     * `.home.arpa` and `.lan`. A public name that resolves to a private address is
     * [PublicOnlyDns]'s to refuse.
     */
    fun isPublicHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h.startsWith("[") || ':' in h) return false
        val labels = h.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        val last = labels.last()
        if (last.all { it.isDigit() } || last.startsWith("0x")) return false
        if (h == "localhost" || h == "home.arpa" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return false
        return HOSTNAME.matches(h)
    }

    // ---------------------------------------------------------------------------------------------- JSON helpers

    private fun fail(path: String, reason: String): Nothing = throw AddonContractException(path, reason)

    private fun child(path: String, key: String) = "$path/" + key.replace("~", "~0").replace("/", "~1")

    private fun obj(v: JsonElement?, path: String): JsonObject = v as? JsonObject ?: fail(path, "expected an object")

    /** Whether [key] carries a value: absent and `null` both mean "not given" (SPEC.md 1). */
    private fun JsonObject.given(key: String) = this[key].let { it != null && it !is JsonNull }

    private fun requireKeys(o: JsonObject, path: String, vararg keys: String) {
        for (k in keys) if (k !in o) fail(path, "$k is required")
    }

    private fun string(o: JsonObject, key: String, path: String): String {
        val v = o[key]
        if (v !is JsonPrimitive || !v.isString) fail(child(path, key), "expected a string")
        return v.content
    }

    private fun reqText(o: JsonObject, key: String, path: String, max: Int): String {
        if (!o.given(key)) fail(if (key in o) child(path, key) else path, if (key in o) "expected a string" else "$key is required")
        val t = cleanText(string(o, key, path), max)
        if (t.isEmpty()) fail(child(path, key), "must not be empty")
        return t
    }

    private fun optText(o: JsonObject, key: String, path: String, max: Int): String? {
        if (!o.given(key)) return null
        return cleanText(string(o, key, path), max).ifEmpty { null }
    }

    /** A JSON number that is a whole number, or null when [v] is not one. */
    private fun wholeNumber(v: JsonElement?): Long? {
        if (v !is JsonPrimitive || v.isString) return null
        val d = v.content.toDoubleOrNull() ?: return null
        if (!d.isFinite() || d != Math.floor(d) || kotlin.math.abs(d) > 9.0e15) return null
        return d.toLong()
    }

    private fun optLong(o: JsonObject, key: String, path: String, min: Long, max: Long): Long? {
        if (!o.given(key)) return null
        val n = wholeNumber(o[key]) ?: fail(child(path, key), "expected a whole number")
        if (n < min || n > max) fail(child(path, key), "expected $min to $max")
        return n
    }

    private fun optInt(o: JsonObject, key: String, path: String, min: Int, max: Int): Int? =
        optLong(o, key, path, min.toLong(), max.toLong())?.toInt()

    private fun reqLong(o: JsonObject, key: String, path: String, min: Long, max: Long): Long {
        if (key !in o) fail(path, "$key is required")
        return optLong(o, key, path, min, max) ?: fail(child(path, key), "expected a whole number")
    }

    private fun optDouble(o: JsonObject, key: String, path: String): Double? {
        if (!o.given(key)) return null
        val v = o[key]
        if (v !is JsonPrimitive || v.isString) fail(child(path, key), "expected a number")
        return v.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail(child(path, key), "expected a number")
    }

    private fun optBool(o: JsonObject, key: String, path: String): Boolean? {
        if (!o.given(key)) return null
        val v = o[key]
        if (v !is JsonPrimitive || v.isString) fail(child(path, key), "expected a boolean")
        return when (v.content) {
            "true" -> true
            "false" -> false
            else -> fail(child(path, key), "expected a boolean")
        }
    }

    private fun reqBool(o: JsonObject, key: String, path: String): Boolean {
        if (key !in o) fail(path, "$key is required")
        return optBool(o, key, path) ?: fail(child(path, key), "expected a boolean")
    }

    private fun reqUrl(o: JsonObject, key: String, path: String, options: Options, max: Int = Limits.URL): String {
        if (key !in o) fail(path, "$key is required")
        return optUrl(o, key, path, options, max) ?: fail(child(path, key), "expected a URL")
    }

    private fun optUrl(o: JsonObject, key: String, path: String, options: Options, max: Int = Limits.URL): String? {
        if (!o.given(key)) return null
        val u = string(o, key, path)
        if (!isAllowedUrl(u, options, max)) {
            fail(
                child(path, key),
                if (isLocalAddressUrl(u, options)) AddonEclipse.PRIVATE_ADDRESS
                else "URL must be https, with a host and no credentials, at most $max characters",
            )
        }
        return u
    }

    private fun arr(v: JsonElement?, path: String, max: Int, min: Int = 0): JsonArray {
        val a = v as? JsonArray ?: fail(path, "expected an array")
        if (a.size > max) fail(path, "too many entries (max $max)")
        if (a.size < min) fail(path, "too few entries (min $min)")
        return a
    }

    private fun optArr(o: JsonObject, key: String, path: String, max: Int, min: Int = 0): JsonArray? =
        if (o.given(key)) arr(o[key], child(path, key), max, min) else null

    private fun <E : Enum<E>> enumOf(value: String?, path: String, wire: (String) -> E?): E =
        value?.let(wire) ?: fail(path, "unknown value")

    private fun optEnumString(o: JsonObject, key: String, path: String): String? =
        if (o.given(key)) string(o, key, path) else null

    private fun distinct(values: List<String>, path: String, what: String) {
        if (values.toSet().size != values.size) fail(path, "duplicate $what")
    }

    // ---------------------------------------------------------------------------------------------- shared shapes

    private val ISRC = Regex("^[A-Za-z0-9]{12}$")

    fun artwork(v: JsonElement?, path: String, options: Options = DEFAULT): Artwork {
        val o = obj(v, path)
        requireKeys(o, path, "url")
        return Artwork(
            url = reqUrl(o, "url", path, options),
            width = optInt(o, "width", path, 1, 20_000),
            height = optInt(o, "height", path, 1, 20_000),
        )
    }

    private fun artworkList(o: JsonObject, path: String, options: Options): List<Artwork>? =
        optArr(o, "artwork", path, Limits.ARTWORK)?.mapIndexed { i, a -> artwork(a, "${child(path, "artwork")}/$i", options) }

    fun track(v: JsonElement?, path: String, options: Options = DEFAULT): Track {
        val o = obj(v, path)
        requireKeys(o, path, "id", "title", "artist")
        val isrc = if (o.given("isrc")) {
            val s = string(o, "isrc", path)
            if (!ISRC.matches(s)) fail(child(path, "isrc"), "expected 12 letters or digits")
            s.uppercase()
        } else {
            null
        }
        return Track(
            id = reqText(o, "id", path, Limits.ID),
            title = reqText(o, "title", path, Limits.TITLE),
            artist = reqText(o, "artist", path, Limits.TITLE),
            album = optText(o, "album", path, Limits.TITLE),
            albumId = optText(o, "albumId", path, Limits.ID),
            artistId = optText(o, "artistId", path, Limits.ID),
            durationMs = optLong(o, "durationMs", path, 0, MAX_DURATION_MS),
            isrc = isrc,
            explicit = optBool(o, "explicit", path),
            artwork = artworkList(o, path, options),
            maxTier = optEnumString(o, "maxTier", path)?.let { enumOf(it, child(path, "maxTier"), Tier::fromWire) },
        )
    }

    private fun tracks(v: JsonElement?, path: String, options: Options): List<Track> =
        arr(v, path, Limits.TRACKS).mapIndexed { i, t -> track(t, "$path/$i", options) }

    fun artistSummary(v: JsonElement?, path: String, options: Options = DEFAULT): ArtistSummary {
        val o = obj(v, path)
        requireKeys(o, path, "id", "name")
        return ArtistSummary(reqText(o, "id", path, Limits.ID), reqText(o, "name", path, Limits.TITLE), artworkList(o, path, options))
    }

    fun albumSummary(v: JsonElement?, path: String, options: Options = DEFAULT): AlbumSummary {
        val o = obj(v, path)
        requireKeys(o, path, "id", "title", "artist")
        return AlbumSummary(
            id = reqText(o, "id", path, Limits.ID),
            title = reqText(o, "title", path, Limits.TITLE),
            artist = reqText(o, "artist", path, Limits.TITLE),
            year = optInt(o, "year", path, 1000, 3000),
            artwork = artworkList(o, path, options),
        )
    }

    // ---------------------------------------------------------------------------------------------- routes

    /** `GET /search` (SPEC.md 4.2). */
    fun searchResponse(v: JsonElement?, options: Options = DEFAULT): SearchResponse {
        val o = obj(v, "")
        requireKeys(o, "", "tracks")
        return SearchResponse(
            tracks = tracks(o["tracks"], "/tracks", options),
            artists = optArr(o, "artists", "", Limits.SEARCH_ARTISTS)?.mapIndexed { i, a -> artistSummary(a, "/artists/$i", options) },
            albums = optArr(o, "albums", "", Limits.SEARCH_ALBUMS)?.mapIndexed { i, a -> albumSummary(a, "/albums/$i", options) },
            top = if (o.given("top")) searchTop(o["top"], "/top", options) else null,
        )
    }

    private fun searchTop(v: JsonElement?, path: String, options: Options): SearchTop {
        val o = obj(v, path)
        requireKeys(o, path, "kind", "item")
        val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (kind) {
            "track" -> SearchTop.TrackTop(track(o["item"], "$path/item", options))
            "artist" -> SearchTop.ArtistTop(artistSummary(o["item"], "$path/item", options))
            "album" -> SearchTop.AlbumTop(albumSummary(o["item"], "$path/item", options))
            else -> fail(path, "kind must be track, artist or album")
        }
    }

    /** `GET /resolve` (SPEC.md 4.3). What a host checks against its own query is [checkResolve]. */
    fun resolveResponse(v: JsonElement?, options: Options = DEFAULT): ResolveResponse {
        val o = obj(v, "")
        requireKeys(o, "", "item")
        return ResolveResponse(if (o["item"] is JsonNull) null else track(o["item"], "/item", options))
    }

    /**
     * SPEC.md 4.3 (draft 2), what a host checks: rule 3, a length more than max(8000 ms, 3 %) from
     * the asked one when both are known, and rule 7, an item whose `maxTier` is below the asked
     * `minTier`. An item is never rejected for its ISRC: another release of the same song often
     * carries an ISRC of its own (rule 2).
     */
    fun checkResolve(item: Track, query: ResolveQuery) {
        val asked = query.song.durationMs
        if (asked != null && asked > 0 && item.durationMs != null) {
            val tolerance = maxOf(8_000L, asked * 3 / 100)
            if (kotlin.math.abs(item.durationMs - asked) > tolerance) fail("/item/durationMs", "another recording's length")
        }
        val minTier = query.minTier
        if (minTier != null && item.maxTier != null && item.maxTier < minTier) fail("/item/maxTier", "below the asked minTier")
    }

    private val MIME = Regex("^audio/[A-Za-z0-9_.+-]+(\\s*;[^\\u0000-\\u001f]*)?$")
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$")
    private val FORBIDDEN_HEADER = Regex(
        "^(host|content-length|connection|transfer-encoding|upgrade|te|trailer|range|proxy-.*|sec-.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** `GET /stream/{id}` and `GET /download/{id}` (SPEC.md 5.3), with the consistency rules. */
    fun streamInfo(v: JsonElement?, options: Options = DEFAULT): StreamInfo {
        val o = obj(v, "")
        requireKeys(o, "", "url", "manifest", "tier", "cors")
        val url = reqUrl(o, "url", "", options, Limits.STREAM_URL)
        val delivery = enumOf(string(o, "manifest", ""), "/manifest", Delivery::fromWire)
        val tier = enumOf(string(o, "tier", ""), "/tier", Tier::fromWire)
        val cors = reqBool(o, "cors", "")
        val codec = optEnumString(o, "codec", "")?.let { w -> enumOf(w, "/codec") { s -> CODECS[s] } }
        val container = optEnumString(o, "container", "")?.let { w -> enumOf(w, "/container") { s -> CONTAINERS[s] } }
        val mime = if (o.given("mime")) {
            val m = string(o, "mime", "")
            if (m.length > 200 || !MIME.matches(m)) fail("/mime", "expected an audio/* media type")
            m
        } else {
            null
        }
        val bitrate = optInt(o, "bitrate", "", 1, 100_000_000)
        val sampleRate = optInt(o, "sampleRate", "", 1, 1_000_000)
        val bitDepth = optInt(o, "bitDepth", "", 1, 64)
        val headers = if (o.given("headers")) headers(o["headers"], "/headers") else null
        val info = StreamInfo(
            url = url,
            manifest = delivery,
            tier = tier,
            cors = cors,
            codec = codec,
            container = container,
            mime = mime,
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            durationMs = optLong(o, "durationMs", "", 1, MAX_DURATION_MS),
            expiresAt = optLong(o, "expiresAt", "", 1, MAX_UNIX_SECONDS),
            quality = optText(o, "quality", "", 40),
            encrypted = optBool(o, "encrypted", ""),
            headers = headers,
            download = optUrl(o, "download", "", options, Limits.STREAM_URL),
            quota = if (o.given("quota")) quota(o["quota"], "/quota") else null,
        )
        checkTierConsistency(info)
        return info
    }

    /** SPEC.md 5.3 "Consistency". */
    private fun checkTierConsistency(s: StreamInfo) {
        if (s.codec != null && s.codec.lossless != s.tier.isLossless) fail("/codec", "codec does not match tier ${s.tier.wire}")
        when (s.tier) {
            Tier.LOSSLESS -> {
                if (s.bitDepth != null && s.bitDepth > 16) fail("/bitDepth", "lossless is 16-bit or less")
                if (s.sampleRate != null && s.sampleRate > 48_000) fail("/sampleRate", "lossless is 48 kHz or less")
            }
            Tier.HIRES -> if (s.bitDepth != null && s.sampleRate != null && s.bitDepth < 24 && s.sampleRate <= 48_000) {
                fail("", "hires needs 24-bit or more than 48 kHz")
            }
            Tier.HIGH -> if (s.bitrate != null && s.bitrate < 256_000) fail("/bitrate", "high is 256 kbps or more")
            Tier.LOW -> if (s.bitrate != null && s.bitrate >= 256_000) fail("/bitrate", "low is below 256 kbps")
        }
    }

    private fun headers(v: JsonElement?, path: String): Map<String, String> {
        val o = obj(v, path)
        if (o.size > Limits.HEADERS) fail(path, "too many headers (max ${Limits.HEADERS})")
        val out = LinkedHashMap<String, String>()
        for ((name, value) in o) {
            if (!HEADER_NAME.matches(name) || FORBIDDEN_HEADER.matches(name)) fail(path, "header $name is not allowed")
            if (value !is JsonPrimitive || !value.isString) fail(child(path, name), "expected a string")
            val s = value.content
            if (s.length > 1024 || '\r' in s || '\n' in s) fail(child(path, name), "header value must be one line of at most 1024")
            out[name] = s
        }
        return out
    }

    /** `GET /quota` (SPEC.md 4.8). [limit] must be present; null means no limit. */
    fun quota(v: JsonElement?, path: String = ""): Quota {
        val o = obj(v, path)
        requireKeys(o, path, "used", "limit", "resetsAt")
        return Quota(
            used = reqLong(o, "used", path, 0, Int.MAX_VALUE.toLong()).toInt(),
            limit = optInt(o, "limit", path, 1, Int.MAX_VALUE),
            resetsAt = reqLong(o, "resetsAt", path, 1, MAX_UNIX_SECONDS),
            label = optText(o, "label", path, Limits.SHORT_TEXT),
        )
    }

    /** `GET /lyrics` (SPEC.md 4.6): at least one of a non-empty `synced` or `plain`. */
    fun lyrics(v: JsonElement?): Lyrics {
        val o = obj(v, "")
        val synced = optArr(o, "synced", "", Limits.LYRIC_LINES)?.mapIndexed { i, l -> lyricLine(l, "/synced/$i") }
        val plain = if (o.given("plain")) cleanMultiline(string(o, "plain", ""), LYRIC_PLAIN) else null
        val lines = synced?.takeIf { it.isNotEmpty() }
        val text = plain?.takeIf { it.isNotEmpty() }
        if (lines == null && text == null) fail("", "needs synced lines or plain text")
        return Lyrics(synced = lines, plain = text)
    }

    private fun lyricLine(v: JsonElement?, path: String): LyricLine {
        val o = obj(v, path)
        requireKeys(o, path, "startMs", "text")
        return LyricLine(
            startMs = reqLong(o, "startMs", path, 0, MAX_DURATION_MS),
            endMs = optLong(o, "endMs", path, 0, MAX_DURATION_MS),
            text = cleanText(string(o, "text", path), LYRIC_LINE),
            words = optArr(o, "words", path, Limits.LYRIC_WORDS)?.mapIndexed { i, w -> lyricWord(w, "${child(path, "words")}/$i") },
        )
    }

    private fun lyricWord(v: JsonElement?, path: String): LyricWord {
        val o = obj(v, path)
        requireKeys(o, path, "startMs", "text")
        return LyricWord(
            startMs = reqLong(o, "startMs", path, 0, MAX_DURATION_MS),
            endMs = optLong(o, "endMs", path, 0, MAX_DURATION_MS),
            text = cleanText(string(o, "text", path), LYRIC_WORD),
        )
    }

    /** `GET /album/{id}`. */
    fun album(v: JsonElement?, options: Options = DEFAULT): Album {
        val o = obj(v, "")
        requireKeys(o, "", "id", "title", "artist", "tracks")
        return Album(
            id = reqText(o, "id", "", Limits.ID),
            title = reqText(o, "title", "", Limits.TITLE),
            artist = reqText(o, "artist", "", Limits.TITLE),
            year = optInt(o, "year", "", 1000, 3000),
            artwork = artworkList(o, "", options),
            tracks = tracks(o["tracks"], "/tracks", options),
        )
    }

    /** `GET /artist/{id}`. */
    fun artist(v: JsonElement?, options: Options = DEFAULT): Artist {
        val o = obj(v, "")
        requireKeys(o, "", "id", "name", "topTracks", "albums")
        return Artist(
            id = reqText(o, "id", "", Limits.ID),
            name = reqText(o, "name", "", Limits.TITLE),
            artwork = artworkList(o, "", options),
            topTracks = tracks(o["topTracks"], "/topTracks", options),
            albums = arr(o["albums"], "/albums", Limits.ARTIST_ALBUMS).mapIndexed { i, a ->
                val p = "/albums/$i"
                val ao = obj(a, p)
                requireKeys(ao, p, "id", "title")
                ArtistAlbum(
                    id = reqText(ao, "id", p, Limits.ID),
                    title = reqText(ao, "title", p, Limits.TITLE),
                    year = optInt(ao, "year", p, 1000, 3000),
                    artwork = artworkList(ao, p, options),
                    trackCount = optInt(ao, "trackCount", p, 0, 10_000),
                )
            },
            listenersText = optText(o, "listenersText", "", Limits.SHORT_TEXT),
            listeners = optLong(o, "listeners", "", 0, 1_000_000_000_000),
        )
    }

    /** `GET /catalog/{id}`: [CatalogPage.next] is required, an offset or null. */
    fun catalogPage(v: JsonElement?, options: Options = DEFAULT): CatalogPage {
        val o = obj(v, "")
        requireKeys(o, "", "id", "title", "items", "next")
        return CatalogPage(
            id = reqText(o, "id", "", Limits.ID),
            title = reqText(o, "title", "", Limits.SHORT_TEXT),
            items = arr(o["items"], "/items", Limits.CATALOG_ITEMS).mapIndexed { i, a -> albumSummary(a, "/items/$i", options) },
            next = optInt(o, "next", "", 0, 1_000_000),
        )
    }

    /** `GET /catalog-genres`. */
    fun catalogGenres(v: JsonElement?): CatalogGenres {
        val o = obj(v, "")
        requireKeys(o, "", "genres")
        val genres = arr(o["genres"], "/genres", Limits.GENRES).mapIndexed { i, g ->
            val p = "/genres/$i"
            val go = obj(g, p)
            requireKeys(go, p, "id", "name")
            CatalogGenre(reqText(go, "id", p, GENRE_ID), reqText(go, "name", p, Limits.SHORT_TEXT))
        }
        distinct(genres.map { it.id }, "/genres", "genre id")
        return CatalogGenres(genres)
    }

    private val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,39}$")

    /**
     * The body of a non-2xx answer (SPEC.md 7). A host that gets a body which fails this reads the
     * answer by its HTTP status instead ([com.stash.addons.contract.ErrorCode.of]).
     */
    fun errorInfo(v: JsonElement?): ErrorInfo {
        val root = obj(v, "")
        requireKeys(root, "", "error")
        val o = obj(root["error"], "/error")
        requireKeys(o, "/error", "code", "message")
        val code = string(o, "code", "/error")
        if (!ERROR_CODE.matches(code)) fail("/error/code", "expected a lowercase code")
        val retryAfter = optInt(o, "retryAfter", "/error", 1, 86_400)
        if (retryAfter == null && (code == "rate_limited" || code == "quota_exceeded")) fail("/error", "retryAfter is required with $code")
        return ErrorInfo(
            code = code,
            message = cleanText(string(o, "message", "/error"), Limits.MESSAGE),
            retryAfter = retryAfter,
            quota = if (o.given("quota")) quota(o["quota"], "/error/quota") else null,
        )
    }

    // ---------------------------------------------------------------------------------------------- manifest

    private val MANIFEST_ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
    private val RESOURCE_NAME = Regex("^[a-z][a-z0-9._-]{0,31}$")
    private val LIST_VALUE = Regex("^[a-z][a-z0-9_-]{0,15}$")
    private val SETTING_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$")
    private val BUNDLE_PATH = Regex("^[A-Za-z0-9._-]{1,64}\\.js$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private const val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    private val HOSTNAME = Regex("^$LABEL(?:\\.$LABEL)+$")
    private val COOKIE_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$")

    /** [readManifest]'s v1 manifest, whatever the dialect. */
    fun anyManifest(v: JsonElement?, options: Options = DEFAULT): Manifest = readManifest(v, options).manifest

    /**
     * A manifest of a contract this host reads (SPEC.md 10): v1 itself, v0 read as v1 through
     * [AddonV0], and an Eclipse-family manifest (no `stash`) through [AddonEclipse] (add-ons plan
     * phase 4 §2.3). Anything else is refused: a newer `stash` ("needs a newer Stash"), or no
     * `stash` and nothing Eclipse-shaped ("not a Stash add-on").
     */
    fun readManifest(v: JsonElement?, options: Options = DEFAULT): AddonManifest {
        val raw = (v as? JsonObject)?.get("stash")
        val stash = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content
        return when {
            stash == "0" -> AddonManifest(AddonV0.manifest(v, options), AddonDialect.V0)
            stash == "1" -> AddonManifest(manifest(v, options), AddonDialect.V1)
            (raw == null || raw is JsonNull) && AddonEclipse.isEclipse(v) -> AddonEclipse.manifest(v, options)
            raw == null || raw is JsonNull -> fail("/stash", NOT_AN_ADDON)
            else -> fail("/stash", NEEDS_NEWER_STASH)
        }
    }

    /** `GET /manifest.json` (SPEC.md 2), v1 only: v0 and Eclipse-family manifests need their adapters first (SPEC.md 10). */
    fun manifest(v: JsonElement?, options: Options = DEFAULT): Manifest {
        val o = obj(v, "")
        requireKeys(o, "", "stash", "id", "name", "version", "kind", "resources")
        if (wholeNumber(o["stash"]) != 1L) fail("/stash", "must be 1")
        val id = string(o, "id", "")
        if (!MANIFEST_ID.matches(id)) fail("/id", "expected a lowercase id (a-z, 0-9, '.', '-'; up to 64)")
        val kind = when (string(o, "kind", "")) {
            "server" -> AddonKind.SERVER
            "phone" -> AddonKind.PHONE
            "frame" -> AddonKind.FRAME
            else -> fail("/kind", "must be server, phone or frame")
        }
        val resources = arr(o["resources"], "/resources", 32).mapIndexed { i, r ->
            if (r !is JsonPrimitive || !r.isString || !RESOURCE_NAME.matches(r.content)) fail("/resources/$i", "expected a resource name")
            r.content
        }
        distinct(resources, "/resources", "resource")
        val manifest = Manifest(
            stash = 1,
            id = id,
            name = reqText(o, "name", "", Limits.SHORT_TEXT),
            version = reqText(o, "version", "", Limits.VERSION),
            kind = kind,
            description = optText(o, "description", "", Limits.DESCRIPTION),
            icon = optUrl(o, "icon", "", options),
            resources = resources,
            stream = if (o.given("stream")) streamCapabilities(o["stream"], "/stream") else null,
            catalogs = optArr(o, "catalogs", "", Limits.CATALOGS)?.mapIndexed { i, c -> catalogInfo(c, "/catalogs/$i") }
                ?.also { list -> distinct(list.map { it.id }, "/catalogs", "catalog id") },
            settings = optArr(o, "settings", "", Limits.SETTINGS)?.mapIndexed { i, s -> setting(s, "/settings/$i") }
                ?.also { list -> distinct(list.map { it.key }, "/settings", "setting key") },
            auth = if (o.given("auth")) auth(o["auth"], "/auth") else null,
            frame = optUrl(o, "frame", "", options),
            phone = if (o.given("phone")) phone(o["phone"], "/phone", options) else null,
        )
        // The schema's conditionals (manifest.schema.json allOf), after every field is checked.
        val listed = resources.toSet()
        if (("stream" in listed || "download" in listed) && manifest.stream == null) fail("", "stream is required with stream or download")
        if ((kind == AddonKind.FRAME) != (manifest.frame != null)) fail("", "frame is required with kind frame, and only then")
        if ((kind == AddonKind.PHONE) != (manifest.phone != null)) fail("", "phone is required with kind phone, and only then")
        if ("account" in listed && (kind != AddonKind.PHONE || manifest.phone?.signIn == null)) fail("", "account needs a phone add-on that signs in")
        if (manifest.phone?.signIn != null && "account" !in listed) fail("/resources", "a phone add-on that signs in lists account")
        return manifest
    }

    private fun streamCapabilities(v: JsonElement?, path: String): StreamCapabilities {
        val o = obj(v, path)
        requireKeys(o, path, "cors")
        return StreamCapabilities(
            cors = reqBool(o, "cors", path),
            manifests = optArr(o, "manifests", path, 8, min = 1)?.let { listValues(it, child(path, "manifests")) },
            tiers = optArr(o, "tiers", path, 8, min = 1)?.let { listValues(it, child(path, "tiers")) },
            headers = optBool(o, "headers", path),
        )
    }

    private fun listValues(a: JsonArray, path: String): List<String> = a.mapIndexed { i, e ->
        if (e !is JsonPrimitive || !e.isString || !LIST_VALUE.matches(e.content)) fail("$path/$i", "expected a lowercase value")
        e.content
    }.also { distinct(it, path, "value") }

    private fun catalogInfo(v: JsonElement?, path: String): CatalogInfo {
        val o = obj(v, path)
        requireKeys(o, path, "id", "title", "kind")
        if (string(o, "kind", path) != "album") fail(child(path, "kind"), "must be album")
        return CatalogInfo(reqText(o, "id", path, Limits.ID), reqText(o, "title", path, Limits.SHORT_TEXT), "album", optBool(o, "genres", path))
    }

    /** One setting as SPEC.md 2.2 has it, or null when it breaks a rule: the Eclipse reader drops such a setting, not the add-on. */
    internal fun settingOrNull(v: JsonElement?, path: String): Setting? =
        try {
            setting(v, path)
        } catch (e: AddonContractException) {
            null
        }

    private fun setting(v: JsonElement?, path: String): Setting {
        val o = obj(v, path)
        requireKeys(o, path, "key", "type", "label")
        val key = string(o, "key", path)
        if (!SETTING_KEY.matches(key) || key in RESERVED_PARAMS) fail(child(path, "key"), "expected a setting key that is not a reserved parameter")
        val type = when (string(o, "type", path)) {
            "select" -> SettingType.SELECT
            "toggle" -> SettingType.TOGGLE
            "text" -> SettingType.TEXT
            "number" -> SettingType.NUMBER
            else -> fail(child(path, "type"), "must be select, toggle, text or number")
        }
        if (type == SettingType.SELECT && !o.given("options")) fail(path, "a select needs options")
        val options = optArr(o, "options", path, 50, min = 1)?.mapIndexed { i, opt ->
            val p = "${child(path, "options")}/$i"
            val oo = obj(opt, p)
            requireKeys(oo, p, "value", "label")
            val value = string(oo, "value", p)
            if (value.isEmpty() || value.length > 100) fail(child(p, "value"), "expected 1 to 100 characters")
            SettingOption(value, reqText(oo, "label", p, Limits.SHORT_TEXT))
        }?.also { list -> distinct(list.map { it.value }, child(path, "options"), "option value") }
        val default = if (o.given("default")) {
            val d = o["default"] as? JsonPrimitive ?: fail(child(path, "default"), "expected a value")
            val ok = when (type) {
                SettingType.SELECT, SettingType.TEXT -> d.isString && (type != SettingType.TEXT || d.content.length <= 1000)
                SettingType.TOGGLE -> !d.isString && (d.content == "true" || d.content == "false")
                SettingType.NUMBER -> !d.isString && d.content.toDoubleOrNull()?.isFinite() == true
            }
            if (!ok) fail(child(path, "default"), "does not match the setting's type")
            d
        } else {
            null
        }
        val step = optDouble(o, "step", path)
        if (step != null && step <= 0.0) fail(child(path, "step"), "must be above 0")
        return Setting(
            key = key,
            type = type,
            label = reqText(o, "label", path, Limits.SHORT_TEXT),
            help = optText(o, "help", path, Limits.DESCRIPTION),
            default = default,
            options = options,
            maxLength = optInt(o, "maxLength", path, 1, 1000),
            placeholder = optText(o, "placeholder", path, 100),
            min = optDouble(o, "min", path),
            max = optDouble(o, "max", path),
            step = step,
            perNetwork = optBool(o, "perNetwork", path),
        )
    }

    private fun auth(v: JsonElement?, path: String): Auth {
        val o = obj(v, path)
        requireKeys(o, path, "token")
        return Auth(
            when (string(o, "token", path)) {
                "path" -> TokenPlacement.PATH
                "header" -> TokenPlacement.HEADER
                else -> fail(child(path, "token"), "must be path or header")
            },
        )
    }

    private fun phone(v: JsonElement?, path: String, options: Options): PhoneSpec {
        val o = obj(v, path)
        requireKeys(o, path, "bundle", "domains")
        val bp = child(path, "bundle")
        val bundle = obj(o["bundle"], bp)
        requireKeys(bundle, bp, "path", "sha256")
        if (!BUNDLE_PATH.matches(string(bundle, "path", bp))) fail(child(bp, "path"), "expected a .js file name")
        if (!SHA256.matches(string(bundle, "sha256", bp))) fail(child(bp, "sha256"), "expected 64 lowercase hex")
        val dp = child(path, "domains")
        val domains = arr(o["domains"], dp, 32, min = 1).mapIndexed { i, d ->
            val s = (d as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$dp/$i", "expected a host")
            val host = s.removePrefix("*.")
            if (s.length > 253 || host == "localhost" || host.all { it.isDigit() || it == '.' } || !HOSTNAME.matches(host)) {
                fail("$dp/$i", "expected a lowercase hostname or *. and one; no IP address, port or localhost")
            }
            s
        }
        distinct(domains, dp, "domain")
        val spec = PhoneSpec(Bundle(string(bundle, "path", bp), string(bundle, "sha256", bp)), domains, signIn = null)
        val signIn = if (o.given("signIn")) signIn(o["signIn"], child(path, "signIn"), spec, options) else null
        return spec.copy(signIn = signIn)
    }

    private fun signIn(v: JsonElement?, path: String, phone: PhoneSpec, options: Options): SignIn {
        val o = obj(v, path)
        requireKeys(o, path, "title", "url", "domain", "cookieNames", "accounts")
        val url = reqUrl(o, "url", path, options)
        val domain = string(o, "domain", path)
        if (!HOSTNAME.matches(domain)) fail(child(path, "domain"), "expected a lowercase hostname")
        val cp = child(path, "cookieNames")
        val cookies = arr(o["cookieNames"], cp, 10, min = 1).mapIndexed { i, c ->
            val s = (c as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s == null || !COOKIE_NAME.matches(s)) fail("$cp/$i", "expected a cookie name")
            s
        }
        distinct(cookies, cp, "cookie name")
        val accounts = when (string(o, "accounts", path)) {
            "one" -> AccountCount.ONE
            "many" -> AccountCount.MANY
            else -> fail(child(path, "accounts"), "must be one or many")
        }
        // SPEC.md 8.2: the sign-in page's host, and the cookie domain, are ones the add-on may reach.
        val urlHost = url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringBefore(':').lowercase()
        if (!phone.allows(urlHost)) fail(child(path, "url"), "its host is not in domains")
        if (domain !in phone.domains && "*.$domain" !in phone.domains) fail(child(path, "domain"), "not in domains")
        return SignIn(reqText(o, "title", path, Limits.SHORT_TEXT), url, domain, cookies, accounts)
    }

    // ---------------------------------------------------------------------------------------------- caps not in Limits

    /** Why [readManifest] refuses a manifest written for a newer host; a caller tells it apart by this text. */
    const val NEEDS_NEWER_STASH = "This add-on needs a newer Stash"

    /** Why [readManifest] refuses a manifest with no `stash` field and nothing Eclipse-shaped. */
    const val NOT_AN_ADDON = "not a Stash add-on"

    const val MAX_DURATION_MS = 86_400_000L
    const val MAX_UNIX_SECONDS = 4_102_444_800L
    const val LYRIC_LINE = 600
    const val LYRIC_WORD = 100
    const val LYRIC_PLAIN = 100_000
    const val GENRE_ID = 40

    private val CODECS = mapOf(
        "flac" to Codec.FLAC, "alac" to Codec.ALAC, "pcm" to Codec.PCM, "aac" to Codec.AAC, "he-aac" to Codec.HE_AAC,
        "mp3" to Codec.MP3, "opus" to Codec.OPUS, "vorbis" to Codec.VORBIS, "eac3_joc" to Codec.EAC3_JOC,
    )
    private val CONTAINERS = mapOf(
        "flac" to Container.FLAC, "mp3" to Container.MP3, "mp4" to Container.MP4, "fmp4" to Container.FMP4,
        "ogg" to Container.OGG, "webm" to Container.WEBM, "wav" to Container.WAV,
    )
}
