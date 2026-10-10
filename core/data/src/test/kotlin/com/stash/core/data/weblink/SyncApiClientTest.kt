package com.stash.core.data.weblink

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

/** The sync client's wire handling: paths, the device header, bodies, and every error shape the Worker answers with. */
class SyncApiClientTest {
    private val web = MockWebServer().apply { start() }
    private val client = SyncApiClient(OkHttpClient(), WebLinkConfig(web.url("/").toString().trimEnd('/'), enabled = true))
    private val auth = DeviceAuth("d_P7x2Lk9QwZr4Tn8M", ByteArray(32) { it.toByte() })
    private val env = SyncEnvelope(1, "AQIDBAUGBwgJCgsM", "c2VhbGVkLWJ5dGVzLWhlcmU")

    @After fun stop() = web.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Test fun `a space is read with the device header, ids in the path, unknown fields ignored`() = runTest {
        web.enqueue(
            json(
                200,
                """{"spaceId":"s_AJNrGM1Yn3oI2sgnUjMtLQ","me":"d_P7x2Lk9QwZr4Tn8M","epoch":2,"rotationDue":true,"compactDue":false,"head":0,"snapshot":null,
                   "devices":[{"id":"d_P7x2Lk9QwZr4Tn8M","type":"phone","pub":"p","labelCt":{"e":2,"n":"n","c":"c"},"addedAt":1,"lastSeenAt":2,"extra":1}],
                   "serverTime":1760083200000,"future":"x"}""",
            ),
        )
        val r = client.space(auth, "s_AJNrGM1Yn3oI2sgnUjMtLQ") as SyncResult.Ok
        assertThat(r.value.epoch).isEqualTo(2)
        assertThat(r.value.rotationDue).isTrue()
        assertThat(r.value.devices.single().labelCt).isEqualTo(SyncEnvelope(2, "n", "c"))
        val req = web.takeRequest()
        assertThat(req.path).isEqualTo("/v1/spaces/s_AJNrGM1Yn3oI2sgnUjMtLQ")
        assertThat(req.getHeader("Authorization")).isEqualTo("Stash-Device d_P7x2Lk9QwZr4Tn8M:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    }

    @Test fun `401 revoked is its own case`() = runTest {
        web.enqueue(json(401, """{"error":{"code":"revoked","message":"Not in this space"}}"""))
        val r = client.space(auth, "s_x") as SyncResult.Error
        assertThat(r.revoked).isTrue()
        assertThat(r.message).isEqualTo("Not in this space")
        web.enqueue(json(401, """{"error":{"code":"unauthorized","message":"No device"}}"""))
        assertThat((client.space(auth, "s_x") as SyncResult.Error).revoked).isFalse()
    }

    @Test fun `409 epoch carries the current epoch`() = runTest {
        web.enqueue(json(409, """{"error":{"code":"epoch","message":"The key changed"},"epoch":4}"""))
        val r = client.join(auth, "s_x", JoinBody("p".repeat(22), 3, env)) as SyncResult.Error
        assertThat(r.code).isEqualTo(SyncErrorCode.EPOCH)
        assertThat(r.epoch).isEqualTo(4)
    }

    @Test fun `409 devices_changed, no_reply, rotation_due and compact keep their codes`() = runTest {
        for (code in listOf(SyncErrorCode.DEVICES_CHANGED, SyncErrorCode.NO_REPLY, SyncErrorCode.ROTATION_DUE, SyncErrorCode.COMPACT)) {
            web.enqueue(json(409, """{"error":{"code":"$code","message":"m"}}"""))
            val r = client.rotate(auth, "s_x", RotateBody(2, mapOf("d_a" to env))) as SyncResult.Error
            assertThat(r.status).isEqualTo(409)
            assertThat(r.code).isEqualTo(code)
        }
    }

    @Test fun `429 says when to try again`() = runTest {
        web.enqueue(json(429, """{"error":{"code":"rate_limited","message":"Slow down"}}""").setHeader("Retry-After", "17"))
        val r = client.pairLabel("p".repeat(22)) as SyncResult.Error
        assertThat(r.retryAfterSeconds).isEqualTo(17)
        assertThat(r.userMessage()).isEqualTo(WebLinkCopy.SLOW_DOWN)
    }

    @Test fun `an error page that isn't the Worker's is code http`() = runTest {
        web.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val r = client.space(auth, "s_x") as SyncResult.Error
        assertThat(r.code).isEqualTo(SyncErrorCode.HTTP)
        assertThat(r.userMessage()).isEqualTo(WebLinkCopy.OFFLINE)
    }

    @Test fun `a 2xx that isn't JSON (a captive portal) is unreachable, not a crash`() = runTest {
        web.enqueue(MockResponse().setResponseCode(200).setBody("<html>Sign in to Wi-Fi</html>"))
        assertThat(client.space(auth, "s_x")).isInstanceOf(SyncResult.Unreachable::class.java)
    }

    @Test fun `the reply long-poll - 204 is pending, 200 is the reply, both with the phone's token`() = runTest {
        web.enqueue(MockResponse().setResponseCode(204))
        web.enqueue(json(200, """{"ct":{"e":0,"n":"n","c":"c"}}"""))
        assertThat(client.pairReply("p".repeat(22), auth)).isEqualTo(SyncResult.Ok(null))
        assertThat(client.pairReply("p".repeat(22), auth)).isEqualTo(SyncResult.Ok(PairReplyInfo(SyncEnvelope(0, "n", "c"))))
        assertThat(web.takeRequest().path).isEqualTo("/v1/pair/${"p".repeat(22)}/reply")
        assertThat(web.takeRequest().getHeader("Authorization")).startsWith("Stash-Device d_P7x2Lk9QwZr4Tn8M:")
    }

    @Test fun `the answer is posted as JSON without null fields, and without a device header`() = runTest {
        web.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        val body = PairAnswerBody("pub", env, DeviceRecord("d_P7x2Lk9QwZr4Tn8M", "hash", "pub", env))
        assertThat(client.pairAnswer("p".repeat(22), body)).isEqualTo(SyncResult.Ok(Unit))
        val req = web.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.getHeader("Authorization")).isNull()
        val sent = req.body.readUtf8()
        assertThat(sent).contains(""""phonePub":"pub"""")
        assertThat(sent).doesNotContain("null")
        assertThat(sent).doesNotContain(""""p":""")
    }

    @Test fun `no config is Ok(null), remove and unlink are plain deletes`() = runTest {
        web.enqueue(MockResponse().setResponseCode(204))
        assertThat(client.config(auth, "s_x")).isEqualTo(SyncResult.Ok(null))
        web.enqueue(MockResponse().setResponseCode(204))
        assertThat(client.removeDevice(auth, "s_x", "d_b")).isEqualTo(SyncResult.Ok(Unit))
        web.enqueue(MockResponse().setResponseCode(204))
        assertThat(client.deleteSpace(auth, "s_x")).isEqualTo(SyncResult.Ok(Unit))
        web.takeRequest()
        assertThat(web.takeRequest().let { it.method + " " + it.path }).isEqualTo("DELETE /v1/spaces/s_x/devices/d_b")
        assertThat(web.takeRequest().let { it.method + " " + it.path }).isEqualTo("DELETE /v1/spaces/s_x")
    }

    @Test fun `a Worker that can't be reached is Unreachable`() = runTest {
        web.shutdown()
        assertThat(client.space(auth, "s_x")).isInstanceOf(SyncResult.Unreachable::class.java)
    }

    @Test fun `the config knows a plain-http Worker`() {
        assertThat(WebLinkConfig("http://192.168.137.1:8795", true).cleartext).isTrue()
        assertThat(WebLinkConfig("https://sync.stashfm.app", false).cleartext).isFalse()
    }
}
