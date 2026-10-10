package com.stash.core.data.weblink

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.interfaces.ECPublicKey

/**
 * The golden crypto fixtures of sync-v1 (`crypto-vectors.json`, written by stash-player `scripts/sync-fixtures.mjs` with WebCrypto;
 * see [SyncVectors]): exact ciphertexts for fixed keys and nonces, the pairing transcript, a key-rotation envelope.
 */
class SyncCryptoTest {
    private val v = SyncVectors.load("crypto-vectors.json")
    private fun b(e: JsonElement) = Base64Url.decode(e.s)
    private fun b64(x: ByteArray) = Base64Url.encode(x)
    private fun env(e: JsonElement) = SyncEnvelope(e["e"].i, e["n"].s, e["c"].s, if (e.has("p")) e["p"].s else null)
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun hex(x: ByteArray) = x.joinToString("") { "%02x".format(it) }

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

    @Test fun `envelopes - exact ciphertexts, and each opens to its document`() {
        for (e in v["envelopes"].list) {
            val key = b(v["space"][e["key"].s])
            assertWithMessage(e["name"].s).that(SyncCrypto.aad(e["spaceId"].s, e["epoch"].i, e["place"].s)).isEqualTo(e["aad"].s)
            assertWithMessage(e["name"].s).that(b64(SyncCrypto.aesSeal(key, b(e["nonce"]), e["aad"].s, b(e["gz"])))).isEqualTo(e["env"]["c"].s)
            assertWithMessage(e["name"].s).that(SyncCrypto.gunzip(b(e["gz"])).toString(Charsets.UTF_8)).isEqualTo(e["json"].s)
            assertWithMessage(e["name"].s).that(json(SyncCrypto.open(key, e["spaceId"].s, e["place"].s, env(e["env"])))).isEqualTo(json(e["json"].s))
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
    }

    @Test fun `envelopes - seal and open round trip, and the inflate cap`() {
        val key = b(v["space"]["dataKey"])
        val doc = """{"kind":"stash-now","v":1,"playing":false,"positionMs":0}"""
        val sealed = SyncCrypto.seal(key, "s_x", 1, "now:d_P7x2Lk9QwZr4Tn8M", doc)
        assertThat(Base64Url.decode(sealed.n)).hasLength(12)
        assertThat(SyncCrypto.open(key, "s_x", "now:d_P7x2Lk9QwZr4Tn8M", sealed)).isEqualTo(doc)
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.gunzip(SyncCrypto.gzip(ByteArray(200_000)), max = 100_000) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.gunzip("not gzip".toByteArray()) }
    }

    @Test fun `pairing - the QR link, the label, Kpair from both sides, answer and reply`() {
        val p = v["pairing"]
        val eBPub = b(p["browserEphemeral"]["pub"])
        val ePPub = b(p["phoneEphemeral"]["pub"])
        assertThat(SyncCrypto.qrLink(p["pairId"].s, b(p["pairSecret"]), eBPub)).isEqualTo(p["qr"].s)
        val q = SyncCrypto.parseQrLink(p["qr"].s)!!
        assertThat(q.pairId).isEqualTo(p["pairId"].s)
        assertThat(b64(q.pairSecret)).isEqualTo(p["pairSecret"].s)
        assertThat(b64(q.eBPub)).isEqualTo(p["browserEphemeral"]["pub"].s)
        val qr = p["qr"].s
        for (bad in listOf("https://stashfm.app/link#2." + qr.substringAfter("#1."), "https://example.com/link#" + qr.substringAfter('#'), "$qr.x", qr.dropLast(2), "hello")) {
            assertWithMessage(bad).that(SyncCrypto.parseQrLink(bad)).isNull()
        }

        val labelKey = SyncCrypto.pairLabelKey(b(p["pairSecret"]))
        assertThat(b64(labelKey)).isEqualTo(p["labelKey"].s)
        val label = p["label"]
        assertThat(b64(SyncCrypto.aesSeal(labelKey, b(label["nonce"]), label["aad"].s, b(label["gz"])))).isEqualTo(label["env"]["c"].s)
        assertThat(json(SyncCrypto.open(labelKey, "pair", "label", env(label["env"])))).isEqualTo(json(label["json"].s))

        // Fixed ephemeral keys: the JCA's public point and ECDH agree with WebCrypto's.
        val eB = SyncCrypto.privateKey(b(p["browserEphemeral"]["d"]))
        val eP = SyncCrypto.privateKey(b(p["phoneEphemeral"]["d"]))
        assertThat(b64(SyncCrypto.ecdh(eP, eBPub))).isEqualTo(p["shared"].s)
        assertThat(b64(SyncCrypto.ecdh(eB, ePPub))).isEqualTo(p["shared"].s)
        assertThat(SyncCrypto.pairInfo(p["pairId"].s, eBPub, ePPub)).isEqualTo(p["info"].s)
        val kPhone = SyncCrypto.pairKey(eP, eBPub, b(p["pairSecret"]), p["pairId"].s, eBPub, ePPub)
        val kBrowser = SyncCrypto.pairKey(eB, ePPub, b(p["pairSecret"]), p["pairId"].s, eBPub, ePPub)
        assertThat(b64(kPhone)).isEqualTo(p["kpair"].s)
        assertThat(b64(kBrowser)).isEqualTo(p["kpair"].s)

        val answer = p["answer"]
        assertThat(b64(SyncCrypto.aesSeal(kPhone, b(answer["nonce"]), answer["aad"].s, b(answer["gz"])))).isEqualTo(answer["env"]["c"].s)
        val opened = json(SyncCrypto.open(kBrowser, "pair", "answer:${p["pairId"].s}", env(answer["env"])))
        assertThat(opened["space"].jsonObject["k"]!!.jsonPrimitive.content).isEqualTo(v["space"]["k"].s)
        assertThat(json(SyncCrypto.open(kPhone, "pair", "reply:${p["pairId"].s}", env(p["reply"]["env"])))).isEqualTo(json(p["reply"]["json"].s))
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(kBrowser, "pair", "answer:AAAAAAAAAAAAAAAAAAAAAA", env(answer["env"])) }
    }

    @Test fun `key rotation - exact envelope, and only the addressed device opens it`() {
        val r = v["rotation"]
        val devicePub = b(r["device"]["pub"])
        val ephPub = b(r["ephemeral"]["pub"])
        assertThat(SyncCrypto.keyInfo(r["spaceId"].s, r["epoch"].i, r["deviceId"].s, ephPub, devicePub)).isEqualTo(r["info"].s)
        assertThat(b64(SyncCrypto.aesSeal(b(r["key"]), b(r["nonce"]), r["aad"].s, b(r["gz"])))).isEqualTo(r["env"]["c"].s)
        val devicePriv = SyncCrypto.privateKey(b(r["device"]["d"]))
        val k = SyncCrypto.openKeyEnvelope(env(r["env"]), r["spaceId"].s, r["deviceId"].s, devicePriv, devicePub)
        assertThat(b64(k)).isEqualTo(r["newKey"].s)

        // A fresh round trip through this implementation's own gzip, and a wrong device key fails.
        val dev = SyncCrypto.newKeyPair()
        val devRaw = SyncCrypto.rawPublicKey(dev.public as ECPublicKey)
        val sealed = SyncCrypto.sealKeyFor(b(r["newKey"]), r["spaceId"].s, 3, "d_W5nK2pR8tL4qX9zC", devRaw)
        assertThat(b64(SyncCrypto.openKeyEnvelope(sealed, r["spaceId"].s, "d_W5nK2pR8tL4qX9zC", dev.private, devRaw))).isEqualTo(r["newKey"].s)
        val other = SyncCrypto.newKeyPair()
        assertThrows(SyncCryptoException::class.java) {
            SyncCrypto.openKeyEnvelope(sealed, r["spaceId"].s, "d_W5nK2pR8tL4qX9zC", other.private, SyncCrypto.rawPublicKey(other.public as ECPublicKey))
        }
    }

    @Test fun `a public key off the curve is refused`() {
        val pub = b(v["pairing"]["browserEphemeral"]["pub"]).also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.publicKey(pub) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.publicKey(ByteArray(64)) }
    }
}
