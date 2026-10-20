package com.stash.data.cloud.provider.gdrive

import com.google.android.gms.common.api.CommonStatusCodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which silent-authorization failures cost the user a sign-in, and which are a retry later. */
class GoogleAuthorizerTest {

    @Test
    fun `only a failure that needs the user is a reconnect`() {
        assertThat(silentFailureNeedsUser(CommonStatusCodes.SIGN_IN_REQUIRED, hasResolution = false)).isTrue()
        assertThat(silentFailureNeedsUser(CommonStatusCodes.RESOLUTION_REQUIRED, hasResolution = false)).isTrue()
        assertThat(silentFailureNeedsUser(CommonStatusCodes.INVALID_ACCOUNT, hasResolution = false)).isTrue()
        // Whatever the code, a resolution means Google wants to show the user a screen.
        assertThat(silentFailureNeedsUser(CommonStatusCodes.INTERNAL_ERROR, hasResolution = true)).isTrue()
    }

    @Test
    fun `transient Play services failures are retried later`() {
        listOf(
            CommonStatusCodes.NETWORK_ERROR,
            CommonStatusCodes.INTERNAL_ERROR,
            CommonStatusCodes.INTERRUPTED,
            CommonStatusCodes.TIMEOUT,
            CommonStatusCodes.API_NOT_CONNECTED,
            CommonStatusCodes.CANCELED,
            CommonStatusCodes.DEVELOPER_ERROR,
            CommonStatusCodes.ERROR,
        ).forEach { assertThat(silentFailureNeedsUser(it, hasResolution = false)).isFalse() }
    }
}
