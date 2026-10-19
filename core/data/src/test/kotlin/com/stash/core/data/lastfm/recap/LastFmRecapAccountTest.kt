package com.stash.core.data.lastfm.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class LastFmRecapAccountTest {

    private class Debug(value: String?) : LastFmRecapDebugUser {
        val flow = MutableStateFlow(value)
        override val username: Flow<String?> = flow
    }

    @Test fun `the connected account, with its own session key`() = runTest {
        val account = LastFmRecapAccount(MutableStateFlow(LastFmSession("rawnclark", "sk")), LastFmRecapDebugUser.None)
        assertThat(account.user.first()).isEqualTo(LastFmRecapUser("rawnclark", "sk"))
        assertThat(account.username.first()).isEqualTo("rawnclark")
    }

    @Test fun `not connected - no recap user`() = runTest {
        val account = LastFmRecapAccount(MutableStateFlow<LastFmSession?>(null), LastFmRecapDebugUser.None)
        assertThat(account.user.first()).isNull()
    }

    @Test fun `the debug test user wins, and never carries the session key`() = runTest {
        val debug = Debug("someone")
        val account = LastFmRecapAccount(MutableStateFlow(LastFmSession("rawnclark", "sk")), debug)
        assertThat(account.user.first()).isEqualTo(LastFmRecapUser("someone", null))
        debug.flow.value = null
        assertThat(account.user.first()).isEqualTo(LastFmRecapUser("rawnclark", "sk"))
    }

    @Test fun `the user's string form never prints the session key`() {
        assertThat(LastFmRecapUser("me", "secret-key").toString()).doesNotContain("secret-key")
    }

    @Test fun `freshness - settled two weeks after the end, kept for good once fetched after that`() {
        val endOfSeptember = Instant.parse("2026-10-01T00:00:00Z").epochSecond
        val oct14 = Instant.parse("2026-10-14T23:00:00Z").toEpochMilli()
        val oct15 = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
        assertThat(LastFmRecapFreshness.isSettled(endOfSeptember, oct14)).isFalse()
        assertThat(LastFmRecapFreshness.isSettled(endOfSeptember, oct15)).isTrue()
        val ttl = 600_000L
        // Fetched after settling: fresh a year later.
        assertThat(LastFmRecapFreshness.isFresh(oct15, endOfSeptember, oct15 + 365L * 86_400_000, ttl)).isTrue()
        // Fetched before settling: fresh only within the TTL.
        assertThat(LastFmRecapFreshness.isFresh(oct14, endOfSeptember, oct14 + ttl - 1, ttl)).isTrue()
        assertThat(LastFmRecapFreshness.isFresh(oct14, endOfSeptember, oct14 + ttl, ttl)).isFalse()
    }
}
