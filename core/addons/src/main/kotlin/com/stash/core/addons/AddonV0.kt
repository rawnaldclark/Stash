package com.stash.core.addons

import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Limits
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stash Sources v0 (the web player's contract, `stash-web/player/docs/sources-v0.md`) read as v1,
 * SPEC.md 10.1. A v0 add-on is checked by v0's own rules, then treated as v1 from then on:
 *
 *  - the manifest: `runtime: "remote"` is `kind: "server"`, `"frame"` is `kind: "frame"` (which an
 *    app host never runs), `stream: { cors }` gains `manifests: ["none"]`, and [Manifest.stash] stays
 *    `0` so the client knows to read its streams through [streamInfo];
 *  - a stream: `manifest: "none"`, a `codec` from a plain `mime`, and a `tier` derived from the
 *    codec and numbers, never over-claimed. v0 ignores `tier`, `minTier` and `manifest`, so the
 *    client checks the derived tier against what it asked and reads an answer out of range as
 *    `quality_unavailable`;
 *  - fractional numbers (v0 allowed them) are rounded.
 *
 * Search, resolve, lyrics, album, artist and catalog answers are the same shapes in v0 and v1.
 */
object AddonV0 {

    private val MANIFEST_ID = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")
    private val V0_RESOURCES = setOf("search", "resolve", "stream", "lyrics", "album", "artist", "catalog")
    private val MIME = Regex("^audio/[A-Za-z0-9_.+-]+(\\s*;[^\\u0000-\\u001f]*)?$")

    private fun fail(path: String, reason: String): Nothing = throw AddonContractException(path, reason)

    private fun obj(v: JsonElement?, path: String): JsonObject = v as? JsonObject ?: fail(path, "expected an object")

    private fun JsonObject.given(key: String) = this[key].let { it != null && it !is JsonNull }

    private fun string(o: JsonObject, key: String, path: String): String {
        val v = o[key]
        if (v !is JsonPrimitive || !v.isString) fail("$path/$key", "expected a string")
        return v.content
    }

    private fun text(o: JsonObject, key: String, max: Int): String {
        if (!o.given(key)) fail("", "$key is required")
        return AddonValidator.cleanText(string(o, key, ""), max).ifEmpty { fail("/$key", "must not be empty") }
    }

    private fun bool(o: JsonObject, key: String, path: String): Boolean? {
        if (!o.given(key)) return null
        val v = o[key] as? JsonPrimitive
        if (v == null || v.isString || (v.content != "true" && v.content != "false")) fail("$path/$key", "expected a boolean")
        return v.content == "true"
    }

    /** A number rounded to a whole one (v0 allowed fractions), within [min]..[max]; null when absent. */
    private fun rounded(o: JsonObject, key: String, min: Long, max: Long): Long? {
        if (!o.given(key)) return null
        val v = o[key] as? JsonPrimitive
        val d = v?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("/$key", "expected a number")
        val n = Math.round(d)
        if (n < min || n > max) fail("/$key", "expected $min to $max")
        return n
    }

    /** A v0 manifest (`stash: 0`), as the v1 [Manifest] a host reads it as (SPEC.md 10.1). */
    fun manifest(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): Manifest {
        val o = obj(v, "")
        for (k in listOf("stash", "id", "name", "version", "runtime", "resources", "stream")) if (k !in o) fail("", "$k is required")
        if ((o["stash"] as? JsonPrimitive)?.content != "0") fail("/stash", "must be 0")
        val id = string(o, "id", "")
        if (!MANIFEST_ID.matches(id)) fail("/id", "expected a lowercase id (a-z, 0-9, '.', '-'; up to 64)")
        val kind = when (string(o, "runtime", "")) {
            "remote" -> AddonKind.SERVER
            "frame" -> AddonKind.FRAME
            else -> fail("/runtime", "must be remote or frame")
        }
        val list = o["resources"] as? JsonArray ?: fail("/resources", "expected an array")
        if (list.size > 32) fail("/resources", "too many entries (max 32)")
        val resources = list.mapIndexed { i, r ->
            if (r !is JsonPrimitive || !r.isString || r.content !in V0_RESOURCES) fail("/resources/$i", "unknown resource")
            r.content
        }
        if (resources.toSet().size != resources.size) fail("/resources", "duplicate resource")
        val stream = obj(o["stream"], "/stream")
        val cors = bool(stream, "cors", "/stream") ?: fail("/stream", "cors is required")
        val frame = if (o.given("frame")) {
            string(o, "frame", "").also { if (!AddonValidator.isAllowedUrl(it, options)) fail("/frame", "URL must be https") }
        } else {
            null
        }
        if ((kind == AddonKind.FRAME) != (frame != null)) fail("", "frame is required with runtime frame, and only then")
        return Manifest(
            stash = 0,
            id = id,
            name = text(o, "name", Limits.SHORT_TEXT),
            version = text(o, "version", Limits.VERSION),
            kind = kind,
            resources = resources,
            stream = StreamCapabilities(cors = cors, manifests = listOf("none")),
            frame = frame,
        )
    }

    /** A v0 StreamInfo as v1's (SPEC.md 10.1): `manifest: "none"`, a codec from a plain mime, a derived tier. */
    fun streamInfo(v: JsonElement?, options: AddonValidator.Options = AddonValidator.Options()): StreamInfo {
        val o = obj(v, "")
        if ("url" !in o) fail("", "url is required")
        if ("cors" !in o) fail("", "cors is required")
        val url = string(o, "url", "")
        if (!AddonValidator.isAllowedUrl(url, options, Limits.STREAM_URL)) fail("/url", "URL must be https, with a host and no credentials")
        val cors = bool(o, "cors", "") ?: fail("/cors", "expected a boolean")
        val mime = if (o.given("mime")) string(o, "mime", "").also { if (it.length > 200 || !MIME.matches(it)) fail("/mime", "expected an audio/* media type") } else null
        val codec = mime?.let(::codecOf)
        val bitrate = rounded(o, "bitrate", 1, 100_000_000)?.toInt()
        val sampleRate = rounded(o, "sampleRate", 1, 1_000_000)?.toInt()
        val bitDepth = rounded(o, "bitDepth", 1, 64)?.toInt()
        val tier = when {
            codec?.lossless == true -> if ((bitDepth ?: 0) >= 24 || (sampleRate ?: 0) > 48_000) Tier.HIRES else Tier.LOSSLESS
            (bitrate ?: 0) >= 256_000 -> Tier.HIGH
            else -> Tier.LOW
        }
        val download = if (o.given("download")) {
            string(o, "download", "").also { if (!AddonValidator.isAllowedUrl(it, options, Limits.STREAM_URL)) fail("/download", "URL must be https") }
        } else {
            null
        }
        return StreamInfo(
            url = url,
            manifest = Delivery.NONE,
            tier = tier,
            cors = cors,
            codec = codec,
            mime = mime,
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            durationMs = rounded(o, "durationMs", 1, 86_400_000),
            expiresAt = rounded(o, "expiresAt", 1, 32_503_680_000),
            download = download,
        )
    }

    /** SPEC.md 10.1: a codec only from a plain mime; anything else stays unknown. */
    internal fun codecOf(mime: String): Codec? {
        val m = mime.lowercase()
        val type = m.substringBefore(';').trim()
        val codecs = Regex("codecs\\s*=\\s*\"?([^\";]+)").find(m)?.groupValues?.get(1)?.trim()
        return when {
            type == "audio/flac" || type == "audio/x-flac" -> Codec.FLAC
            type == "audio/wav" || type == "audio/x-wav" || type == "audio/wave" -> Codec.PCM
            type == "audio/mpeg" || type == "audio/mp3" -> Codec.MP3
            codecs == "opus" -> Codec.OPUS
            codecs?.startsWith("mp4a") == true -> Codec.AAC
            else -> null
        }
    }
}
