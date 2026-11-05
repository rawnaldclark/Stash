package com.stash.feature.settings.cloud

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudAccount
import com.stash.data.cloud.upload.UploadStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Accounts › Cloud storage, drawn with real strings: one card per Google account, each
 * with its own status and buttons, and one account that takes saves and backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class CloudAccountCardsTest {

    @get:Rule
    val rule = createComposeRule()

    private val ron = CloudAccount(1, CloudProvider.GOOGLE_DRIVE, "Ron", "ron@example.com", 1)
    private val sam = CloudAccount(2, CloudProvider.GOOGLE_DRIVE, "Sam", "sam@example.com", 2)

    private val opened = mutableListOf<Long?>()
    private val disconnected = mutableListOf<CloudAccount>()
    private val chosen = mutableListOf<CloudAccount>()
    private val backup = mutableListOf<Pair<Long, Boolean>>()
    private val retried = mutableListOf<Long>()
    private val existing = mutableListOf<Long>()
    private val connected = mutableListOf<CloudProvider>()

    private val actions = CloudCardActions(
        usesPicker = { it == CloudProvider.GOOGLE_DRIVE },
        connect = { connected += it },
        openGooglePicker = { opened += it },
        disconnect = { disconnected += it },
        addFolder = {},
        setBackup = { id, on -> backup += id to on },
        setSaveAccount = { chosen += it },
        backUpExisting = { existing += it },
        retryUploads = { retried += it },
    )

    private fun status(
        saved: Int = 0,
        queued: Int = 0,
        existing: Int = 0,
        full: Boolean = false,
        backupOn: Boolean = false,
    ) = UploadStatus(backupOn, saved, queued, existing, 0, full, running = false, wifiOnly = true)

    private fun show(
        accounts: List<CloudAccountRow>,
        uploads: Map<Long, UploadStatus> = accounts.associate { it.account.id to status() },
        saveAccount: Long? = accounts.firstOrNull()?.account?.id,
        providers: List<CloudProvider> = listOf(CloudProvider.GOOGLE_DRIVE),
    ) = rule.setContent {
        MaterialTheme {
            Column { CloudAccountCards(providers, accounts, uploads, saveAccount, actions) }
        }
    }

    private fun CloudAccount.signedIn() = CloudAccountRow(this, signedIn = true)
    private fun CloudAccount.signedOut() = CloudAccountRow(this, signedIn = false)

    private val saveHere = "Save and back up to this account"
    private val backupSwitch = "Back up my downloads to Google Drive"
    private val addSongs = "Add songs from Google Drive"
    private val addAnother = "Add another Google account"

    @Test fun `no account offers Connect, and nothing about another account`() {
        show(emptyList())

        rule.onNodeWithText("Not connected").assertExists()
        rule.onNodeWithText(addAnother).assertDoesNotExist()
        rule.onNodeWithText("Connect").performClick()
        assertThat(opened).containsExactly(null)
    }

    @Test fun `one account looks as it did, with a way to add another`() {
        show(listOf(ron.signedIn()))

        rule.onNodeWithText("Connected as ron@example.com").assertExists()
        rule.onNodeWithText(backupSwitch).assertExists()
        // The logo, not a letter, and no radio: there's nothing to choose between.
        rule.onNodeWithContentDescription("Google Drive").assertExists()
        rule.onNodeWithText("R").assertDoesNotExist()
        rule.onNodeWithText(saveHere).assertDoesNotExist()

        rule.onNodeWithText(addAnother).performClick()
        assertThat(opened).containsExactly(null)
    }

    @Test fun `two accounts get a card each, told apart by letter and email`() {
        show(listOf(ron.signedIn(), sam.signedIn()))

        // The address alone: "Connected as" would cost it a line, and it's all that differs.
        rule.onNodeWithText("ron@example.com").assertExists()
        rule.onNodeWithText("sam@example.com").assertExists()
        rule.onNodeWithText("R").assertExists()
        rule.onNodeWithText("S").assertExists()
        rule.onNodeWithContentDescription("Google Drive").assertDoesNotExist()
        assertThat(rule.onAllNodesWithText(addAnother).fetchSemanticsNodes()).hasSize(1)
        assertThat(rule.onAllNodesWithText(addSongs).fetchSemanticsNodes()).hasSize(2)
    }

    @Test fun `the account saves go to is the selected one, and holds the backup switch`() {
        show(listOf(ron.signedIn(), sam.signedIn()), saveAccount = 2)

        val radios = rule.onAllNodesWithText(saveHere)
        radios[0].assertIsNotSelected()
        radios[1].assertIsSelected()
        assertThat(rule.onAllNodesWithText(backupSwitch).fetchSemanticsNodes()).hasSize(1)

        // The switch belongs to the account it's on, the selected one.
        rule.onNodeWithText(backupSwitch).performClick()
        assertThat(backup).containsExactly(2L to true)
    }

    @Test fun `tapping the other radio chooses that account, tapping the selected one does nothing`() {
        show(listOf(ron.signedIn(), sam.signedIn()), saveAccount = 1)

        val radios = rule.onAllNodesWithText(saveHere)
        radios[0].performClick()
        assertThat(chosen).isEmpty()

        radios[1].performClick()
        assertThat(chosen).containsExactly(sam)
    }

    @Test fun `each account's buttons act on that account`() {
        show(listOf(ron.signedIn(), sam.signedIn()))

        rule.onAllNodesWithText(addSongs)[1].performClick()
        assertThat(opened).containsExactly(2L)

        rule.onAllNodesWithText("Disconnect")[1].performClick()
        assertThat(disconnected).containsExactly(sam)
    }

    @Test fun `one account signed out shows Reconnect on its own card only`() {
        show(listOf(ron.signedOut(), sam.signedIn()), saveAccount = 2)

        rule.onNodeWithText("Session expired for ron@example.com. Reconnect to keep syncing.").assertExists()
        rule.onNodeWithText("sam@example.com").assertExists()
        assertThat(rule.onAllNodesWithText("Reconnect").fetchSemanticsNodes()).hasSize(1)
        // The signed-out card has no rows; the other carries on.
        assertThat(rule.onAllNodesWithText(addSongs).fetchSemanticsNodes()).hasSize(1)

        rule.onNodeWithText("Reconnect").performClick()
        assertThat(opened).containsExactly(1L)
    }

    @Test fun `one Drive full says so on its own card, and Try again acts on that account`() {
        show(
            listOf(ron.signedIn(), sam.signedIn()),
            uploads = mapOf(1L to status(saved = 124, backupOn = true), 2L to status(saved = 3, queued = 2, full = true)),
            saveAccount = 1,
        )

        rule.onNodeWithText("Backed up 124 songs").assertExists()
        rule.onNodeWithText("Your Google Drive is full. Free up space, then try again.").assertExists()
        assertThat(rule.onAllNodesWithText("Try again").fetchSemanticsNodes()).hasSize(1)

        rule.onNodeWithText("Try again").performClick()
        assertThat(retried).containsExactly(2L)
    }

    @Test fun `an account that isn't the chosen one still says how its uploads stand`() {
        show(
            listOf(ron.signedIn(), sam.signedIn()),
            uploads = mapOf(1L to status(), 2L to status(saved = 3)),
            saveAccount = 1,
        )

        rule.onNodeWithText("Saved 3 songs").assertExists()
    }

    @Test fun `waiting downloads are offered on the account backup runs on`() {
        show(
            listOf(ron.signedIn(), sam.signedIn()),
            uploads = mapOf(1L to status(), 2L to status(existing = 12, backupOn = true)),
            saveAccount = 2,
        )

        rule.onNodeWithText("Back up 12 existing downloads").performClick()
        assertThat(existing).containsExactly(2L)
    }

    @Test fun `other providers stay one Connect card and offer no second account`() {
        show(emptyList(), providers = listOf(CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE))

        rule.onAllNodesWithText("Connect")[0].performClick()
        assertThat(connected).containsExactly(CloudProvider.DROPBOX)
        assertThat(opened).isEmpty()
    }

    @Test fun `a connected Dropbox offers no second account`() {
        val box = CloudAccount(3, CloudProvider.DROPBOX, "Ron", "ron@box.example", 3)
        show(listOf(box.signedIn()), providers = listOf(CloudProvider.DROPBOX))

        rule.onNodeWithText("Connected as ron@box.example").assertExists()
        rule.onNodeWithText(addAnother).assertDoesNotExist()
        rule.onNodeWithText("Add a music folder").assertExists()
    }

    @Test fun `the notices read as written`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(CloudNotice.AlreadyConnected("sam@example.com", 0).text(context)).isEqualTo("Already connected as sam@example.com.")
        assertThat(CloudNotice.AlreadyConnected("sam@example.com", 1).text(context)).isEqualTo("Already connected as sam@example.com. Adding 1 song.")
        assertThat(CloudNotice.AlreadyConnected("sam@example.com", 2).text(context)).isEqualTo("Already connected as sam@example.com. Adding 2 songs.")
        assertThat(CloudNotice.SavingTo("sam@example.com").text(context)).isEqualTo("Saves and backup go to sam@example.com from now on. Songs already saved stay where they are.")
        assertThat(CloudNotice.AccountUnidentified.text(context)).contains("which account")
    }

    @Test fun `the disconnect dialog names the account and what moves`() {
        rule.setContent {
            MaterialTheme {
                CloudDisconnectDialog(
                    PendingCloudDisconnect(
                        accountId = 1, provider = CloudProvider.GOOGLE_DRIVE, removed = 3, kept = 2,
                        name = "ron@example.com", savesMoveTo = "sam@example.com", backupTurnsOff = true,
                    ),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }

        rule.onNodeWithText("Disconnect ron@example.com?").assertExists()
        rule.onNodeWithText(
            "Stash forgets this sign-in on this phone. 3 songs that only stream from ron@example.com leave your library, with their play history. " +
                "2 downloaded songs stay on this phone. Songs waiting to be saved to it won't be. " +
                "Backup turns off until you turn it on for another account. Save to Google Drive will use sam@example.com. " +
                "Your files in Google Drive are not touched.",
        ).assertExists()
    }

    @Test fun `the disconnect dialog with one account reads as it did`() {
        rule.setContent {
            MaterialTheme {
                CloudDisconnectDialog(
                    PendingCloudDisconnect(accountId = 1, provider = CloudProvider.GOOGLE_DRIVE, removed = 1, kept = 0),
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }

        rule.onNodeWithText("Disconnect Google Drive?").assertExists()
        rule.onNodeWithText(
            "Stash forgets this sign-in on this phone. 1 song that only streams from Google Drive leaves your library, with its play history. " +
                "Songs waiting to be saved to it won't be. Your files in Google Drive are not touched.",
        ).assertExists()
    }
}
