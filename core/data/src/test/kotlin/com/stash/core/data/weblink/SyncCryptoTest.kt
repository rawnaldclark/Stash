package com.stash.core.data.weblink

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/**
 * The golden crypto fixtures of sync-v1 (`crypto-vectors.json`, written by stash-player `scripts/sync-fixtures.mjs` with WebCrypto;
 * see [SyncVectors]): exact ciphertexts for fixed keys and nonces, the pairing transcript and code, a proven key-rotation
 * envelope. Readers are pinned by the fixture's ciphertexts; writers by opening what they write with the fixture's keys and AADs.
 */
class SyncCryptoTest {
    private val v = SyncVectors.load("crypto-vectors.json")
    private fun b(e: JsonElement) = Base64Url.decode(e.s)
    private fun b64(x: ByteArray) = Base64Url.encode(x)
    private fun env(e: JsonElement) = SyncEnvelope(e["e"].i, e["n"].s, e["c"].s, if (e.has("p")) e["p"].s else null)
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun hex(x: ByteArray) = x.joinToString("") { "%02x".format(it) }

    /** Opens what a writer produced with the FIXTURE's key and AAD only: pins its key derivation and AAD, whatever its gzip. */
    private fun rawOpen(key: ByteArray, aad: String, e: SyncEnvelope) =
        json(SyncCrypto.gunzip(SyncCrypto.aesOpen(key, Base64Url.decode(e.n), aad, Base64Url.decode(e.c))).toString(Charsets.UTF_8))

    @Test fun `base64url - valid and refused`() {
        for (c in v["b64u"]["valid"].list) {
            val bytes = c["hex"].s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertThat(b64(bytes)).isEqualTo(c["b64u"].s)
            assertThat(hex(Base64Url.decode(c["b64u"].s))).isEqualTo(c["hex"].s)
        }
        for (s in v["b64u"]["invalid"].list) {
            assertThrows(s.s, IllegalArgumentException::class.java) { Base64Url.decode(s.s) }
        }
    }

    @Test fun `primitives - HKDF (RFC 5869, incl the empty salt), token hash, data keys`() {
        for (h in v["hkdf"].list) assertThat(b64(SyncCrypto.hkdf(b(h["ikm"]), b(h["salt"]), b(h["info"]), h["length"].i))).isEqualTo(h["okm"].s)
        val t = v["tokenHash"]
        assertThat(SyncCrypto.tokenHash(b(t["token"]))).isEqualTo(t["hash"].s)
        assertThat(SyncCrypto.authHeader("d_P7x2Lk9QwZr4Tn8M", b(t["token"]))).isEqualTo(t["header"].s)
        val sp = v["space"]
        assertThat(b64(SyncCrypto.dataKey(b(sp["k"]), sp["spaceId"].s))).isEqualTo(sp["dataKey"].s)
        assertThat(b64(SyncCrypto.dataKey(b(sp["k2"]), sp["spaceId"].s))).isEqualTo(sp["dataKey2"].s)
    }

    @Test fun `gunzip - one member only, no trailing bytes, and the inflate cap`() {
        val one = SyncCrypto.gzip("""{"a":1}""".toByteArray())
        assertThat(SyncCrypto.gunzip(one).toString(Charsets.UTF_8)).isEqualTo("""{"a":1}""")
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.gunzip(one + SyncCrypto.gzip("{}".toByteArray())) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.gunzip(one + byteArrayOf(0, 1, 2)) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.gunzip(SyncCrypto.gzip(ByteArray(200_000)), max = 100_000) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.gunzip("not gzip".toByteArray()) }
    }

    @Test fun `envelopes - exact ciphertexts, and each opens to its document (one written with the web's gzip, one with the app's)`() {
        for (e in v["envelopes"].list) {
            val key = b(v["space"][e["key"].s])
            assertWithMessage(e["name"].s).that(SyncCrypto.aad(e["spaceId"].s, e["epoch"].i, e["place"].s)).isEqualTo(e["aad"].s)
            assertWithMessage(e["name"].s).that(b64(SyncCrypto.aesSeal(key, b(e["nonce"]), e["aad"].s, b(e["gz"])))).isEqualTo(e["env"]["c"].s)
            assertWithMessage(e["name"].s).that(SyncCrypto.gunzip(b(e["gz"])).toString(Charsets.UTF_8)).isEqualTo(e["json"].s)
            assertWithMessage(e["name"].s).that(json(SyncCrypto.open(key, e["spaceId"].s, e["place"].s, env(e["env"])))).isEqualTo(json(e["json"].s))
        }
    }

    @Test fun `envelopes - the writer, with the fixture nonce, opens with the fixture key and AAD alone`() {
        for (e in v["envelopes"].list) {
            val key = b(v["space"][e["key"].s])
            val w = SyncCrypto.sealWithNonce(key, e["spaceId"].s, e["epoch"].i, e["place"].s, e["json"].s, b(e["nonce"]))
            assertThat(w.n).isEqualTo(e["env"]["n"].s)
            assertWithMessage(e["name"].s).that(rawOpen(key, e["aad"].s, w)).isEqualTo(json(e["json"].s))
        }
    }

    @Test fun `envelopes - a blob moved to another place, epoch or space, or altered, does not open`() {
        val e = v["envelopes"].list[0]
        val key = b(v["space"]["dataKey"])
        val ok = env(e["env"])
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(key, e["spaceId"].s, "now:d_B3mV6cYh1sJd0Ga5", ok) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(key, e["spaceId"].s, e["place"].s, ok.copy(e = 2)) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(key, "s_AAAAAAAAAAAAAAAAAAAAAA", e["place"].s, ok) }
        val c = Base64Url.decode(ok.c).also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(key, e["spaceId"].s, e["place"].s, ok.copy(c = b64(c))) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(key, e["spaceId"].s, e["place"].s, ok.copy(n = ok.n + "=")) }
        val doc = """{"kind":"stash-now","v":1,"playing":false,"positionMs":0}"""
        val sealed = SyncCrypto.seal(key, "s_x", 1, "now:d_P7x2Lk9QwZr4Tn8M", doc)
        assertThat(Base64Url.decode(sealed.n)).hasLength(12)
        assertThat(SyncCrypto.open(key, "s_x", "now:d_P7x2Lk9QwZr4Tn8M", sealed)).isEqualTo(doc)
    }

    @Test fun `a document in parts - exact slices, the writer pinned, parts open in order only`() {
        val p = v["parts"]
        val key = b(v["space"][p["key"].s])
        val gz = b(p["gz"])
        val size = p["partBytes"].i
        val placeOf = { part: Int, count: Int -> "snapshot:812:$part/$count" }
        val parts = p["parts"].list
        parts.forEachIndexed { i, part ->
            assertThat(part["place"].s).isEqualTo(placeOf(i, parts.size))
            assertThat(b64(gz.copyOfRange(i * size, minOf((i + 1) * size, gz.size)))).isEqualTo(part["slice"].s)
            assertThat(b64(SyncCrypto.aesSeal(key, b(part["nonce"]), part["aad"].s, b(part["slice"])))).isEqualTo(part["env"]["c"].s)
        }
        val envs = parts.map { env(it["env"]) }
        assertThat(json(SyncCrypto.openParts(key, p["spaceId"].s, placeOf, envs))).isEqualTo(json(p["json"].s))
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.openParts(key, p["spaceId"].s, placeOf, listOf(envs[1], envs[0]) + envs.drop(2)) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.openParts(key, p["spaceId"].s, placeOf, envs.dropLast(1)) }

        val written = SyncCrypto.sealPartsWith(key, p["spaceId"].s, 1, placeOf, p["json"].s, 100) { b(parts[it % parts.size]["nonce"]) }
        assertThat(written.size).isGreaterThan(1)
        val slices = ByteArrayOutputStream()
        written.forEachIndexed { i, w -> slices.write(SyncCrypto.aesOpen(key, Base64Url.decode(w.n), SyncCrypto.aad(p["spaceId"].s, 1, placeOf(i, written.size)), Base64Url.decode(w.c))) }
        assertThat(json(SyncCrypto.gunzip(slices.toByteArray()).toString(Charsets.UTF_8))).isEqualTo(json(p["json"].s))
        assertThat(SyncCrypto.sealParts(key, p["spaceId"].s, 1, placeOf, p["json"].s)).hasSize(1)
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.sealParts(key, p["spaceId"].s, 1, placeOf, p["json"].s, partBytes = 10) }
    }

    @Test fun `pairing - the QR link and both labels`() {
        val p = v["pairing"]
        val eBPub = b(p["browserEphemeral"]["pub"])
        assertThat(SyncKeys.qrLink(p["pairId"].s, b(p["pairSecret"]), eBPub)).isEqualTo(p["qr"].s)
        val q = SyncKeys.parseQrLink(p["qr"].s)!!
        assertThat(q.pairId).isEqualTo(p["pairId"].s)
        assertThat(b64(q.pairSecret)).isEqualTo(p["pairSecret"].s)
        assertThat(b64(q.eBPub)).isEqualTo(p["browserEphemeral"]["pub"].s)
        val qr = p["qr"].s
        for (bad in listOf("https://stashfm.app/link#2." + qr.substringAfter("#1."), "https://example.com/link#" + qr.substringAfter('#'), "$qr.x", qr.dropLast(2), "hello")) {
            assertWithMessage(bad).that(SyncKeys.parseQrLink(bad)).isNull()
        }

        val labelKey = SyncKeys.pairLabelKey(b(p["pairSecret"]))
        assertThat(b64(labelKey)).isEqualTo(p["labelKey"].s)
        for ((l, id, pub) in listOf(Triple(p["label"], "d_B3mV6cYh1sJd0Ga5", p["browserDevicePub"].s), Triple(p["phoneLabel"], "d_P7x2Lk9QwZr4Tn8M", p["phoneDevicePub"].s))) {
            assertThat(l["place"].s).isEqualTo("label:$id")
            assertThat(b64(SyncCrypto.aesSeal(labelKey, b(l["nonce"]), l["aad"].s, b(l["gz"])))).isEqualTo(l["env"]["c"].s)
            val label = SyncKeys.readLabel(SyncCrypto.open(labelKey, "pair", l["place"].s, env(l["env"])), id)
            assertThat(b64(label.pub)).isEqualTo(pub)
            assertThat(json(SyncKeys.labelJson(label))).isEqualTo(json(l["json"].s))
            assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(labelKey, "pair", "label:d_W5nK2pR8tL4qX9zC", env(l["env"])) }
            assertThrows(SyncCryptoException::class.java) { SyncKeys.readLabel(l["json"].s, "d_W5nK2pR8tL4qX9zC") }
        }
    }

    @Test fun `pairing - Kpair and the code from both sides, answer and reply read`() {
        val p = v["pairing"]
        val eBPub = b(p["browserEphemeral"]["pub"])
        val ePPub = b(p["phoneEphemeral"]["pub"])
        val eB = SyncCrypto.privateKey(b(p["browserEphemeral"]["d"]))
        val eP = SyncCrypto.privateKey(b(p["phoneEphemeral"]["d"]))
        assertThat(b64(SyncCrypto.ecdh(eP, eBPub))).isEqualTo(p["shared"].s)
        assertThat(b64(SyncCrypto.ecdh(eB, ePPub))).isEqualTo(p["shared"].s)
        assertThat(SyncKeys.pairInfo(p["pairId"].s, eBPub, ePPub)).isEqualTo(p["info"].s)
        val kPhone = SyncKeys.pairKey(eP, eBPub, b(p["pairSecret"]), p["pairId"].s, eBPub, ePPub)
        val kBrowser = SyncKeys.pairKey(eB, ePPub, b(p["pairSecret"]), p["pairId"].s, eBPub, ePPub)
        assertThat(b64(kPhone)).isEqualTo(p["kpair"].s)
        assertThat(b64(kBrowser)).isEqualTo(p["kpair"].s)
        assertThat(SyncKeys.pairCode(kPhone)).isEqualTo(p["sas"].s)
        assertThat(SyncKeys.pairCode(ByteArray(32))).isNotEqualTo(p["sas"].s)

        val answer = p["answer"]
        assertThat(b64(SyncCrypto.aesSeal(kPhone, b(answer["nonce"]), answer["aad"].s, b(answer["gz"])))).isEqualTo(answer["env"]["c"].s)
        val read = SyncKeys.readPairAnswer(SyncCrypto.open(kBrowser, "pair", "answer:${p["pairId"].s}", env(answer["env"])))
        assertThat(read.label.name).isEqualTo("Pixel 6")
        assertThat(b64(read.label.pub)).isEqualTo(p["phoneDevicePub"].s)
        assertThat(read.space!!.id).isEqualTo(v["space"]["spaceId"].s)
        assertThat(b64(read.space!!.k)).isEqualTo(v["space"]["k"].s)
        assertThat(SyncKeys.readPairReply(SyncCrypto.open(kPhone, "pair", "reply:${p["pairId"].s}", env(p["reply"]["env"]))).epoch).isEqualTo(1)
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(kBrowser, "pair", "answer:AAAAAAAAAAAAAAAAAAAAAA", env(answer["env"])) }
    }

    @Test fun `pairing - a granted space is checked - id form, key length, epoch`() {
        val ok = json("""{"id":"${v["space"]["spaceId"].s}","k":"${v["space"]["k"].s}","epoch":1}""").jsonObject
        assertThat(SyncKeys.readSpaceGrant(ok).epoch).isEqualTo(1)
        fun with(k: String, value: JsonElement) = JsonObject(ok + (k to value))
        for (bad in listOf(
            with("id", JsonPrimitive("x_" + ok["id"]!!.s.drop(2))),
            with("id", JsonPrimitive(ok["id"]!!.s + "A")),
            with("k", JsonPrimitive(b64(ByteArray(31)))),
            with("epoch", JsonPrimitive(0)),
            with("epoch", JsonPrimitive("1")),
            with("k", JsonPrimitive(7)),
        )) {
            assertThrows(bad.toString(), SyncCryptoException::class.java) { SyncKeys.readSpaceGrant(bad) }
        }
    }

    private val r = v["rotation"]
    private fun open(e: SyncEnvelope, prevKey: ByteArray = b(r["prevKey"]), prevEpoch: Int = r["prevEpoch"].i) =
        SyncKeys.openKeyEnvelope(e, r["spaceId"].s, r["deviceId"].s, SyncCrypto.privateKey(b(r["device"]["d"])), b(r["device"]["pub"]), prevKey, prevEpoch)

    @Test fun `key rotation - proof key and input, exact envelope, the device opens it`() {
        assertThat(SyncKeys.keyInfo(r["spaceId"].s, r["epoch"].i, r["deviceId"].s, b(r["ephemeral"]["pub"]), b(r["device"]["pub"]))).isEqualTo(r["info"].s)
        assertThat(b64(SyncKeys.rotateKey(b(r["prevKey"]), r["spaceId"].s, r["prevEpoch"].i))).isEqualTo(r["proofKey"].s)
        val members = r["members"].list.map { it.s }
        assertThat(SyncKeys.proofInput(r["spaceId"].s, r["epoch"].i, r["deviceId"].s, b(r["newKey"]), members)).isEqualTo(r["proofInput"].s)
        assertThat(b64(SyncCrypto.aesSeal(b(r["key"]), b(r["nonce"]), r["aad"].s, b(r["gz"])))).isEqualTo(r["env"]["c"].s)
        val got = open(env(r["env"]))
        assertThat(b64(got.k)).isEqualTo(r["newKey"].s)
        assertThat(got.members).isEqualTo(members)
    }

    @Test fun `key rotation - the writer, with the fixture ephemeral key and nonce, writes the fixture document`() {
        val eph = KeyPair(SyncCrypto.publicKey(b(r["ephemeral"]["pub"])), SyncCrypto.privateKey(b(r["ephemeral"]["d"])))
        val w = SyncKeys.sealKeyWith(
            b(r["newKey"]), r["spaceId"].s, r["epoch"].i, r["deviceId"].s, b(r["device"]["pub"]),
            r["members"].list.map { it.s }.reversed(), b(r["prevKey"]), eph, b(r["nonce"]),
        )
        assertThat(w.p).isEqualTo(r["ephemeral"]["pub"].s)
        assertThat(rawOpen(b(r["key"]), r["aad"].s, w)).isEqualTo(json(r["json"].s))
    }

    @Test fun `key rotation - refused - a proof under another key, the wrong epoch, a dropped member, another device`() {
        val members = r["members"].list.map { it.s }
        val forged = SyncKeys.sealKeyFor(b(r["newKey"]), r["spaceId"].s, 2, r["deviceId"].s, b(r["device"]["pub"]), members, ByteArray(32))
        assertThrows(SyncCryptoException::class.java) { open(forged) }
        assertThrows(SyncCryptoException::class.java) { open(env(r["env"]), prevEpoch = 2) }
        assertThrows(SyncCryptoException::class.java) { open(env(r["env"]), prevKey = b(v["space"]["k2"])) }

        // The fixture document with another member list, sealed properly to the device: the proof no longer matches.
        val eph = SyncCrypto.newKeyPair()
        val ephPub = SyncCrypto.rawPublicKey(eph.public as ECPublicKey)
        val k = SyncCrypto.hkdf(
            SyncCrypto.ecdh(eph.private, b(r["device"]["pub"])), r["spaceId"].s.toByteArray(),
            SyncKeys.keyInfo(r["spaceId"].s, 2, r["deviceId"].s, ephPub, b(r["device"]["pub"])).toByteArray(),
        )
        val doc = JsonObject(json(r["json"].s).jsonObject + ("members" to json("""["${r["deviceId"].s}"]""")))
        val tampered = SyncCrypto.seal(k, r["spaceId"].s, 2, "key:${r["deviceId"].s}", doc.toString()).copy(p = b64(ephPub))
        assertThrows(SyncCryptoException::class.java) { open(tampered) }

        val dev = SyncCrypto.newKeyPair()
        val devPub = SyncCrypto.rawPublicKey(dev.public as ECPublicKey)
        val prev = SyncCrypto.randomBytes(32)
        val id = "d_W5nK2pR8tL4qX9zC"
        val sealed = SyncKeys.sealKeyFor(b(r["newKey"]), r["spaceId"].s, 3, id, devPub, listOf(id, "d_P7x2Lk9QwZr4Tn8M"), prev)
        assertThat(b64(SyncKeys.openKeyEnvelope(sealed, r["spaceId"].s, id, dev.private, devPub, prev, 2).k)).isEqualTo(r["newKey"].s)
        val other = SyncCrypto.newKeyPair()
        assertThrows(SyncCryptoException::class.java) {
            SyncKeys.openKeyEnvelope(sealed, r["spaceId"].s, id, other.private, SyncCrypto.rawPublicKey(other.public as ECPublicKey), prev, 2)
        }
        assertThrows(SyncCryptoException::class.java) { SyncKeys.sealKeyFor(b(r["newKey"]), r["spaceId"].s, 3, id, devPub, listOf("d_P7x2Lk9QwZr4Tn8M"), prev) }
    }

    @Test fun `a public key off the curve is refused, and keys round-trip through the named-curve encoding`() {
        val good = b(v["pairing"]["browserEphemeral"]["pub"])
        assertThat(SyncCrypto.rawPublicKey(SyncCrypto.publicKey(good))).isEqualTo(good)
        assertThat(SyncCrypto.publicKey(good).params.curve).isEqualTo((SyncCrypto.newKeyPair().public as ECPublicKey).params.curve)
        val pub = good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.publicKey(pub) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.publicKey(ByteArray(64)) }
    }
}
