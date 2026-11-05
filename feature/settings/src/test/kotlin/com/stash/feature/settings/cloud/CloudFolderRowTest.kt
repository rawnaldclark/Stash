package com.stash.feature.settings.cloud

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.CloudFolderWithCount
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Cloud folders with two accounts: each account's "Picked songs" says whose it is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudFolderRowTest {

    @get:Rule
    val rule = createComposeRule()

    private val ron = CloudAccount(1, CloudProvider.GOOGLE_DRIVE, "Ron", "ron@example.com", 1)
    private val sam = CloudAccount(2, CloudProvider.GOOGLE_DRIVE, "Sam", null, 2)
    private val box = CloudAccount(3, CloudProvider.DROPBOX, "Ron", "ron@box.example", 3)

    private fun rows(vararg accounts: CloudAccount) = accounts.map { CloudAccountRow(it, signedIn = true) }

    private fun picked(trackCount: Int) = CloudFolderWithCount(
        id = 9, accountId = 1, itemId = "picked", name = "Picked songs", path = "",
        lastScanAt = null, lastScanStatus = CloudScanStatus.OK.name, lastScanError = null, trackCount = trackCount,
    )

    @Test fun `the only account of its provider isn't named`() {
        assertThat(rows(ron).nameIfShared(1)).isNull()
        // Another provider's account doesn't make it ambiguous.
        assertThat(rows(ron, box).nameIfShared(1)).isNull()
    }

    @Test fun `two accounts of a provider are named, by email or else by name`() {
        assertThat(rows(ron, sam).nameIfShared(1)).isEqualTo("ron@example.com")
        assertThat(rows(ron, sam).nameIfShared(2)).isEqualTo("Sam")
    }

    @Test fun `an account no longer connected has no name`() {
        assertThat(rows(ron, sam).nameIfShared(99)).isNull()
    }

    @Test fun `the row says provider, account and songs`() {
        rule.setContent {
            MaterialTheme {
                CloudFolderRow(picked(trackCount = 12), CloudProvider.GOOGLE_DRIVE, accountName = "ron@example.com", onRemove = null)
            }
        }

        rule.onNodeWithText("Picked songs").assertExists()
        rule.onNodeWithText("Google Drive · ron@example.com · 12 songs").assertExists()
    }

    @Test fun `with one account the row reads as it did`() {
        rule.setContent {
            MaterialTheme {
                CloudFolderRow(picked(trackCount = 1), CloudProvider.GOOGLE_DRIVE, accountName = null, onRemove = null)
            }
        }

        rule.onNodeWithText("Google Drive · 1 song").assertExists()
    }
}
