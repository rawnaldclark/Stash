package com.stash.core.auth.store

import com.stash.core.auth.model.ServiceToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A token refresh can take its time. A Disconnect or a new sign-in landing meanwhile must win:
 * saving anyway would undo the sign-out, or put the old account's token back under the new
 * account's record. So the store saves a refresh only over the sign-in it refreshed.
 */
class StoredTokenRefreshTest {

    private val stored = StoredToken(
        accessToken = "old-access",
        refreshToken = "rawn-cookie",
        expiresAtEpoch = 100,
        scope = "rawnclark|webclient",
        userId = "rawnclark",
        displayName = "Rawn",
        imageUrl = "https://example/rawn.png",
    )
    private val refreshed = ServiceToken("new-access", "rawn-cookie", expiresAtEpoch = 200, scope = "|webclient")

    @Test fun `the sign-in that was refreshed takes the new token and keeps its user`() {
        assertEquals(
            stored.copy(accessToken = "new-access", expiresAtEpoch = 200, scope = "|webclient"),
            stored.afterRefresh("rawn-cookie", refreshed),
        )
    }

    @Test fun `another sign-in is left alone`() {
        assertNull(stored.copy(refreshToken = "sam-cookie").afterRefresh("rawn-cookie", refreshed))
    }
}
