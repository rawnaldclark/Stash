package com.stash.core.data.weblink.remote

import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.HandoffWireException
import com.stash.core.data.weblink.handoff.WireSong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What a browser says it plays (sync-v1 §5.7 `stash-remote-state`), as read: the fields the "Playing on Chrome" card shows.
 *
 * @property sid that browser's socket session; every command names it.
 * @property needsGesture the tab was never clicked, so the browser would refuse a remote play ("Click the Stash tab once").
 * @property volume 0–1, or null when that browser can't set its volume (no slider).
 * @property art the first https cover, or null.
 * @property caps what the browser can do beyond v1 (Remote v2, spec 2026-11-03 §4): `drive` means it plays a phone's queue.
 *   Empty from a v1 browser, which "Play on" never offers.
 * @property driver the phone that drives it, or null while it plays its own music.
 * @property iid the id of the item it plays for its [driver] (from `load` or `upNext`), or null.
 * @property next the id it holds after [iid], so the phone knows its `upNext` arrived.
 * @property phase where that item is; null from a v1 browser.
 * @property pendingPlay a phone's play waits for a click in the tab (it comes with [needsGesture]).
 */
data class RemoteState(
    val sid: String,
    val needsGesture: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val rate: Double,
    val volume: Double?,
    val canSeek: Boolean,
    val title: String?,
    val artist: String?,
    val art: String?,
    val caps: List<String> = emptyList(),
    val driver: String? = null,
    val iid: String? = null,
    val next: String? = null,
    val phase: RemotePhase? = null,
    val pendingPlay: Boolean = false,
) {
    /** It can play a phone's queue (Remote v2). */
    val canDrive: Boolean get() = RemoteWire.CAP_DRIVE in caps
}

/** Where a driven browser's item is (`phase`, Remote v2 §4). */
enum class RemotePhase(val wire: String) {
    LOADING("loading"), PLAYING("playing"), PAUSED("paused"), ENDED("ended"), FAILED("failed");

    companion object {
        fun of(wire: String?): RemotePhase? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One song a phone hands a browser (Remote v2 §4): [id] is how the browser's state names it back (`iid`, `next`). A read `load`
 * whose song won't read, or is phone-only, keeps its id with [song] null: the browser takes it and reports it failed.
 */
data class RemoteItem(val id: String, val song: WireSong?)

/** A remote command. [wire] is its `cmd` value. */
sealed class RemoteCommand(val wire: String) {
    // v1: transport and volume (the card; and the driving phone's play, pause, seek and volume).
    data object Play : RemoteCommand("play")
    data object Pause : RemoteCommand("pause")
    data object Next : RemoteCommand("next")
    data object Prev : RemoteCommand("prev")
    data class Seek(val positionMs: Long) : RemoteCommand("seek")
    data class Volume(val volume: Double) : RemoteCommand("volume")

    // Remote v2 (spec 2026-11-03 §4): the browser plays this phone's queue, one song and the one after it.

    /** Be driven by this phone: replace your queue with [item], from [positionMs], playing if [play]. [from]: "Playing from". */
    data class Load(val item: RemoteItem, val positionMs: Long, val play: Boolean, val from: String? = null) : RemoteCommand("load")

    /** Replace everything after the current item with [items] (phase 1: none or one). */
    data class UpNext(val items: List<RemoteItem>) : RemoteCommand("upNext")

    /** Play the held item [id] from its start. */
    data class Jump(val id: String) : RemoteCommand("jump")

    /** Stop and unload (the phone's queue ran out or was cleared); it stays driven. */
    data object Stop : RemoteCommand("stop")

    /** Stop being driven, and pause unless [pause] is false. What the browser holds becomes its own queue. */
    data class Release(val pause: Boolean = true) : RemoteCommand("release")
}

/** A `stash-remote-cmd`: [command] for the browser session [sid], the [n]th of that session (each taken once, in order). */
data class RemoteCmd(val sid: String, val n: Long, val command: RemoteCommand)

/**
 * The remote-control documents (sync-v1 §1, §5.7), the same rules as the web's `remote-wire.ts`: readers refuse another kind and a
 * newer `v`, ignore unknown fields, check every value by its JSON type and rebuild what they keep; an unknown command is ignored,
 * not refused. Both sides pass `remote-vectors.json`. The phone writes commands and reads states; it can read commands too (the
 * vectors check every reader on both sides).
 */
object RemoteWire {
    const val STATE_KIND = "stash-remote-state"
    const val CMD_KIND = "stash-remote-cmd"
    const val VERSION = 1

    /** A socket session id: 8–32 base64url characters. */
    val SID = Regex("^[A-Za-z0-9_-]{8,32}$")

    /** An item id (Remote v2 §4): 1–64 of `A–Z a–z 0–9 _ - . : #`. The cast wrapper's content ids (`<mediaId>#<n>`) fit. */
    val ITEM_ID = Regex("^[A-Za-z0-9_.:#-]{1,64}$")

    /** A device id as the sync server accepts one (sync-v1 §1: 8–64 base64url): a state's `driver`. */
    private val DEVICE = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** A cap name, as a reader keeps it. */
    private val CAP_NAME = Regex("^[A-Za-z0-9_-]{1,32}$")

    /** The cap of a browser that plays a phone's queue. */
    const val CAP_DRIVE = "drive"

    /** `upNext` carries at most this many items (phase 2's window; phase 1 sends one). */
    const val MAX_UP_NEXT = 50

    private const val MAX_CAPS = 16
    private const val MAX_FROM = 300

    /** Where a state is sealed (its sender) and where a command is (its sender and its one receiver), sync-v1 §3.2. */
    fun statePlace(from: String) = "remote:$from"
    fun cmdPlace(from: String, to: String) = "remote-cmd:$from:$to"

    private val json = Json { ignoreUnknownKeys = true }

    fun cmdJson(c: RemoteCmd): String = buildJsonObject {
        put("kind", CMD_KIND)
        put("v", VERSION)
        put("sid", c.sid)
        put("n", c.n)
        put("cmd", c.command.wire)
        when (val x = c.command) {
            is RemoteCommand.Seek -> put("positionMs", x.positionMs.coerceAtLeast(0))
            is RemoteCommand.Volume -> put("volume", x.volume.coerceIn(0.0, 1.0))
            is RemoteCommand.Load -> {
                put("item", itemJson(x.item))
                put("positionMs", x.positionMs.coerceAtLeast(0))
                put("play", x.play)
                x.from?.let { HandoffWire.cleanText(it, MAX_FROM) }?.takeIf { it.isNotEmpty() }?.let { put("from", it) }
            }
            is RemoteCommand.UpNext -> putJsonArray("items") { x.items.take(MAX_UP_NEXT).forEach { add(itemJson(it)) } }
            is RemoteCommand.Jump -> put("id", x.id)
            is RemoteCommand.Release -> put("pause", x.pause)
            RemoteCommand.Play, RemoteCommand.Pause, RemoteCommand.Next, RemoteCommand.Prev, RemoteCommand.Stop -> Unit
        }
    }.toString()

    /** An item as the phone writes it; one with no song (never written by this phone) carries its id only, as the web's. */
    private fun itemJson(i: RemoteItem) = buildJsonObject {
        put("id", i.id)
        i.song?.let { put("song", HandoffWire.songJson(it)) }
    }

    fun readState(text: String): RemoteState {
        val o = root(text, STATE_KIND)
        val playing = o.bool("playing") ?: throw HandoffWireException("bad playing")
        val position = o.long("positionMs")?.takeIf { it >= 0 } ?: throw HandoffWireException("bad positionMs")
        val song = o["song"]?.let(HandoffWire::readSong)
        return RemoteState(
            sid = o.str("sid")!!,
            needsGesture = o.bool("needsGesture") == true,
            playing = playing,
            positionMs = position,
            durationMs = o.long("durationMs")?.takeIf { it > 0 } ?: 0L,
            rate = o.num("rate")?.takeIf { it > 0 && it <= 16 } ?: 1.0,
            volume = o.num("volume")?.coerceIn(0.0, 1.0),
            canSeek = o.bool("canSeek") == true,
            title = song?.title,
            artist = song?.artist,
            // readSong keeps https covers only (the library file's rule).
            art = song?.artwork?.firstOrNull(),
            caps = (o["caps"] as? JsonArray).orEmpty().asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(CAP_NAME::matches) }
                .distinct().take(MAX_CAPS).toList(),
            driver = o.str("driver")?.takeIf(DEVICE::matches),
            iid = o.str("iid")?.takeIf(ITEM_ID::matches),
            next = o.str("next")?.takeIf(ITEM_ID::matches),
            phase = RemotePhase.of(o.str("phase")),
            pendingPlay = o.bool("pendingPlay") == true,
        )
    }

    /** Null for a command this app doesn't know (a newer sender's): ignored, not an error. */
    fun readCmd(text: String): RemoteCmd? {
        val o = root(text, CMD_KIND)
        val n = o.long("n")?.takeIf { it >= 1 } ?: throw HandoffWireException("bad n")
        val cmd = o.str("cmd") ?: throw HandoffWireException("bad cmd")
        val sid = o.str("sid")!!
        val command = when (cmd) {
            "play" -> RemoteCommand.Play
            "pause" -> RemoteCommand.Pause
            "next" -> RemoteCommand.Next
            "prev" -> RemoteCommand.Prev
            "seek" -> RemoteCommand.Seek(o.long("positionMs")?.takeIf { it >= 0 } ?: throw HandoffWireException("bad positionMs"))
            "volume" -> RemoteCommand.Volume((o.num("volume") ?: throw HandoffWireException("bad volume")).coerceIn(0.0, 1.0))
            "load" -> {
                // Refused only for a bad id, position or play; a song that won't read is dropped and the item still taken.
                val item = o["item"] as? JsonObject ?: throw HandoffWireException("bad item")
                val id = item.str("id")?.takeIf(ITEM_ID::matches) ?: throw HandoffWireException("bad item")
                RemoteCommand.Load(
                    item = RemoteItem(id, songOfItem(item["song"])),
                    positionMs = o.long("positionMs")?.takeIf { it >= 0 } ?: throw HandoffWireException("bad positionMs"),
                    play = o.bool("play") ?: throw HandoffWireException("bad play"),
                    from = o.str("from")?.let { HandoffWire.cleanText(it, MAX_FROM) }?.takeIf { it.isNotEmpty() },
                )
            }
            "upNext" -> RemoteCommand.UpNext(readItems(o["items"] as? JsonArray ?: throw HandoffWireException("bad items")))
            "jump" -> RemoteCommand.Jump(o.str("id")?.takeIf(ITEM_ID::matches) ?: throw HandoffWireException("bad id"))
            "stop" -> RemoteCommand.Stop
            "release" -> RemoteCommand.Release(pause = o.bool("pause") != false)
            else -> return null
        }
        return RemoteCmd(sid, n, command)
    }

    /** A handed-over song (sync-v1 §2.1), or null when it won't read or is phone-only: such a song is never taken. */
    private fun songOfItem(e: JsonElement?): WireSong? = HandoffWire.readSong(e)?.takeUnless { it.phoneOnly }

    /**
     * `upNext`'s items, in order, each id once, at most [MAX_UP_NEXT]. One that isn't an object, has no good id, repeats an id
     * or whose song is dropped is left out (its id is claimed all the same); the rest are kept.
     */
    private fun readItems(a: JsonArray): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        val seen = HashSet<String>()
        for (x in a) {
            if (out.size >= MAX_UP_NEXT) break
            val o = x as? JsonObject ?: continue
            val id = o.str("id")?.takeIf(ITEM_ID::matches) ?: continue
            if (!seen.add(id)) continue
            songOfItem(o["song"])?.let { out += RemoteItem(id, it) }
        }
        return out
    }

    private fun root(text: String, kind: String): JsonObject {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw HandoffWireException("not a document")
        if (o.str("kind") != kind) throw HandoffWireException("not a $kind")
        val v = o.long("v") ?: throw HandoffWireException("no version")
        if (v > VERSION) throw HandoffWireException("$kind v$v is newer than this app", newer = true)
        if (v < 1) throw HandoffWireException("bad version")
        if (o.str("sid")?.let(SID::matches) != true) throw HandoffWireException("bad sid")
        return o
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    /** An integer (a JSON number with no fraction), or null. */
    private fun JsonObject.long(k: String): Long? {
        val p = (this[k] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite() && d == Math.floor(d) && kotlin.math.abs(d) < 9.0e15) d.toLong() else null
    }

    private fun JsonObject.num(k: String): Double? =
        (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}
