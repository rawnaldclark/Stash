package com.stash.data.download.lossless.addon

import com.stash.core.addons.BlockReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The signed add-on index (add-ons plan phase 4 §1.3): the official list and the blocklist, as
 * `infra/lossless-config/src/index.js` publishes it (`index.json` and `index.json.sig` in the
 * public `stash-addons/registry` repo). [parse] is that file's `readIndex`, rule for rule: the
 * publisher checks every rule, and the app checks them again.
 *
 * **Refused, never repaired.** A known field that breaks a rule refuses the whole index (the copy
 * kept from before stays in use); a field this version doesn't know is ignored, so an index from a
 * later publisher still reads. One thing is read forward on purpose: a block whose `reason` is a
 * word this version doesn't know is still a block ([BlockReason.OTHER]).
 *
 * **`type` is the first rule.** The index is signed with the key that signs `lossless.json`, so a
 * good signature alone says nothing about which file it is. Without `"type": "stash-addon-index"`
 * a signed `lossless.json` served at the index's address would pass as an index.
 */
data class AddonIndex(
    /** Unix seconds; never lower than the last one applied (the rollback floor). */
    val updatedAt: Long,
    val addons: List<Entry>,
    val blocked: List<Block>,
) {
    /**
     * One official add-on. [manifest] null: an add-on Stash turns on by default (`"default": true`);
     * its manifest URL can carry a token, so it stays in the signed config and is found by [id].
     */
    data class Entry(
        val id: String,
        val name: String,
        val summary: String,
        val icon: String? = null,
        val manifest: String? = null,
        /** The lowest app build (versionCode) it is offered to. */
        val minBuild: Int? = null,
        /**
         * `android`, `web`; null: everywhere. Empty: for no platform this app knows (a later list
         * named one, and the word was dropped): not offered here, and not Official here.
         */
        val platforms: List<String>? = null,
    ) {
        val isDefault: Boolean get() = manifest == null

        /** Offered on this phone: for Android, and not for a newer build than [build]. */
        fun offeredTo(build: Long): Boolean = (platforms == null || ANDROID in platforms) && (minBuild == null || minBuild <= build)
    }

    /** One blocked origin (`https://host[:port]`), for every add-on on it or only the one with [id]. */
    data class Block(val origin: String, val id: String?, val reason: BlockReason)

    /** The block that covers the add-on at [origin] with manifest id [id] (null: not known), if any. */
    fun blockOf(origin: String, id: String?): Block? = blocked.firstOrNull { it.origin == origin && (it.id == null || it.id == id) }

    /** Why [parse] refused: the rule's name (`type`, `v`, `size`, `addons[3].name`…), never anything from the file. */
    class Refused(val rule: String) : Exception(rule)

    companion object {
        const val TYPE = "stash-addon-index"

        /** The cap on the fetched body, as for `lossless.json`. */
        const val MAX_BYTES = 64 * 1024
        const val MAX_ADDONS = 64
        const val MAX_BLOCKED = 256
        const val MAX_TEXT = 60
        const val MAX_URL = 2048
        const val ANDROID = "android"
        private val PLATFORMS = setOf(ANDROID, "web")

        /** SPEC.md 2: the manifest `id` rule. */
        private val ID_RULE = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        private val PLAIN_SEGMENT = Regex("[a-z0-9]+(?:[-.][a-z0-9]+)*")

        /** The largest whole number a JSON reader on any platform keeps exactly (2^53 - 1). */
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val json = Json

        /**
         * Reads published index bytes. Throws [Refused] on any break: over [MAX_BYTES], not UTF-8,
         * not JSON, a wrong `type` or `v`, a bad `updated_at`, a missing list, or any entry rule.
         * The signature is checked by the caller, over these same bytes.
         */
        fun parse(bytes: ByteArray): AddonIndex {
            if (bytes.size > MAX_BYTES) refuse("size")
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                refuse("not UTF-8")
            }
            // A byte order mark is not JSON.
            if (text.startsWith('﻿')) refuse("not JSON")
            val root = (runCatching { json.parseToJsonElement(text) }.getOrNull() ?: refuse("not JSON")) as? JsonObject
                ?: refuse("not a JSON object")
            if (string(root["type"]) != TYPE) refuse("type")
            if (wholeNumber(root["v"]) != 1L) refuse("v")
            val updatedAt = wholeNumber(root["updated_at"])?.takeIf { it >= 1 } ?: refuse("updated_at")
            val addonsRaw = root["addons"] as? JsonArray ?: refuse("addons")
            val blockedRaw = root["blocked"] as? JsonArray ?: refuse("blocked")
            val addons = addons(addonsRaw)
            val blocked = blocked(blockedRaw)
            // A listed add-on that is also blocked is a contradiction: refused rather than guessed at.
            addons.forEachIndexed { i, a ->
                val origin = a.manifest?.toHttpUrlOrNull()?.let(::originOf) ?: return@forEachIndexed
                if (blocked.any { it.origin == origin && (it.id == null || it.id == a.id) }) refuse("addons[$i] is also blocked")
            }
            return AddonIndex(updatedAt, addons, blocked)
        }

        private fun addons(raw: JsonArray): List<Entry> {
            if (raw.size > MAX_ADDONS) refuse("addons (over $MAX_ADDONS)")
            val ids = HashSet<String>()
            val manifests = HashSet<String>()
            return raw.mapIndexed { i, e ->
                val at = "addons[$i]"
                val o = e as? JsonObject ?: refuse(at)
                val id = string(o["id"])?.takeIf(ID_RULE::matches) ?: refuse("$at.id")
                if (!ids.add(id)) refuse("$at.id (listed twice)")
                val name = line(o["name"]) ?: refuse("$at.name")
                val summary = line(o["summary"]) ?: refuse("$at.summary")
                val icon = if ("icon" in o) url(o["icon"]) ?: refuse("$at.icon") else null
                // Exactly one of `manifest` (a public URL) or `"default": true`.
                if ("default" in o && !isTrue(o["default"])) refuse("$at.default")
                if (("manifest" in o) == ("default" in o)) refuse("$at (needs exactly one of manifest or default)")
                val manifest = if ("manifest" in o) manifestUrl(o["manifest"]) ?: refuse("$at.manifest") else null
                if (manifest != null && !manifests.add(manifest)) refuse("$at.manifest (listed twice)")
                val minBuild = if ("min_build" in o) {
                    wholeNumber(o["min_build"])?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: refuse("$at.min_build")
                } else {
                    null
                }
                val platforms = if ("platforms" in o) {
                    val list = (o["platforms"] as? JsonArray)?.map { string(it) ?: refuse("$at.platforms") } ?: refuse("$at.platforms")
                    if (list.isEmpty() || list.toSet().size != list.size) refuse("$at.platforms")
                    // A word this app doesn't know (a later list's, "ios" say) drops out of the entry and
                    // never refuses the index: a newer list must never make an older app miss its
                    // blocks (review-885 M14). The publisher's reader does the same (`index.js`).
                    list.filter { it in PLATFORMS }
                } else {
                    null
                }
                Entry(id, name, summary, icon, manifest, minBuild, platforms)
            }
        }

        private fun blocked(raw: JsonArray): List<Block> {
            if (raw.size > MAX_BLOCKED) refuse("blocked (over $MAX_BLOCKED)")
            val out = raw.mapIndexed { i, e ->
                val at = "blocked[$i]"
                val o = e as? JsonObject ?: refuse(at)
                val origin = origin(o["origin"]) ?: refuse("$at.origin")
                val id = if ("id" in o) string(o["id"])?.takeIf(ID_RULE::matches) ?: refuse("$at.id") else null
                // A reason this version doesn't know still blocks (as BlockReason.OTHER): a newer list
                // must never make an older app drop a block. Only a block with no reason at all is broken.
                val reason = string(o["reason"])?.let(BlockReason::of) ?: refuse("$at.reason")
                Block(origin, id, reason)
            }
            // One entry per (origin, id); an entry without an id already covers every id on its origin.
            val seen = HashMap<String, MutableSet<String>>()
            out.forEachIndexed { i, b ->
                val onOrigin = seen.getOrPut(b.origin) { HashSet() }
                val key = b.id.orEmpty()
                if (key in onOrigin) refuse("blocked[$i] (repeats another)")
                if (onOrigin.isNotEmpty() && (key.isEmpty() || "" in onOrigin)) refuse("blocked[$i] (overlaps another)")
                onOrigin += key
            }
            return out
        }

        private fun refuse(rule: String): Nothing = throw Refused(rule)

        private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun isTrue(e: JsonElement?): Boolean = e is JsonPrimitive && e !is JsonNull && !e.isString && e.content == "true"

        /** A JSON number that is a whole number a reader keeps exactly (`1`, `1.0` and `1e0` all are); null otherwise. */
        private fun wholeNumber(e: JsonElement?): Long? {
            val p = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString } ?: return null
            if (!JSON_NUMBER.matches(p.content)) return null
            val n = runCatching { BigDecimal(p.content) }.getOrNull() ?: return null
            if (n.signum() != 0 && n.stripTrailingZeros().scale() > 0) return null
            if (n.abs() > BigDecimal.valueOf(MAX_SAFE_INTEGER)) return null
            return n.toLong()
        }

        /**
         * One line of text for a name or a summary: 1 to [MAX_TEXT] UTF-16 units, nothing invisible,
         * no whitespace but single plain spaces between words.
         */
        private fun line(e: JsonElement?): String? {
            val v = string(e) ?: return null
            if (v.isEmpty() || v.length > MAX_TEXT) return null
            if (v.startsWith(' ') || v.endsWith(' ') || "  " in v) return null
            return v.takeIf { codePoints(it).none { cp -> unsafe(cp) || oddSpace(cp) } }
        }

        private fun codePoints(s: String): Sequence<Int> = sequence {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                yield(cp)
                i += Character.charCount(cp)
            }
        }

        /** The characters a name, a summary or a URL may not hold (index.js `UNSAFE_TEXT_RANGES`). */
        private fun unsafe(cp: Int): Boolean = cp in 0x0000..0x001f || cp in 0x007f..0x009f || cp == 0x061c ||
            cp in 0x200b..0x200f || cp in 0x2028..0x202e || cp in 0x2060..0x2069 || cp == 0xfeff ||
            cp == 0x00ad || cp == 0x180e || cp in 0x206a..0x206f || cp in 0xd800..0xdfff ||
            cp in 0xfff9..0xfffb || cp in 0xe0000..0xe007f

        /** Any whitespace character but the plain space (index.js `ODD_SPACE`: no-break, thin, ideographic and the rest). */
        private fun oddSpace(cp: Int): Boolean = cp in 0x0009..0x000d || cp == 0x00a0 || cp == 0x1680 ||
            cp in 0x2000..0x200a || cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000 || cp == 0xfeff

        /** SPEC.md 1.1's URL rule: https, a host, no user or password, no whitespace, control character or backslash, at most [MAX_URL]. */
        private fun url(e: JsonElement?): String? = string(e)?.takeIf { httpsUrl(it) != null }

        private fun httpsUrl(raw: String): HttpUrl? {
            if (raw.isEmpty() || raw.length > MAX_URL || !raw.startsWith("https://")) return null
            if (raw.any { it == '\\' } || codePoints(raw).any { unsafe(it) || oddSpace(it) || it == 0x20 }) return null
            val u = raw.toHttpUrlOrNull() ?: return null
            return u.takeIf { it.host.isNotEmpty() && it.username.isEmpty() && it.password.isEmpty() }
        }

        /**
         * An official add-on's public manifest URL: [url]'s rule, ending in `/manifest.json`, with no
         * query or fragment and no path part that looks like a token (a manifest whose URL carries
         * one stays in the signed config, listed here by id with `"default": true`).
         */
        private fun manifestUrl(e: JsonElement?): String? {
            val raw = string(e) ?: return null
            val u = httpsUrl(raw) ?: return null
            if ('?' in raw || '#' in raw || !u.encodedPath.endsWith("/manifest.json")) return null
            if (u.encodedPathSegments.dropLast(1).any(::looksLikeToken)) return null
            return raw
        }

        /** index.js `looksLikeToken`: anything but short lowercase words joined by `-` or `.`. */
        fun looksLikeToken(segment: String): Boolean {
            if (segment.length > 32 || !PLAIN_SEGMENT.matches(segment)) return true
            return segment.split('-', '.').any { w -> w.length >= 20 || (w.length >= 8 && w.any { it in '0'..'9' }) }
        }

        /**
         * A blocked `origin`: exactly `https://host[:port]`, lower case, no default port, no path and
         * no trailing slash, no dot after the host. Compared exactly, so there is one way to write each.
         */
        private fun origin(e: JsonElement?): String? {
            val raw = string(e)?.takeIf { it.isNotEmpty() && it.length <= MAX_URL } ?: return null
            val u = raw.toHttpUrlOrNull()?.takeIf { it.scheme == "https" } ?: return null
            if (originOf(u) != raw || u.host.endsWith('.')) return null
            return raw
        }

        /**
         * The origin of [url] as the index writes one: scheme, host and, when it isn't the scheme's
         * own, the port. What an add-on's base is compared with against the blocklist.
         */
        fun originOf(url: HttpUrl): String {
            val host = if (':' in url.host) "[${url.host}]" else url.host
            return "${url.scheme}://$host${if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""}"
        }

        /** [originOf] of a base or manifest URL as text; null when it isn't a URL. */
        fun originOf(url: String): String? = url.toHttpUrlOrNull()?.let(::originOf)
    }
}
