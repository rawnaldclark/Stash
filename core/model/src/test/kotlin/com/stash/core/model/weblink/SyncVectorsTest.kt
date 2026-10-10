package com.stash.core.model.weblink

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.security.MessageDigest

/**
 * The shared identity and clock vectors of sync-v1 (stash-player `docs/sync-v1.md` "Test vectors"). The files in
 * `src/test/resources/sync/` are byte-for-byte copies of stash-player `src/lib/sync/fixtures/`; both repos pin the same SHA-256
 * (stash-player `src/lib/sync/vectors.test.ts`). Change both repos and both pins together, or neither.
 */
class SyncVectorsTest {
    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/sync/$name")) { "missing test resource sync/$name" }.use { it.readBytes() }

    private fun load(name: String): JsonElement = Json.parseToJsonElement(bytes(name).toString(Charsets.UTF_8))

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val JsonElement.s get() = jsonPrimitive.content
    private val JsonElement.sOrNull get() = if (this is JsonNull) null else jsonPrimitive.content
    private operator fun JsonElement.get(k: String): JsonElement = jsonObject[k] ?: JsonNull
    private fun JsonObject.opt(k: String): JsonElement? = this[k]?.takeUnless { it is JsonNull }
    private val JsonElement.list: JsonArray get() = jsonArray

    private fun song(o: JsonElement) = SongRef(o["title"].s, o["artist"].s, o.jsonObject.opt("isrc")?.s, o.jsonObject.opt("key")?.s)

    private fun hlc(e: JsonElement): Hlc? = if (e is JsonNull) null else e.list.let { Hlc(it[0].jsonPrimitive.long, it[1].jsonPrimitive.int, it[2].s) }

    @Test fun `the vector files are the pinned ones`() {
        assertThat(sha256(bytes("identity-vectors.json"))).isEqualTo(IDENTITY_SHA256)
        assertThat(sha256(bytes("clock-vectors.json"))).isEqualTo(CLOCK_SHA256)
    }

    @Test fun `identity - fold`() {
        for (c in load("identity-vectors.json")["fold"].list) {
            assertWithMessage(c["note"].s).that(SongKey.fold(c["in"].s)).isEqualTo(c["out"].s)
        }
    }

    @Test fun `identity - descriptorKey and textKey`() {
        for (c in load("identity-vectors.json")["keys"].list) {
            val isrc = c.jsonObject.opt("isrc")?.s
            assertWithMessage(c["note"].s).that(SongKey.descriptorKey(c["title"].s, c["artist"].s, isrc)).isEqualTo(c["key"].s)
            assertWithMessage(c["note"].s).that(SongKey.textKey(c["title"].s, c["artist"].s)).isEqualTo(c["textKey"].s)
        }
    }

    @Test fun `identity - sameSong and SongIndex`() {
        val v = load("identity-vectors.json")
        val songs = v["songs"].jsonObject.mapValues { song(it.value) }
        for (c in v["sameSong"].list) {
            assertWithMessage(c["note"].s).that(SongKey.sameSong(songs.getValue(c["a"].s), songs.getValue(c["b"].s))).isEqualTo(c["same"].jsonPrimitive.boolean)
        }
        for (c in v["songIndex"].list) {
            val index = SongIndex.of(c["entries"].list.map { songs.getValue(it.s) to it.s })
            for (f in c["find"].list) {
                assertWithMessage("${c["entries"]} ∋ ${f["q"].s}").that(index.find(songs.getValue(f["q"].s))).isEqualTo(f["v"].sOrNull)
            }
        }
    }

    @Test fun `clock - compare, tick, receive`() {
        val v = load("clock-vectors.json")
        for (c in v["compare"].list) {
            assertWithMessage(c.toString()).that(Integer.signum(hlc(c["a"])!!.compareTo(hlc(c["b"])!!))).isEqualTo(c["cmp"].jsonPrimitive.int)
        }
        for (c in v["tick"].list) {
            assertWithMessage(c["name"].s).that(Hlc.tick(hlc(c["last"]), c["wall"].jsonPrimitive.long, c["device"].s)).isEqualTo(hlc(c["expect"]))
        }
        for (c in v["recv"].list) {
            assertWithMessage(c["name"].s)
                .that(Hlc.recv(hlc(c["last"]), hlc(c["remote"])!!, c["wall"].jsonPrimitive.long, c["device"].s))
                .isEqualTo(hlc(c["expect"]))
        }
    }

    private fun samples(e: JsonElement) = e.list.map {
        ClockOffset.Sample(it["sent"].jsonPrimitive.long, it["received"].jsonPrimitive.long, it["serverTime"].jsonPrimitive.long)
    }

    @Test fun `clock - offset and skew`() {
        val v = load("clock-vectors.json")
        for (c in v["offset"].list) assertWithMessage(c["name"].s).that(ClockOffset.of(samples(c["samples"]))).isEqualTo(c["expect"].jsonPrimitive.long)
        for (c in v["skew"].list) {
            fun stamp(d: JsonElement, corrected: Boolean) =
                Hlc.tick(null, d["local"].jsonPrimitive.long + if (corrected) ClockOffset.of(samples(d["samples"])) else 0L, d["device"].s)
            fun newer(corrected: Boolean) = if (stamp(c["a"], corrected) > stamp(c["b"], corrected)) "a" else "b"
            assertWithMessage(c["name"].s).that(newer(true)).isEqualTo(c["newer"].s)
            assertWithMessage(c["name"].s).that(newer(false)).isEqualTo(c["naiveNewer"].s)
        }
    }

    @Test fun `clock - handoff extrapolation and offer`() {
        val v = load("clock-vectors.json")
        for (c in v["extrapolate"].list) {
            val st = c["state"].jsonObject
            val got = HandoffTiming.extrapolate(
                playing = st["playing"]!!.jsonPrimitive.boolean,
                positionMs = st["positionMs"]!!.jsonPrimitive.long,
                rate = st.opt("rate")?.jsonPrimitive?.double ?: 1.0,
                durationMs = st.opt("durationMs")?.jsonPrimitive?.long,
                serverAt = c["serverAt"].jsonPrimitive.long,
                serverTime = c["serverTime"].jsonPrimitive.long,
            )
            assertWithMessage(c["name"].s).that(got).isEqualTo(c["expect"].jsonPrimitive.long)
        }
        for (c in v["offer"].list) {
            val got = HandoffTiming.pickOffer(
                states = c["states"].list.map { HandoffTiming.Candidate(it["device"].s, it["serverAt"].jsonPrimitive.long, it["hasSong"].jsonPrimitive.boolean) },
                me = c["me"].s,
                serverTime = c["serverTime"].jsonPrimitive.long,
                ownLastAt = c["ownLastAt"].jsonPrimitive.long,
                playingHere = c["playingHere"].jsonPrimitive.boolean,
                dismissed = c["dismissed"].list.map { it.s },
            )
            assertWithMessage(c["name"].s).that(got).isEqualTo(c["expect"].sOrNull)
        }
    }

    companion object {
        const val IDENTITY_SHA256 = "d1e17c733a5725233f9bf657be3205ecccfca0428dcb722d611ca7a10a7315c0"
        const val CLOCK_SHA256 = "d257b1e742f14ef9fcc53680843e84f264a9bd7447de3ed1a6a81091d516a345"
    }
}
