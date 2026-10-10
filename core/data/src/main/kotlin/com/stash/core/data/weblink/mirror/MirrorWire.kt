package com.stash.core.data.weblink.mirror

import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.FirstMerge
import com.stash.core.data.weblink.merge.Follow
import com.stash.core.model.weblink.Hlc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The mirror's documents on the wire (stash-player `docs/sync-v1.md` §5.2–5.4; the web's `src/lib/sync/mirror-wire.ts`): the shared
 * settings (`stash-mirror-config`), a batch of changes (`stash-mirror-ops`) and a snapshot (`stash-mirror-state`). Readers rebuild
 * every value they keep, refuse a newer `v` of a kind they know ([MirrorWireException.newer]: "Update Stash to keep syncing"),
 * refuse another kind, skip what they can't read (an op of a type they don't know, a song without a title) and ignore unknown
 * fields. Writers send only what the contract names. Songs are read and written exactly as the handoff's ([HandoffWire]).
 */

class MirrorWireException(message: String, val newer: Boolean = false) : Exception(message)

/** Where a kind mirrors: [TO_WEB] = phone → web, [TO_PHONE] = web → phone. */
enum class Dir(val wire: String) {
    OFF("off"), BOTH("both"), TO_WEB("toWeb"), TO_PHONE("toPhone");

    /** The phone sends a kind on `both` / `toWeb` (sync-v1 §5.2). */
    val phoneSends: Boolean get() = this == BOTH || this == TO_WEB

    /** The phone applies a kind on `both` / `toPhone`. */
    val phoneApplies: Boolean get() = this == BOTH || this == TO_PHONE

    companion object {
        fun of(wire: String?): Dir = entries.firstOrNull { it.wire == wire } ?: OFF
    }
}

enum class Kind(val wire: String) {
    LIKES("likes"), PLAYS("plays"), PLAYLISTS("playlists");

    companion object {
        fun of(wire: String?): Kind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One kind's settings: its direction, the log head when it was last turned on ([since]) and who turned it on ([by]). */
data class KindConfig(val dir: Dir = Dir.OFF, val since: Long = 0, val by: String? = null)

/** `stash-mirror-config` v1 (sync-v1 §5.2): one per space, last writer wins on [at]. */
data class MirrorConfig(
    val at: Hlc,
    val likes: KindConfig = KindConfig(),
    val plays: KindConfig = KindConfig(),
    val playlists: KindConfig = KindConfig(),
    /** The chosen playlists' mirror ids. */
    val ids: List<String> = emptyList(),
    /** "Mirror new playlists too". */
    val newOnes: Boolean = false,
) {
    fun of(k: Kind): KindConfig = when (k) {
        Kind.LIKES -> likes
        Kind.PLAYS -> plays
        Kind.PLAYLISTS -> playlists
    }

    fun with(k: Kind, c: KindConfig): MirrorConfig = when (k) {
        Kind.LIKES -> copy(likes = c)
        Kind.PLAYS -> copy(plays = c)
        Kind.PLAYLISTS -> copy(playlists = c)
    }

    val anyOn: Boolean get() = Kind.entries.any { of(it).dir != Dir.OFF }

    companion object {
        /** Every kind off, nothing chosen: what a space starts with. */
        fun off(at: Hlc) = MirrorConfig(at)
    }
}

/** One op of a `stash-mirror-ops` batch (sync-v1 §5.3). */
sealed interface MirrorOp {
    data class Like(val s: WireSong, val on: Boolean, val at: Hlc) : MirrorOp
    data class Play(val s: WireSong, val playedAt: Long) : MirrorOp
    data class ClearPlays(val before: Long, val at: Hlc, val all: Boolean = false) : MirrorOp

    /** [items] null: deleted everywhere. [parent]: the hash of the version the edit started from (null for a new one). */
    data class Pl(
        val id: String,
        val at: Hlc,
        val parent: String?,
        val name: String,
        val items: List<WireSong>?,
        val follow: Follow? = null,
        val ro: Boolean = false,
    ) : MirrorOp

    /** The writer joined [kind] (turned on at [since]) and everything it holds of it is in this batch or an earlier one. */
    data class Joined(val kind: Kind, val since: Long) : MirrorOp

    /** The op's stamp, for the ops that carry one. */
    val stamp: Hlc?
        get() = when (this) {
            is Like -> at
            is ClearPlays -> at
            is Pl -> at
            else -> null
        }
}

data class MirrorBatch(val device: String, val ops: List<MirrorOp>)

/** `stash-mirror-state` v1 (sync-v1 §5.4): the space after log entry [uptoSeq]. */
data class MirrorState(
    val uptoSeq: Long,
    val likes: List<StateLike> = emptyList(),
    val plays: List<StatePlay> = emptyList(),
    val playsClearedBefore: Long = 0,
    val playlists: List<StatePl> = emptyList(),
    val joined: List<JoinMark> = emptyList(),
) {
    data class StateLike(val s: WireSong, val on: Boolean, val at: Hlc)
    data class StatePlay(val s: WireSong, val playedAt: Long, val device: String)
    data class StatePl(val id: String, val name: String, val items: List<WireSong>?, val follow: Follow?, val ro: Boolean, val at: Hlc, val hash: String)
}

/** "`device` joined `kind` when it was turned on at `since`". */
data class JoinMark(val kind: Kind, val since: Long, val device: String) {
    val key: String get() = "${kind.wire}|$since|$device"
}

object MirrorWire {
    const val CONFIG_KIND = "stash-mirror-config"
    const val OPS_KIND = "stash-mirror-ops"
    const val STATE_KIND = "stash-mirror-state"
    const val VERSION = 1

    /** Songs a mirrored playlist holds at most (sync-v1 §8). */
    const val MAX_PL_ITEMS = 10_000

    /** A playlist name's length at most, in UTF-16 units (sync-v1 §8). */
    const val NAME_MAX = 100

    val MIRROR_ID = Regex("^m_[A-Za-z0-9]{16}$")
    private val PL_HASH = Regex("^h_[A-Za-z0-9_-]{22}$")
    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val FOLLOW_ID = Regex("^[A-Za-z0-9_-]{4,64}$")

    /** JavaScript's `\s+` (it matches U+FEFF and not U+0085), for the names' `replace(/\s+/g, ' ')`. */
    private val JS_SPACES = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    /** JavaScript's `Number.MAX_SAFE_INTEGER`: a bigger integer isn't one the web can hold exactly. */
    private const val MAX_SAFE = 9_007_199_254_740_991L

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------------------------- value readers

    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.str(k: String): String? = prim(k)?.takeIf { it.isString }?.content

    /** An integer ≥ 0 written as a JSON number (sync-v1 §1: a number written as a string is refused). */
    private fun nat(e: JsonElement?): Long? {
        val p = (e as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull } ?: return null
        val v = p.content.toLongOrNull() ?: return null
        return v.takeIf { it in 0..MAX_SAFE }
    }

    private fun JsonObject.nat(k: String): Long? = nat(this[k])

    private fun JsonObject.isTrue(k: String): Boolean = prim(k)?.takeUnless { it.isString }?.booleanOrNull == true

    private fun JsonObject.bool(k: String): Boolean? = prim(k)?.takeUnless { it.isString }?.booleanOrNull

    /** `[wallMs, counter, deviceId]` checked by type. */
    fun readHlc(e: JsonElement?): Hlc? {
        val a = e as? JsonArray ?: return null
        if (a.size != 3) return null
        val w = nat(a[0]) ?: return null
        val c = nat(a[1])?.takeIf { it <= Int.MAX_VALUE } ?: return null
        val d = (a[2] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(DEVICE_ID::matches) ?: return null
        return Hlc(w, c.toInt(), d)
    }

    fun hlcJson(h: Hlc): JsonArray = buildJsonArray {
        add(h.wall)
        add(h.counter)
        add(h.device)
    }

    private fun nameOf(e: JsonElement?): String? {
        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return FirstMerge.cutUnits(s.replace(JS_SPACES, " ").trim(), FirstMerge.MAX_PLAYLIST_NAME).ifEmpty { null }
    }

    private fun followOf(e: JsonElement?): Follow? {
        val o = e as? JsonObject ?: return null
        val id = o.str("id")?.takeIf(FOLLOW_ID::matches) ?: return null
        val v = o.nat("version")?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
        return Follow(id, v.toInt())
    }

    /** `null` (deleted), a list (unreadable songs left out), or [Unreadable] (not a list, or too long). */
    private object Unreadable

    private fun itemsOf(e: JsonElement?): Any? {
        if (e == null) return Unreadable
        if (e is JsonNull) return null
        val a = e as? JsonArray ?: return Unreadable
        if (a.size > MAX_PL_ITEMS) return Unreadable
        return a.mapNotNull { HandoffWire.readSong(it) }
    }

    private fun root(text: String, kind: String): JsonObject {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw MirrorWireException("not a document")
        if (o.str("kind") != kind) throw MirrorWireException("not a $kind")
        val v = o.nat("v") ?: throw MirrorWireException("bad version")
        if (v < 1) throw MirrorWireException("bad version")
        if (v > VERSION) throw MirrorWireException("$kind v$v is newer than this app", newer = true)
        return o
    }

    // ---------------------------------------------------------------------------------------- config

    fun readConfig(text: String): MirrorConfig {
        val d = root(text, CONFIG_KIND)
        val at = readHlc(d["at"]) ?: throw MirrorWireException("bad at")
        fun kind(e: JsonElement?): KindConfig {
            val o = e as? JsonObject ?: return KindConfig()
            return KindConfig(Dir.of(o.str("dir")), o.nat("since") ?: 0, o.str("by")?.takeIf(DEVICE_ID::matches))
        }
        val pl = d["playlists"] as? JsonObject ?: JsonObject(emptyMap())
        val ids = (pl["ids"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(MIRROR_ID::matches) }
            .distinct()
        return MirrorConfig(at, kind(d["likes"]), kind(d["plays"]), kind(pl), ids, pl.isTrue("newOnes"))
    }

    fun configJson(c: MirrorConfig): String = buildJsonObject {
        put("kind", CONFIG_KIND)
        put("v", VERSION)
        put("at", hlcJson(c.at))
        fun kind(k: KindConfig): JsonObject = buildJsonObject {
            put("dir", k.dir.wire)
            put("since", k.since)
            k.by?.let { put("by", it) }
        }
        put("likes", kind(c.likes))
        put("plays", kind(c.plays))
        put(
            "playlists",
            JsonObject(
                kind(c.playlists) + mapOf(
                    "ids" to buildJsonArray { c.ids.forEach { add(it) } },
                    "newOnes" to JsonPrimitive(c.newOnes),
                ),
            ),
        )
    }.toString()

    // ---------------------------------------------------------------------------------------- ops

    private fun readOp(e: JsonElement): MirrorOp? {
        val o = e as? JsonObject ?: return null
        return when (o.str("t")) {
            "like" -> {
                val s = HandoffWire.readSong(o["s"]) ?: return null
                val at = readHlc(o["at"]) ?: return null
                val on = o.bool("on") ?: return null
                MirrorOp.Like(s, on, at)
            }
            "play" -> {
                val s = HandoffWire.readSong(o["s"]) ?: return null
                val playedAt = o.nat("playedAt")?.takeIf { it > 0 } ?: return null
                MirrorOp.Play(s, playedAt)
            }
            "clearPlays" -> {
                val at = readHlc(o["at"]) ?: return null
                val before = o.nat("before") ?: return null
                MirrorOp.ClearPlays(before, at, o.isTrue("all"))
            }
            "pl" -> {
                val at = readHlc(o["at"]) ?: return null
                val id = o.str("id")?.takeIf(MIRROR_ID::matches) ?: return null
                val items = itemsOf(o["items"])
                if (items === Unreadable) return null
                val name = nameOf(o["name"])
                if (items != null && name == null) return null
                @Suppress("UNCHECKED_CAST")
                MirrorOp.Pl(
                    id = id,
                    at = at,
                    parent = o.str("parent")?.takeIf(PL_HASH::matches),
                    name = name ?: "",
                    items = items as List<WireSong>?,
                    follow = followOf(o["follow"]),
                    ro = o.isTrue("ro"),
                )
            }
            "joined" -> {
                val kind = Kind.of(o.str("kind")) ?: return null
                val since = o.nat("since") ?: return null
                MirrorOp.Joined(kind, since)
            }
            else -> null // an op this app doesn't know: skipped (sync-v1 §5.3)
        }
    }

    /** `stash-mirror-ops` v1: the writing device and the ops this app can read. */
    fun readOps(text: String): MirrorBatch {
        val d = root(text, OPS_KIND)
        val device = d.str("device")?.takeIf(DEVICE_ID::matches) ?: throw MirrorWireException("bad device")
        val ops = d["ops"] as? JsonArray ?: throw MirrorWireException("bad ops")
        return MirrorBatch(device, ops.mapNotNull(::readOp))
    }

    private fun opJson(o: MirrorOp): JsonObject = buildJsonObject {
        when (o) {
            is MirrorOp.Like -> {
                put("t", "like")
                put("s", HandoffWire.songJson(o.s))
                put("on", o.on)
                put("at", hlcJson(o.at))
            }
            is MirrorOp.Play -> {
                put("t", "play")
                put("s", HandoffWire.songJson(o.s))
                put("playedAt", o.playedAt)
            }
            is MirrorOp.ClearPlays -> {
                put("t", "clearPlays")
                put("before", o.before)
                put("at", hlcJson(o.at))
                if (o.all) put("all", true)
            }
            is MirrorOp.Pl -> {
                put("t", "pl")
                put("id", o.id)
                put("at", hlcJson(o.at))
                put("parent", o.parent?.let(::JsonPrimitive) ?: JsonNull)
                put("name", o.name)
                put("items", o.items?.let { items -> buildJsonArray { items.forEach { add(HandoffWire.songJson(it)) } } } ?: JsonNull)
                o.follow?.let { f -> putJsonObject("follow") { put("id", f.id); put("version", f.version) } }
                if (o.ro) put("ro", true)
            }
            is MirrorOp.Joined -> {
                put("t", "joined")
                put("kind", o.kind.wire)
                put("since", o.since)
            }
        }
    }

    fun opsJson(device: String, ops: List<MirrorOp>): String = buildJsonObject {
        put("kind", OPS_KIND)
        put("v", VERSION)
        put("device", device)
        putJsonArray("ops") { ops.forEach { add(opJson(it)) } }
    }.toString()

    // ---------------------------------------------------------------------------------------- snapshot

    fun readState(text: String): MirrorState {
        val d = root(text, STATE_KIND)
        val upto = d.nat("uptoSeq") ?: throw MirrorWireException("bad uptoSeq")
        val joined = (d["joined"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val k = Kind.of(o.str("kind")) ?: return@mapNotNull null
            val since = o.nat("since") ?: return@mapNotNull null
            val dev = o.str("device")?.takeIf(DEVICE_ID::matches) ?: return@mapNotNull null
            JoinMark(k, since, dev)
        }
        val likes = (d["likes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val s = HandoffWire.readSong(o["s"]) ?: return@mapNotNull null
            val at = readHlc(o["at"]) ?: return@mapNotNull null
            val on = o.bool("on") ?: return@mapNotNull null
            MirrorState.StateLike(s, on, at)
        }
        val plays = (d["plays"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val s = HandoffWire.readSong(o["s"]) ?: return@mapNotNull null
            val playedAt = o.nat("playedAt")?.takeIf { it > 0 } ?: return@mapNotNull null
            val dev = o.str("device")?.takeIf(DEVICE_ID::matches) ?: return@mapNotNull null
            MirrorState.StatePlay(s, playedAt, dev)
        }
        val seen = HashSet<String>()
        val playlists = (d["playlists"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf(MIRROR_ID::matches)?.takeIf { it !in seen } ?: return@mapNotNull null
            val at = readHlc(o["at"]) ?: return@mapNotNull null
            val items = itemsOf(o["items"])
            if (items === Unreadable) return@mapNotNull null
            val name = nameOf(o["name"])
            if (items != null && name == null) return@mapNotNull null
            val hash = o.str("hash")?.takeIf(PL_HASH::matches) ?: return@mapNotNull null
            seen += id
            @Suppress("UNCHECKED_CAST")
            MirrorState.StatePl(id, name ?: "", items as List<WireSong>?, followOf(o["follow"]), o.isTrue("ro"), at, hash)
        }
        return MirrorState(upto, likes, plays, d.nat("playsClearedBefore") ?: 0, playlists, joined)
    }

    fun stateJson(st: MirrorState): String = buildJsonObject {
        put("kind", STATE_KIND)
        put("v", VERSION)
        put("uptoSeq", st.uptoSeq)
        putJsonArray("likes") {
            st.likes.forEach { l -> addJsonObject { put("s", HandoffWire.songJson(l.s)); put("on", l.on); put("at", hlcJson(l.at)) } }
        }
        putJsonArray("plays") {
            st.plays.forEach { p -> addJsonObject { put("s", HandoffWire.songJson(p.s)); put("playedAt", p.playedAt); put("device", p.device) } }
        }
        put("playsClearedBefore", st.playsClearedBefore)
        putJsonArray("playlists") {
            st.playlists.forEach { p ->
                addJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("items", p.items?.let { items -> buildJsonArray { items.forEach { add(HandoffWire.songJson(it)) } } } ?: JsonNull)
                    put("at", hlcJson(p.at))
                    put("hash", p.hash)
                    p.follow?.let { f -> putJsonObject("follow") { put("id", f.id); put("version", f.version) } }
                    if (p.ro) put("ro", true)
                }
            }
        }
        putJsonArray("joined") {
            st.joined.forEach { j -> addJsonObject { put("kind", j.kind.wire); put("since", j.since); put("device", j.device) } }
        }
    }.toString()
}
