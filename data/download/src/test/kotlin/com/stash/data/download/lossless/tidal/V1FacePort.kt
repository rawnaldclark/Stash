package com.stash.data.download.lossless.tidal

import com.stash.data.download.lossless.tidal.TidalWireParityTest.Companion.json
import com.stash.data.download.lossless.tidal.TidalWireParityTest.RelayAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import kotlin.math.roundToLong

/**
 * The v1 face of `stash-addon-tidal` (`src/faces/v1.js` at the phase 2 cut: `toTrack`, `relayAsk`,
 * `streamRange`, `toStreamInfo`, `fromRelay`), ported line for line so the parity test can serve
 * the relay's own answers through it. The face's counting and its `quota` come from the relay's
 * `X-Stash-Lossless` headers here (the Worker reads the same count from D1).
 */
internal object V1FacePort {

    const val MANIFEST = """{"stash":1,"id":"tidal","name":"Tidal","version":"2.0.0","kind":"server",
        "description":"Lossless and Hi-Res from Tidal","resources":["search","resolve","stream","download","quota"],
        "stream":{"cors":false,"manifests":["none","dash"],"tiers":["high","lossless","hires"],"headers":false},
        "auth":{"token":"path"}}"""

    private val TIERS = listOf("low", "high", "lossless", "hires")
    private fun rank(t: String) = TIERS.indexOf(t)

    fun search(relay: RelayAnswer, limitParam: String?): MockResponse {
        if (relay.status != 200) return fromRelay(relay)
        val limit = (limitParam?.toIntOrNull() ?: 20).coerceIn(1, 200)
        val rows = (Json.parseToJsonElement(relay.body).jsonObject["tracks"] as? JsonArray).orEmpty()
        val tracks = rows.mapNotNull { (it as? JsonObject)?.let(::toTrack) }.take(limit)
        return json(200, buildJsonObject { put("tracks", JsonArray(tracks)) }.toString())
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun toTrack(row: JsonObject): JsonObject? {
        val id = row.str("id")?.takeIf { Regex("^td:[1-9]\\d{0,11}$").matches(it) } ?: return null
        val title = row.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = row.str("artist")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return buildJsonObject {
            put("id", id)
            put("title", title)
            put("artist", artist)
            row.str("album")?.trim()?.takeIf { it.isNotEmpty() }?.let { put("album", it) }
            (row["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 && it < 86_400 }?.let { put("durationMs", (it * 1000).roundToLong()) }
            row.str("isrc")?.takeIf { Regex("^[A-Za-z0-9]{12}$").matches(it) }?.let { put("isrc", it.uppercase()) }
            (row["explicit"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { put("explicit", it) }
            listOf(row.str("artworkURL"), row.str("artwork")).firstOrNull { it != null && it.startsWith("https://") && it.length <= 2048 }?.let { art ->
                put("artwork", buildJsonArray { add(buildJsonObject { put("url", art); put("width", 1280); put("height", 1280) }) })
            }
            mapOf("HI_RES_LOSSLESS" to "hires", "LOSSLESS" to "lossless", "HIGH" to "high")[row.str("audioQuality")]?.let { put("maxTier", it) }
        }
    }

    private data class Range(val tier: String, val minTier: String, val manifests: List<String>)

    fun stream(url: HttpUrl, relay: (Map<String, String>) -> RelayAnswer): MockResponse {
        val rawId = url.pathSegments.last()
        if (!Regex("^(?:td:)?([1-9]\\d{0,11})$").matches(rawId)) return failure("not_found", 404)
        val tier = url.queryParameter("tier") ?: "hires"
        val minTier = url.queryParameter("minTier") ?: "low"
        if (rank(tier) < 0 || rank(minTier) < 0 || rank(minTier) > rank(tier)) return failure("bad_request", 400)
        val manifests = (url.queryParameter("manifest") ?: "none").split(",").map { it.trim() }.filter { it in setOf("none", "dash", "hls") }
        if (manifests.isEmpty()) return failure("bad_request", 400)
        val range = Range(tier, minTier, manifests)
        val ask = relayAsk(range) ?: return failure("quality_unavailable", 404)
        val answer = relay(ask)
        if (answer.status != 200) return fromRelay(answer)
        val info = toStreamInfo(Json.parseToJsonElement(answer.body).jsonObject) ?: return failure("unavailable", 503)
        val infoTier = (info["tier"] as JsonPrimitive).content
        val infoManifest = (info["manifest"] as JsonPrimitive).content
        if (rank(infoTier) < rank(range.minTier) || rank(infoTier) > rank(range.tier) || infoManifest !in range.manifests) {
            return failure("quality_unavailable", 404)
        }
        // finish(): the allowance after this answer, in the body.
        val count = answer.headers["X-Stash-Lossless"]
        val resetsAt = answer.headers["X-Stash-Lossless-Reset"]?.toLongOrNull()
        val withQuota = if (count != null && resetsAt != null) {
            val used = count.substringBefore('/').toInt()
            val limit = count.substringAfter('/').toIntOrNull()
            JsonObject(
                info + ("quota" to buildJsonObject {
                    put("used", used)
                    if (limit != null) put("limit", limit) else put("limit", kotlinx.serialization.json.JsonNull)
                    put("resetsAt", resetsAt)
                    put("label", "Tidal plays")
                }),
            )
        } else {
            info
        }
        return json(200, withQuota.toString())
    }

    private fun relayAsk(r: Range): Map<String, String>? {
        if (rank(r.tier) < rank("high")) return null
        val dash = "dash" in r.manifests
        val none = "none" in r.manifests
        if (!dash && !none) return null
        if (r.tier == "high") return if (none) mapOf("aac" to "only", "hires" to "0") else null
        if (rank(r.minTier) <= rank("high")) return if (dash) mapOf("aac" to "1") else mapOf("aac" to "only", "hires" to "0")
        return emptyMap()
    }

    private fun toStreamInfo(b: JsonObject): JsonObject? {
        val url = b.str("url")?.takeIf { it.isNotEmpty() && it.length <= 16_384 } ?: return null
        val expiresAt = (b["expiresAt"] as? JsonPrimitive)?.longOrNull
        if (b.str("codec") == "aac") {
            return buildJsonObject {
                put("url", url); put("manifest", "none"); put("tier", "high"); put("codec", "aac"); put("container", "mp4")
                put("mime", "audio/mp4"); put("bitrate", 320_000); put("sampleRate", 44_100); put("cors", false)
                expiresAt?.takeIf { it > 0 }?.let { put("expiresAt", it) }
                put("quality", "AAC 320")
            }
        }
        if (b.str("codec") != "flac") return null
        val dash = b.str("manifest") == "dash"
        val bitDepth = (b["bitDepth"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..64 } ?: 16
        val sampleRate = (b["sampleRate"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..1_000_000 } ?: 44_100
        val tier = if (bitDepth >= 24 || sampleRate > 48_000) "hires" else "lossless"
        val expiry = if (dash) url.toHttpUrlOrNull()?.queryParameter("exp")?.toLongOrNull() ?: 0 else expiresAt
        val kbps = (b["bitrate"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 1411
        return buildJsonObject {
            put("url", url); put("manifest", if (dash) "dash" else "none"); put("tier", tier); put("codec", "flac")
            put("container", if (dash) "fmp4" else if (b.str("container") == "mp4") "mp4" else "flac")
            put("bitDepth", bitDepth); put("sampleRate", sampleRate); put("bitrate", minOf(kbps.toLong() * 1000, 100_000_000L)); put("cors", false)
            expiry?.takeIf { it > 0 }?.let { put("expiresAt", it) }
            put("quality", "${if (tier == "hires") "Hi-Res" else "CD"} $bitDepth/${sampleRate / 1000.0}")
        }
    }

    private val QUALITY_MISSES = setOf("lossless_unavailable", "web_flac_unavailable", "manifest_missing", "unsupported_manifest")

    private fun fromRelay(r: RelayAnswer): MockResponse {
        val body = runCatching { Json.parseToJsonElement(r.body) as? JsonObject }.getOrNull()
        val cause = body?.str("error").orEmpty()
        val retryAfter = r.headers["Retry-After"]?.toIntOrNull()?.takeIf { it >= 1 }
        val keep = { names: List<String> -> names.mapNotNull { n -> r.headers[n]?.let { n to it } }.toMap() }
        return when (r.status) {
            401 -> failure("unauthorized", 401)
            404 -> when {
                cause in QUALITY_MISSES -> failure("quality_unavailable", 404)
                cause == "not_available" && r.tag == "rights" -> failure("region_unavailable", 451)
                else -> failure("not_found", 404)
            }
            429 -> if (cause == "daily_lossless_cap") {
                val used = (body?.get("used") as? JsonPrimitive)?.intOrNull
                val quota = used?.let {
                    buildJsonObject {
                        put("used", it)
                        put("limit", body?.get("limit") ?: kotlinx.serialization.json.JsonNull)
                        put("resetsAt", body?.get("resetsAt") ?: JsonPrimitive(0))
                        put("label", "Tidal plays")
                    }
                }
                failure("quota_exceeded", 429, retryAfter ?: 3600, quota, keep(listOf("X-Stash-Lossless", "X-Stash-Lossless-Reset")))
            } else {
                failure("rate_limited", 429, retryAfter ?: 60)
            }
            503 -> failure("unavailable", 503, retryAfter)
            502 -> failure("unavailable", 503, retryAfter ?: 15)
            else -> if (r.status >= 500) failure("unavailable", 503) else failure("bad_request", 400)
        }
    }

    private fun failure(code: String, status: Int, retryAfter: Int? = null, quota: JsonElement? = null, extra: Map<String, String> = emptyMap()): MockResponse {
        val headers = extra.toMutableMap()
        val error = buildJsonObject {
            put("code", code)
            put("message", "fixed")
            retryAfter?.takeIf { it >= 1 }?.let { put("retryAfter", minOf(it, 86_400)); headers["Retry-After"] = minOf(it, 86_400).toString() }
            quota?.let { put("quota", it) }
        }
        return json(status, buildJsonObject { put("error", error) }.toString(), headers)
    }
}
