package com.stash.core.data.weblink

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * sync-v1 crypto on the device's own providers (the unit tests run on the JVM's SunEC): fixture ECDH, Kpair, the pairing code
 * and a golden envelope through Conscrypt (API 26+), and ECDH with an Android Keystore `PURPOSE_AGREE_KEY` key (API 31+), which
 * needs the peer key in its named-curve encoding. Values are from stash-player `src/lib/sync/fixtures/crypto-vectors.json`.
 * Run: `./gradlew :core:data:connectedDebugAndroidTest` on an API 26 emulator and on a phone with API 31+ (spec §13).
 */
@RunWith(AndroidJUnit4::class)
class SyncCryptoAndroidTest {
    private fun b(s: String) = Base64Url.decode(s)

    @Test fun fixturePairingOnThePlatformProviders() {
        val eBPub = b(EB_PUB)
        val ePPub = b(EP_PUB)
        val eB = SyncCrypto.privateKey(b(EB_D))
        val eP = SyncCrypto.privateKey(b(EP_D))
        assertEquals(SHARED, Base64Url.encode(SyncCrypto.ecdh(eP, eBPub)))
        assertEquals(SHARED, Base64Url.encode(SyncCrypto.ecdh(eB, ePPub)))
        val kpair = SyncKeys.pairKey(eP, eBPub, b(PAIR_SECRET), PAIR_ID, eBPub, ePPub, BROWSER_ID, b(BROWSER_PUB))
        assertEquals(KPAIR, Base64Url.encode(kpair))
        assertEquals(SAS, SyncKeys.pairCode(kpair))
    }

    @Test fun fixtureEnvelopeOnThePlatformProviders() {
        assertEquals(ENV_C, Base64Url.encode(SyncCrypto.aesSeal(b(DATA_KEY), b(ENV_N), ENV_AAD, b(ENV_GZ))))
        val json = SyncCrypto.open(b(DATA_KEY), SPACE_ID, ENV_PLACE, SyncEnvelope(1, ENV_N, ENV_C))
        assertTrue(json.startsWith("""{"kind":"stash-now""""))
    }

    @Test fun keystoreAgreeKeyDoesEcdhWithAPeerKey() {
        assumeTrue("PURPOSE_AGREE_KEY needs API 31", Build.VERSION.SDK_INT >= 31)
        val alias = "stash-sync-test"
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
        val device = gen.generateKeyPair()
        try {
            val devicePub = SyncCrypto.rawPublicKey(device.public as ECPublicKey)
            val stored = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as PrivateKey
            // A rotation to this device: the rotator's software key and the Keystore key agree.
            val eph = SyncCrypto.privateKey(b(EB_D))
            assertEquals(Base64Url.encode(SyncCrypto.ecdh(eph, devicePub)), Base64Url.encode(SyncCrypto.ecdh(stored, b(EB_PUB))))
            // And a whole key envelope opens with the Keystore key.
            val prev = SyncCrypto.randomBytes(32)
            val next = SyncCrypto.randomBytes(32)
            val env = SyncKeys.sealKeyFor(next, SPACE_ID, 2, "d_B3mV6cYh1sJd0Ga5", devicePub, listOf("d_B3mV6cYh1sJd0Ga5"), prev)
            val got = SyncKeys.openKeyEnvelope(env, SPACE_ID, "d_B3mV6cYh1sJd0Ga5", stored, devicePub, prev, 1)
            assertEquals(Base64Url.encode(next), Base64Url.encode(got.k))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    private companion object {
        const val EB_D = "TuspnxNzY8OwxqfYYRyhZhfbDRPuKlN2lFV9HzYnpNE"
        const val EB_PUB = "BL19ZBISiIuUtQvGzu6Kjczluya0i7vJCJ7-nBA55TtdHlBODMAuj2TbukoulRgKOEOqQMOcVf33YmlIKdoVbmY"
        const val EP_D = "b0hF5jzuVOGf8K87tYXS6WLJmm6Xwkp4IdAqjvAVg_8"
        const val EP_PUB = "BGfywORAAxSrynIzhFv_nphYCyV-hyl8t165-y3f9pnnLo4uR34g-9FGoXychFLR-X7uWHFUvsgsf-RmjrBl8ok"
        const val SHARED = "Fg1ChQEavS5uTK-yhFTvvKmCEQ4GgLWlZBJxDKF5E88"
        const val KPAIR = "IvzcNnH0bWYfAs458bIsanAeaKisPcJhSrrf0p7jFlQ"
        const val SAS = "232660"
        const val BROWSER_ID = "d_B3mV6cYh1sJd0Ga5"
        const val BROWSER_PUB = "BD8oLUkNslgGNdOBtXtewnhY6nwdZiNsq0rdY1iMoDzMQ23k_WlGxgadiudWqJz9OLTwDyut5AUTjrCITrDcJwU"
        const val PAIR_ID = "xWbdvdFfe2P04fdKEQpCLg"
        const val PAIR_SECRET = "oKGio6SlpqeoqaqrrK2urw"
        const val SPACE_ID = "s_AJNrGM1Yn3oI2sgnUjMtLQ"
        const val DATA_KEY = "0fgr1qfF34wRnapjnplwcetcWI6FGA0fr2Cvn6WLy94"
        const val ENV_PLACE = "now:d_P7x2Lk9QwZr4Tn8M"
        const val ENV_AAD = "stash-sync/1|s_AJNrGM1Yn3oI2sgnUjMtLQ|1|now:d_P7x2Lk9QwZr4Tn8M"
        const val ENV_N = "ERITFBUWFxgZGhsc"
        const val ENV_GZ =
            "H4sIAAAAAAAC_z2OwU7DMBBEfyWaK47kQKCtbyCEQCK0gFqOyE3sxkpqp_a6TVXl35F74LZazcx7F3TGNhAIJEObW3cCwxGiYBh6eTZ2B0E-KobBBUPG2SpAFPcF55zBS1LXrLGNGq_XIaqo3tLi4ffV3tWffFysiv1Xd7s5zcM7lY9gCC7tXkCGegWBlbFddpP9tIYUGKQnEwgCL17aLlvWStr07rdxD4Gn3tkm5Zro5b_SvLwqmeBrCKy_19WseOAzzhdg8EqHBDy7SHGbkHEcnmVe52VVY5oYQhu1TjJa9kGlxqBkcnBag0F7l9AfZtdS1nhzTPxeaVpGgiimP1OVgJBIAQAA"
        const val ENV_C =
            "Dyu01XKk0KVfebSx1pwqELbPLsfRU-Z5MIKexI2iqC-ZFOjx_vAICun80h_Ikxf2WZyfFICylqvH03O5zYMw9Upgp3IlWJOvWeBF4a6mDRbMzsGyuvtncDE6hyCa7YHj_-PN31Cuz3JQ0PqmdtIZpd-RdCukH0SEfAzTyKE974Rib7rYJEjVtGBPKy3GNVzpH2ZYaJz7ycZ8d3-0KphxMPHRRpKU6igt0t_MWXj4aWzIBuLGm__GEP4zX0xiwLHDuzlbYxeiUtw80zHsJ9oKP0divu3mQbGMVdxysQETrRwpatJC6qycJcJxgb8_7yxiC_SLkx4Yk5FXMY1jfTjAV2XJvOImJ74hm9b-p6lvyzWTYySRJsvtDA"
    }
}
