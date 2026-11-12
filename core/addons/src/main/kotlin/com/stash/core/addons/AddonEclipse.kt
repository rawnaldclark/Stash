package com.stash.core.addons

import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Artwork
import com.stash.addons.contract.CatalogInfo
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Container
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Limits
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.SearchResponse
import com.stash.addons.contract.SettingType
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * Eclipse-family add-ons (Eclipse Music, BitChord, LastWave: no `stash` field) read as v1,
 * SPEC.md 10.2 as corrected by add-ons plan phase 4 §2.3. The Kotlin twin of the spec checker's
 * `adapters.mjs` (Stash's own MIT code); nothing here comes from BitChord or LastWave.
 *
 * **Tolerant on shapes, strict on what plays.** A search row that can't be read is dropped, not the
 * whole answer; a missing name or version is filled in; ids that are numbers become text. But a
 * stream must have an https URL that passes the URL rule (with the base rules for an add-on the
 * user added), and anything encrypted, DRM'd, "hybrid" or video is not playable.
 *
 * What a host sends such an add-on is the client's ([HttpAddonClient]): only Eclipse's parameters,
 * plus `quality`, never `drm`, `atmos` or a call to `/prewarm`.
 */
object AddonEclipse {

    /** The reason a BitChord-only add-on is refused with (the Add screen's words). */
    const val ONLY_BITCHORD = "This add-on only works in BitChord"

    private val MANIFEST_ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
    private val ISRC = Regex("^[A-Za-z0-9]{12}$")
    private val KBPS = Regex("(\\d+)\\s*kbps", RegexOption.IGNORE_CASE)

    /** The result lists of a search answer other than `tracks`. */
    private val OTHER_RESULTS = listOf("albums", "artists", "playlists")

    private fun fail(path: String, reason: String): Nothing = throw AddonContractException(path, reason)

    private fun JsonObject.given(key: String) = this[key].let { it != null && it !is JsonNull }

    private fun str(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(v: JsonElement?): Double? {
        val p = v as? JsonPrimitive ?: return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** True for a flag that is set: `true`, a non-zero number, or a non-empty string other than "false"/"0". */
    private fun truthy(v: JsonElement?): Boolean {
        val p = v as? JsonPrimitive ?: return v is JsonObject || v is JsonArray
        if (p is JsonNull) return false
        val c = p.content.trim().lowercase()
        return c.isNotEmpty() && c != "false" && c != "0" && c != "null"
    }

    /** A JSON number written as plain digits: `42`, `-7`, `12345678901234567890`. */
    private fun isWholeNumber(s: String): Boolean = s.removePrefix("-").let { d -> d.isNotEmpty() && d.all { it in '0'..'9' } }

    /** 2^53: up to here every whole number is a `Double` exactly. */
    private const val EXACT_DOUBLE = 9_007_199_254_740_992.0

    /**
     * An id the add-on chose: text, or a whole number written as one. A number keeps its own
     * digits: read through a `Double`, an id past 2^53 (17 digits or more) came out rounded, and
     * `/stream/{id}` then asked for another song's id (review-882 N9). A number written another
     * way (`12.0`, `1e3`) is taken only while a `Double` holds it exactly.
     */
    private fun idOf(v: JsonElement?): String? {
        val p = v as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val raw = when {
            p.isString -> p.content
            isWholeNumber(p.content) -> p.content
            else -> p.content.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it == Math.floor(it) && Math.abs(it) < EXACT_DOUBLE }?.toLong()?.toString()
        }
        return raw?.let { AddonValidator.cleanText(it, Limits.ID) }?.takeIf { it.isNotEmpty() }
    }

    private fun text(v: JsonElement?, max: Int): String? = str(v)?.let { AddonValidator.cleanText(it, max) }?.takeIf { it.isNotEmpty() }

    /** The resource names a manifest lists: strings, or Stremio-style `{ name }` objects. */
    private fun resourceNames(o: JsonObject): List<String> = (o["resources"] as? JsonArray)?.mapNotNull { r ->
        when (r) {
            is JsonPrimitive -> r.takeIf { it.isString }?.content
            is JsonObject -> str(r["name"])
            else -> null
        }
    }.orEmpty()

    /**
     * SPEC.md 10: no `stash` field, an `id`, and `resources` that include `search` or `stream` (the
     * checker's `dialectOf`, without its name and version demand: BitChord requires only `id`).
     */
    fun isEclipse(v: JsonElement?): Boolean {
        val o = v as? JsonObject ?: return false
        if (o.given("stash")) return false
        if (idOf(o["id"]) == null) return false
        val res = resourceNames(o)
        return "search" in res || "stream" in res
    }

    // ---------------------------------------------------------------------------------------------- manifest

    /** An Eclipse-family manifest as the v1 [Manifest] a host reads it as, with what v1 has no field for. */
    fun manifest(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): AddonManifest {
        val o = v as? JsonObject ?: fail("", "expected an object")
        if (!isEclipse(o)) fail("/stash", "not a Stash add-on")
        val id = idOf(o["id"])!!.lowercase()
        if (!MANIFEST_ID.matches(id)) fail("/id", "expected an id that fits v1's rule once lowercased")
        // BitChord's attestation: meant to serve only the genuine BitChord, and Stash respects that.
        if (truthy(o["bitchordAuth"])) fail("/bitchordAuth", ONLY_BITCHORD)
        val res = resourceNames(o).toSet()
        val out = LinkedHashSet<String>()
        for (r in listOf("search", "stream", "resolve")) if (r in res) out += r
        val viaIsrc = "isrc" in res && "resolve" !in res
        if (viaIsrc) out += "resolve"
        // Corrected (phase 4 §2.3): the docs tie /album, /artist and /playlist to `catalog`; `types` is display-only.
        if ("catalog" in res) {
            out += "album"
            out += "artist"
        }
        val catalogs = (o["catalogs"] as? JsonArray)?.mapNotNull { c ->
            val co = c as? JsonObject ?: return@mapNotNull null
            if (str(co["type"]) != "album") return@mapNotNull null
            val cid = idOf(co["id"]) ?: return@mapNotNull null
            val title = text(co["name"], Limits.SHORT_TEXT) ?: return@mapNotNull null
            CatalogInfo(cid, title, "album")
        }?.distinctBy { it.id }?.take(Limits.CATALOGS).orEmpty()
        if ("catalog" in res && catalogs.isNotEmpty()) out += "catalog"
        val settings = (o["settings"] as? JsonArray)?.mapIndexedNotNull { i, s -> AddonValidator.settingOrNull(s, "/settings/$i") }
            ?.distinctBy { it.key }?.take(Limits.SETTINGS)
        val quality = settings?.firstOrNull { it.key.equals("quality", ignoreCase = true) && it.type == SettingType.SELECT }
            ?.options?.firstOrNull()?.value
        val manifest = Manifest(
            stash = 1,
            id = id,
            name = text(o["name"], Limits.SHORT_TEXT) ?: id.take(Limits.SHORT_TEXT),
            version = text(o["version"], Limits.VERSION) ?: "",
            kind = AddonKind.SERVER,
            description = text(o["description"], Limits.DESCRIPTION),
            icon = str(o["icon"])?.takeIf { AddonValidator.isAllowedUrl(it, options) },
            resources = out.toList(),
            stream = if ("stream" in out) StreamCapabilities(cors = false, manifests = listOf("none", "dash", "hls")) else null,
            catalogs = catalogs.ifEmpty { null },
            settings = settings?.ifEmpty { null },
        )
        val traits = EclipseTraits(
            resolveViaIsrc = viaIsrc,
            allowDownloads = !(o.given("allowDownloads") && !truthy(o["allowDownloads"])),
            qualityOption = quality,
        )
        return AddonManifest(manifest, AddonDialect.ECLIPSE, traits)
    }

    // ---------------------------------------------------------------------------------------------- tracks

    /** One Eclipse track as v1's [Track], or null when it can't be read (no id, title or artist). */
    fun track(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): Track? {
        val o = v as? JsonObject ?: return null
        val id = idOf(o["id"]) ?: return null
        val title = text(o["title"], Limits.TITLE) ?: return null
        val artist = text(o["artist"], Limits.TITLE) ?: return null
        val durationMs = (num(o["durationMs"])?.roundToLong() ?: num(o["duration"])?.let { (it * 1000).roundToLong() })
            ?.takeIf { it in 1..AddonValidator.MAX_DURATION_MS }
        val art = listOf("artworkURL", "artworkUrl", "albumArtworkURL").firstNotNullOfOrNull { k ->
            str(o[k])?.takeIf { AddonValidator.isAllowedUrl(it, options) }
        }
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = text(o["album"], Limits.TITLE),
            durationMs = durationMs,
            isrc = str(o["isrc"])?.trim()?.takeIf { ISRC.matches(it) }?.uppercase(),
            explicit = (o["explicit"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            },
            artwork = art?.let { listOf(Artwork(it)) },
        )
    }

    /** `GET /search`: `{tracks, albums, artists, playlists}`; only `tracks` is used. Rows that can't be read are dropped. */
    fun searchResponse(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): SearchResponse {
        val o = v as? JsonObject ?: fail("", "expected an object")
        val rows = o["tracks"] as? JsonArray
            // No `tracks`. Beside another result list it is a search that found no songs. On its
            // own (`{"error":"upstream timeout"}`, `{}`) it is no search answer at all: the add-on
            // could not answer, which must never be remembered as "no such song" (review-882 M3).
            ?: if (OTHER_RESULTS.any { o[it] is JsonArray }) return SearchResponse(tracks = emptyList()) else throw AddonRefusalException(ErrorCode.UNAVAILABLE)
        return SearchResponse(tracks = rows.asSequence().mapNotNull { track(it, options) }.distinctBy { it.id }.take(Limits.TRACKS).toList())
    }

    /**
     * `GET /resolve?title=&artist=[&isrc=][&durationMs=]`: `{item}`, or `{item: null}` for a miss.
     * An item that can't be read is a miss too.
     */
    fun resolveResponse(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): Track? {
        val o = v as? JsonObject ?: fail("", "expected an object")
        return track(o["item"], options)
    }

    /**
     * `GET /resolve-isrc?isrc=`: `{trackId}` or `{id}`, or null for no match. The add-on's id for
     * the song the host asked about, so the item is the query's own song.
     */
    fun resolveIsrcResponse(v: JsonElement?, song: Descriptor): Track? {
        if (v == null || v is JsonNull) return null
        val o = v as? JsonObject ?: fail("", "expected an object")
        val id = idOf(o["trackId"]) ?: idOf(o["id"]) ?: return null
        return Track(id = id, title = song.title, artist = song.artist, isrc = song.isrc?.uppercase())
    }

    // ---------------------------------------------------------------------------------------------- streams

    /**
     * `GET /stream/{id}` read as v1's [StreamInfo] (phase 4 §2.3): `codec` first, `format` only
     * when it is a codec word (`format: "dash"` or `"hls"` names the delivery), a derived tier,
     * `cors: false`. A `200 {error}` with no `url` is a miss; encrypted, DRM, "hybrid" and video
     * answers are not playable (both [AddonRefusalException], never a broken answer).
     */
    fun streamInfo(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): StreamInfo {
        val o = v as? JsonObject ?: fail("", "expected an object")
        if (!o.given("url")) {
            if (o.given("error")) throw AddonRefusalException(ErrorCode.NOT_FOUND)
            fail("", "url is required")
        }
        val url = str(o["url"]) ?: fail("/url", "expected a string")
        if (!AddonValidator.isAllowedUrl(url, options, Limits.STREAM_URL)) {
            fail("/url", if (AddonValidator.isLocalAddressUrl(url, options)) PRIVATE_ADDRESS else "URL must be https, with a host and no credentials")
        }
        // v1 defines no keys: LastWave's hybrid CENC plan carries its key in the JSON, and Stash never takes one.
        val encrypted = o.given("encrypted") && truthy(o["encrypted"])
        if (encrypted || (o.given("drm") && truthy(o["drm"])) || truthy(o["hybrid"]) || truthy(o["video"])) {
            throw AddonRefusalException(ErrorCode.QUALITY_UNAVAILABLE)
        }
        val format = str(o["format"])?.trim()?.lowercase()
        val codec = str(o["codec"])?.let(::codecOf) ?: format?.takeIf { it in FORMAT_CODECS }?.let(::codecOf)
        val delivery = when (val m = str(o["manifest"])?.trim()?.lowercase()) {
            null -> if (format == "dash") Delivery.DASH else if (format == "hls") Delivery.HLS else Delivery.NONE
            else -> Delivery.fromWire(m) ?: fail("/manifest", "unknown delivery")
        }
        val bitDepth = num(o["bitDepth"])?.roundToLong()?.takeIf { it in 1..64 }?.toInt()
        val sampleRate = num(o["sampleRate"])?.roundToLong()?.takeIf { it in 1..1_000_000 }?.toInt()
        // Bits per second; kbps when under 10,000 (some add-ons send 320).
        val bitrate = num(o["bitrate"])?.let { if (it < 10_000) it * 1000 else it }?.roundToLong()?.takeIf { it in 1..100_000_000 }?.toInt()
        // Unix seconds; a value in milliseconds is read as such.
        val expiresAt = num(o["expiresAt"])?.let { if (it > 100_000_000_000) it / 1000 else it }?.roundToLong()
            ?.takeIf { it in 1..AddonValidator.MAX_UNIX_SECONDS }
        val quality = text(o["quality"], 40)
        return StreamInfo(
            url = url,
            manifest = delivery,
            tier = deriveTier(codec, bitDepth, sampleRate, bitrate, quality),
            cors = false,
            codec = codec,
            container = str(o["container"])?.let(::containerOf),
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            expiresAt = expiresAt,
            quality = quality,
        )
    }

    /** What the client's log says when a stream URL was on the user's own network. */
    const val PRIVATE_ADDRESS = "refused (private address)"

    private val FORMAT_CODECS = setOf("mp3", "flac", "aac", "m4a")

    /** A codec word as v1's [Codec]; null when unknown. */
    internal fun codecOf(s: String): Codec? {
        val c = s.trim().lowercase()
        return when {
            c == "flac" -> Codec.FLAC
            c == "alac" -> Codec.ALAC
            c == "pcm" || c == "wav" -> Codec.PCM
            c == "he-aac" || c == "heaac" -> Codec.HE_AAC
            c == "aac" || c == "m4a" || c.startsWith("mp4a") -> Codec.AAC
            c == "mp3" -> Codec.MP3
            c == "opus" -> Codec.OPUS
            c == "vorbis" -> Codec.VORBIS
            c == "eac3" || c == "ec-3" || c == "eac3_joc" -> Codec.EAC3_JOC
            else -> null
        }
    }

    private fun containerOf(s: String): Container? = when (s.trim().lowercase()) {
        "flac" -> Container.FLAC
        "mp3" -> Container.MP3
        "mp4", "m4a" -> Container.MP4
        "fmp4" -> Container.FMP4
        "ogg" -> Container.OGG
        "webm" -> Container.WEBM
        "wav" -> Container.WAV
        else -> null
    }

    /**
     * The v1 tier of a stream that does not say (the checker's `deriveTier`), never over-claimed: a
     * lossless codec is `hires` above 16-bit/48 kHz, else `lossless`; then `quality` ("lossless",
     * "N kbps", or BitChord's own `HIGH` / `LOW`); then the bitrate; else `low`.
     */
    fun deriveTier(codec: Codec?, bitDepth: Int?, sampleRate: Int?, bitrate: Int?, quality: String?): Tier {
        if (codec != null && codec.lossless) {
            return if ((bitDepth ?: 0) >= 24 || (sampleRate ?: 0) > 48_000) Tier.HIRES else Tier.LOSSLESS
        }
        tierOfQuality(codec, quality)?.let { return it }
        if (bitrate != null) return if (bitrate >= 256_000) Tier.HIGH else Tier.LOW
        return Tier.LOW
    }

    /** The tier [quality] states, or null when it states none this reader knows. */
    private fun tierOfQuality(codec: Codec?, quality: String?): Tier? {
        val q = quality?.trim() ?: return null
        if (codec == null && q.contains("lossless", ignoreCase = true)) return Tier.LOSSLESS
        // A number too long to be a bit rate (the text is cut at 40 characters, so up to 40 digits) states none.
        KBPS.find(q)?.groupValues?.get(1)?.toIntOrNull()?.let { return if (it >= 256) Tier.HIGH else Tier.LOW }
        if (q.equals("high", ignoreCase = true)) return Tier.HIGH
        if (q.equals("low", ignoreCase = true)) return Tier.LOW
        return null
    }

    /**
     * True when [info]'s tier is only [deriveTier]'s last resort: a stream that is not lossless and
     * states neither a bit rate nor a quality this reader knows (`{"url": …}`, or `{"url": …,
     * "format": "mp3"}`). Its `low` says nothing about its size either way (review-882 J5); what a
     * host makes of such an answer is the host's own rule ([HttpAddonClient.answers]).
     */
    fun tierIsDefault(info: StreamInfo): Boolean =
        info.codec?.lossless != true && info.bitrate == null && tierOfQuality(info.codec, info.quality) == null
}
