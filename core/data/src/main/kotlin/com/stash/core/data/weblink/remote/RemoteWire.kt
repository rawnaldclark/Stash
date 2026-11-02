package com.stash.core.data.weblink.remote

import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.HandoffWireException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What a browser says it plays (sync-v1 §5.7 `stash-remote-state`), as read: the fields the "Playing on Chrome" card shows.
 *
 * @property sid that browser's socket session; every command names it.
 * @property needsGesture the tab was never clicked, so the browser would refuse a remote play ("Click the Stash tab once").
 * @property volume 0–1, or null when that browser can't set its volume (no slider).
 * @property art the first https cover, or null.
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
)

/** A remote command (v1: transport and volume). [wire] is its `cmd` value. */
sealed class RemoteCommand(val wire: String) {
    data object Play : RemoteCommand("play")
    data object Pause : RemoteCommand("pause")
    data object Next : RemoteCommand("next")
    data object Prev : RemoteCommand("prev")
    data class Seek(val positionMs: Long) : RemoteCommand("seek")
    data class Volume(val volume: Double) : RemoteCommand("volume")
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
            else -> Unit
        }
    }.toString()

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
            else -> return null
        }
        return RemoteCmd(sid, n, command)
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
