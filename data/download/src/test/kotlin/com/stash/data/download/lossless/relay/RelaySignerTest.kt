package com.stash.data.download.lossless.relay

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Test

class RelaySignerTest {

    private val config: LosslessConfigFetcher = mockk {
        every { relayKey } returns MutableStateFlow("k-secret")
    }
    private val signer = RelaySigner(config, supporter(), appVersionProvider())

    @Test fun `a cancelled install-id read cancels the signing, not a transient id`() {
        coEvery { config.installId() } throws CancellationException("left the screen")
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { signer.sign(Request.Builder().url("https://relay.example/x"), "quota", 0L) }
        }
    }

    @Test fun `a failing install-id store still signs with a transient id`() = runTest {
        coEvery { config.installId() } throws java.io.IOException("datastore unavailable")
        val b = Request.Builder().url("https://relay.example/x")
        assertThat(signer.sign(b, "quota", 0L)).isTrue()
        assertThat(b.build().header("X-Stash-Install")).isNotEmpty()
    }

    @Test fun `adds the running version code as the app build header`() {
        val b = Request.Builder().url("https://relay.example/x")
        signer.appBuild(b)
        assertThat(b.build().header("X-Stash-App-Build")).isEqualTo("157")
    }
}
