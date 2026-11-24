package com.stash.core.data.weblink.remote

import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.HandoffWireException
import com.stash.core.data.weblink.handoff.WireSong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One song of a computer's queue as its state names it: [iid] is how this phone points at it (remove, move, skip to).
 */
data class ComputerItem(val iid: String, val song: WireSong)

/**
 * What Stash on a computer says it plays (sync-v1 §5.7 `stash-remote-state` with the cap `queue`): the computer keeps the
 * queue, and this phone is its remote. [items] is the queue from the playing song on (the playing one first), a window of at
 * most 50 of the queue's [length] songs; the playing one is at [at] in the whole queue.
 */
data class ComputerState(
    val sid: String,
    val playing: Boolean,
    val loading: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Double?,
    val song: WireSong?,
    val iid: String?,
    val at: Int,
    val length: Int,
    val items: List<ComputerItem>,
    val shuffle: Boolean,
    /** "off", "all" or "one". */
    val repeat: String,
    /** "Playing from" on the computer, or null. */
    val from: String?,
)

/** A computer whose socket is open, and what it said last ([receivedAt]: this phone's elapsed-realtime clock). */
data class ComputerLive(val device: String, val name: String, val state: ComputerState, val receivedAt: Long)

/** A command to a computer that keeps the queue (sync-v1 §5.7, cap `queue`). [wire] is its `cmd`. */
sealed class ComputerCommand(val wire: String) {
    data object Play : ComputerCommand("play")
    data object Pause : ComputerCommand("pause")
    data object Next : ComputerCommand("next")
    data object Prev : ComputerCommand("prev")
    data object Stop : ComputerCommand("stop")
    data class Seek(val positionMs: Long) : ComputerCommand("seek")
    data class Volume(val volume: Double) : ComputerCommand("volume")

    /** Play [songs] there as its queue, from the one at [at], from [positionMs]; paused unless [play]. [from]: "Playing from". */
    data class Start(val songs: List<WireSong>, val at: Int, val positionMs: Long, val play: Boolean, val from: String?) :
        ComputerCommand("start")

    /** Add [songs] after the playing one ([next]) or at the end of the queue. */
    data class Add(val songs: List<WireSong>, val next: Boolean) : ComputerCommand("add")
    data class Remove(val iid: String) : ComputerCommand("remove")

    /** Move the song [iid] to the place [to] in the whole queue. */
    data class Move(val iid: String, val to: Int) : ComputerCommand("move")
    data class SkipTo(val iid: String) : ComputerCommand("skipTo")
    data class Shuffle(val on: Boolean) : ComputerCommand("shuffle")
    data class Repeat(val mode: String) : ComputerCommand("repeat")
}

/**
 * The documents of a computer's remote, as the desktop's `stash_core::link::remote` reads and writes them. The readers follow
 * [RemoteWire]'s rules: another kind or a newer `v` is refused, unknown fields ignored, every value checked by its JSON type.
 */
object ComputerWire {
    /** The cap of a computer that keeps its own queue and takes a phone's queue commands. */
    const val CAP_QUEUE = "queue"

    /** At most this many songs go in one command (the computer takes 100). */
    const val MAX_SONGS = 100

    private const val MAX_FROM = 300
    private val json = Json { ignoreUnknownKeys = true }

    fun cmdJson(sid: String, n: Long, c: ComputerCommand): String = buildJsonObject {
        put("kind", RemoteWire.CMD_KIND)
        put("v", RemoteWire.VERSION)
        put("sid", sid)
        put("n", n)
        put("cmd", c.wire)
        when (c) {
            is ComputerCommand.Seek -> put("positionMs", c.positionMs.coerceAtLeast(0))
            is ComputerCommand.Volume -> put("volume", c.volume.coerceIn(0.0, 1.0))
            is ComputerCommand.Start -> {
                putJsonArray("items") { c.songs.take(MAX_SONGS).forEachIndexed { i, s -> add(item(i, s)) } }
                put("at", c.at.coerceIn(0, (minOf(c.songs.size, MAX_SONGS) - 1).coerceAtLeast(0)))
                put("positionMs", c.positionMs.coerceAtLeast(0))
                put("play", c.play)
                c.from?.let { HandoffWire.cleanText(it, MAX_FROM) }?.takeIf { it.isNotEmpty() }?.let { put("from", it) }
            }
            is ComputerCommand.Add -> {
                putJsonArray("items") { c.songs.take(MAX_SONGS).forEachIndexed { i, s -> add(item(i, s)) } }
                put("next", c.next)
            }
            is ComputerCommand.Remove -> put("iid", c.iid)
            is ComputerCommand.Move -> {
                put("iid", c.iid)
                put("to", c.to.coerceAtLeast(0))
            }
            is ComputerCommand.SkipTo -> put("iid", c.iid)
            is ComputerCommand.Shuffle -> put("on", c.on)
            is ComputerCommand.Repeat -> put("mode", c.mode)
            ComputerCommand.Play, ComputerCommand.Pause, ComputerCommand.Next, ComputerCommand.Prev, ComputerCommand.Stop -> Unit
        }
    }.toString()

    private fun item(i: Int, s: WireSong) = buildJsonObject {
        put("id", "p$i")
        put("song", HandoffWire.songJson(s))
    }

    /** A computer's state. Throws [HandoffWireException] for another kind, a newer version or a bad field. */
    fun readState(text: String): ComputerState {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw HandoffWireException("not a document")
        if (o.str("kind") != RemoteWire.STATE_KIND) throw HandoffWireException("not a state")
        val v = o.long("v") ?: throw HandoffWireException("no version")
        if (v > RemoteWire.VERSION) throw HandoffWireException("state v$v is newer than this app", newer = true)
        val sid = o.str("sid")?.takeIf(RemoteWire.SID::matches) ?: throw HandoffWireException("bad sid")
        val playing = o.bool("playing") ?: throw HandoffWireException("bad playing")
        val position = o.long("positionMs")?.takeIf { it >= 0 } ?: throw HandoffWireException("bad positionMs")
        val queue = o["queue"] as? JsonObject
        val items = (queue?.get("items") as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val iid = x.str("iid")?.takeIf(RemoteWire.ITEM_ID::matches) ?: return@mapNotNull null
            HandoffWire.readSong(x["song"])?.let { ComputerItem(iid, it) }
        }.distinctBy { it.iid }.take(RemoteWire.MAX_UP_NEXT)
        val length = queue?.long("length")?.takeIf { it >= 0 }?.toInt() ?: items.size
        return ComputerState(
            sid = sid,
            playing = playing,
            loading = o.str("phase") == "loading",
            positionMs = position,
            durationMs = o.long("durationMs")?.takeIf { it > 0 } ?: 0L,
            volume = o.num("volume")?.coerceIn(0.0, 1.0),
            song = HandoffWire.readSong(o["song"]),
            iid = o.str("iid")?.takeIf(RemoteWire.ITEM_ID::matches),
            at = queue?.long("at")?.takeIf { it >= 0 }?.toInt() ?: 0,
            length = maxOf(length, items.size),
            items = items,
            shuffle = o.bool("shuffle") == true,
            repeat = o.str("repeat")?.takeIf { it == "off" || it == "all" || it == "one" } ?: "off",
            from = o.str("from")?.let { HandoffWire.cleanText(it, MAX_FROM) }?.takeIf { it.isNotEmpty() },
        )
    }

    /** Whether a state's `caps` say the sender keeps its own queue (a computer), read before [readState]. */
    fun keepsQueue(text: String): Boolean = try {
        val o = json.parseToJsonElement(text) as? JsonObject
        (o?.get("caps") as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == CAP_QUEUE }
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.long(k: String): Long? {
        val p = (this[k] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite() && d == Math.floor(d) && kotlin.math.abs(d) < 9.0e15) d.toLong() else null
    }
    private fun JsonObject.num(k: String): Double? =
        (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}
