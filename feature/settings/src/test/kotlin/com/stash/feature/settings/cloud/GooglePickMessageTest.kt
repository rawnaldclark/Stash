package com.stash.feature.settings.cloud

import com.google.common.truth.Truth.assertThat
import com.stash.data.cloud.provider.gdrive.GooglePickOutcome
import org.junit.Test

/** What Settings tells the user after Google's picker closes. */
class GooglePickMessageTest {

    private fun added(new: Boolean, added: Int, already: Int = 0) =
        GooglePickOutcome.Added(accountId = 1, displayName = "Ron", isNewAccount = new, added = added, alreadyThere = already)

    @Test
    fun `backing out of the picker says nothing`() {
        assertThat(googlePickMessage(GooglePickOutcome.Cancelled)).isNull()
    }

    @Test
    fun `the first pick says the account is connected`() {
        assertThat(googlePickMessage(added(new = true, added = 12)))
            .isEqualTo("Google Drive connected as Ron. Adding 12 songs from Google Drive. Wi-Fi is best for big batches.")
    }

    @Test
    fun `add more counts new songs and the ones already there`() {
        assertThat(googlePickMessage(added(new = false, added = 1))).isEqualTo("Adding 1 song from Google Drive.")
        assertThat(googlePickMessage(added(new = false, added = 0, already = 3))).isEqualTo("Those songs are already in your library.")
        assertThat(googlePickMessage(added(new = false, added = 0))).isEqualTo("Nothing was picked.")
    }

    @Test
    fun `a failure is shown as is`() {
        assertThat(googlePickMessage(GooglePickOutcome.Failed("Couldn't add songs from Google Drive."))).isEqualTo("Couldn't add songs from Google Drive.")
    }
}
