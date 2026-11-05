package com.stash.data.cloud.provider.gdrive

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.provider.CloudAuthRequiredException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Silent re-authorization finds an account by its email. A row with none (from a build that allowed
 * it) must not take whichever account Play services answers for while another is connected.
 */
class GoogleDriveAuthAccountsTest {
    private val store = MemoryTokenStore()
    private val authorizer = FakeAuthorizer(next = "new-token")

    private fun tokens(several: Boolean) = CloudTokenManager(
        store,
        mapOf(CloudProvider.GOOGLE_DRIVE to GoogleDriveAuth(authorizer, FakeDrive().client, severalAccounts = { several })),
    ).apply { nowSeconds = { 1_000 } }

    private val key = CloudTokenManager.keyFor(CloudProvider.GOOGLE_DRIVE, 2)

    /** An expired sign-in whose refresh slot holds [slot]. */
    private suspend fun signedInWith(slot: String) = store.save(key, ServiceToken("old-token", slot, 0))

    @Test fun `a row with no email next to another account asks for a Reconnect`() = runTest {
        signedInWith(GoogleDriveAuth.NO_ACCOUNT)
        val tokens = tokens(several = true)

        assertThrows(CloudAuthRequiredException::class.java) {
            kotlinx.coroutines.runBlocking { tokens.accessToken(CloudProvider.GOOGLE_DRIVE, 2) }
        }

        // Play services was never asked, and the card now says Reconnect.
        assertThat(authorizer.silentCalls).isEmpty()
        assertThat(store.map).doesNotContainKey(key)
    }

    @Test fun `a row with no email that's the only account still refreshes`() = runTest {
        signedInWith(GoogleDriveAuth.NO_ACCOUNT)

        assertThat(tokens(several = false).accessToken(CloudProvider.GOOGLE_DRIVE, 2)).isEqualTo("new-token")
    }

    @Test fun `a row with an email refreshes by it, whatever else is connected`() = runTest {
        signedInWith("sam@example.com")

        assertThat(tokens(several = true).accessToken(CloudProvider.GOOGLE_DRIVE, 2)).isEqualTo("new-token")

        assertThat(authorizer.silentCalls.single().first).isEqualTo("sam@example.com")
    }
}
