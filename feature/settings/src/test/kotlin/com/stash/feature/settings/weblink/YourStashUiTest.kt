package com.stash.feature.settings.weblink

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.DeviceTrust
import com.stash.core.data.weblink.LinkedDevice
import com.stash.core.data.weblink.WebLinkStatus
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudAccount
import com.stash.feature.settings.SupporterRow
import com.stash.feature.settings.cloud.CloudAccountRow
import org.junit.Test

/**
 * Your Stash's page model ([yourStashUi]) from fake inputs: linked or not, supporter or not, cloud accounts or none, and
 * that the backups and What's coming are there whatever the rest says (nothing on the page is gated).
 */
class YourStashUiTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private val me = LinkedDevice("me", "phone", "Pixel 5", isMe = true, trust = DeviceTrust.VERIFIED, lastSeenAt = now)
    private fun browser(id: String, name: String, seen: Long, trust: DeviceTrust = DeviceTrust.VERIFIED) =
        LinkedDevice(id, "web", name, isMe = false, trust = trust, lastSeenAt = seen)

    private fun linked(vararg devices: LinkedDevice, serverTime: Long = now, problem: String? = null) =
        WebLinkStatus.Linked(devices.toList(), epoch = 1, serverTime = serverTime, problem = problem)

    private val allProviders = listOf(CloudProvider.DROPBOX, CloudProvider.ONEDRIVE, CloudProvider.GOOGLE_DRIVE)
    private fun cloudRow(provider: CloudProvider, signedIn: Boolean = true) = CloudAccountRow(
        CloudAccount(id = provider.ordinal.toLong(), provider = provider, displayName = "Ray", email = "ray@example.com", connectedAt = 1L),
        signedIn = signedIn,
    )

    private fun ui(
        link: WebLinkStatus = WebLinkStatus.NotLinked,
        supporter: SupporterRow? = SupporterRow.Offer(hasToken = false),
        cloudAvailable: Boolean = true,
        providers: List<CloudProvider> = allProviders,
        accounts: List<CloudAccountRow>? = emptyList(),
    ) = yourStashUi(link, supporter, cloudAvailable, providers, accounts)

    // ------------------------------------------------------------------------------------------------------- devices

    @Test fun `not linked is this phone alone, with no link features`() {
        val u = ui(link = WebLinkStatus.NotLinked)
        assertThat(u.devices).isEqualTo(YourStashDevices.ThisPhoneOnly)
        assertThat(u.linkFeatures).isNull()
    }

    @Test fun `the first read is loading, with no link features yet`() {
        val u = ui(link = WebLinkStatus.Loading)
        assertThat(u.devices).isEqualTo(YourStashDevices.Loading)
        assertThat(u.linkFeatures).isNull()
    }

    @Test fun `linked lists this phone then the browsers, each with when it was last used`() {
        val chrome = browser("c", "Chrome on Windows", seen = now - 3 * day - 5)
        val u = ui(link = linked(me, chrome))
        val devices = u.devices as YourStashDevices.Linked
        assertThat(devices.rows.map { it.device.name }).containsExactly("Pixel 5", "Chrome on Windows").inOrder()
        assertThat(devices.rows.map { it.seen }).containsExactly(DeviceSeen.ThisPhone, DeviceSeen.DaysAgo(3)).inOrder()
        assertThat(devices.problem).isNull()
        assertThat(u.linkFeatures).isEqualTo(YourStashLinkFeatures(canSend = true))
    }

    @Test fun `a failed read keeps the last list and says why`() {
        val u = ui(link = linked(me, browser("c", "Chrome", now), problem = "Can't reach Stash right now. Try again in a minute."))
        val devices = u.devices as YourStashDevices.Linked
        assertThat(devices.rows).hasSize(2)
        assertThat(devices.problem).isEqualTo("Can't reach Stash right now. Try again in a minute.")
    }

    @Test fun `linked with every browser removed keeps the link features, but there is nothing to send to`() {
        val u = ui(link = linked(me))
        assertThat(u.linkFeatures).isEqualTo(YourStashLinkFeatures(canSend = false))
    }

    @Test fun `last used reads the server's clock, never a negative age`() {
        assertThat(deviceSeen(browser("a", "A", seen = now - 10), now)).isEqualTo(DeviceSeen.DaysAgo(0))
        assertThat(deviceSeen(browser("a", "A", seen = now - day), now)).isEqualTo(DeviceSeen.DaysAgo(1))
        assertThat(deviceSeen(browser("a", "A", seen = now - 40 * day), now)).isEqualTo(DeviceSeen.DaysAgo(40))
        // The browser's clock ran ahead of the server's read: today, not "-1 days".
        assertThat(deviceSeen(browser("a", "A", seen = now + day), now)).isEqualTo(DeviceSeen.DaysAgo(0))
    }

    @Test fun `no usable time says linked, and trust problems win over the time`() {
        assertThat(deviceSeen(browser("a", "A", seen = 0), now)).isEqualTo(DeviceSeen.Linked)
        assertThat(deviceSeen(browser("a", "A", seen = now), serverTime = 0)).isEqualTo(DeviceSeen.Linked)
        assertThat(deviceSeen(browser("a", "A", seen = now, trust = DeviceTrust.KEY_CHANGED), now)).isEqualTo(DeviceSeen.KeyChanged)
        assertThat(deviceSeen(browser("a", "A", seen = now, trust = DeviceTrust.NOT_VERIFIED), now)).isEqualTo(DeviceSeen.NotVerified)
        assertThat(deviceSeen(me, now)).isEqualTo(DeviceSeen.ThisPhone)
    }

    // ----------------------------------------------------------------------------------------------------- supporter

    @Test fun `the supporter section shows the claim's state as Lossless sources has it`() {
        assertThat(ui(supporter = SupporterRow.Offer(hasToken = false)).supporter).isEqualTo(SupporterRow.Offer(false))
        assertThat(ui(supporter = SupporterRow.Supporter(hasToken = true)).supporter).isEqualTo(SupporterRow.Supporter(true))
        // No owner relay configured: nothing to lift, so no section.
        assertThat(ui(supporter = SupporterRow.Hidden).supporter).isEqualTo(SupporterRow.Hidden)
    }

    // --------------------------------------------------------------------------------------------------------- cloud

    @Test fun `no cloud account offers to connect, naming what this build offers`() {
        val u = ui(providers = listOf(CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE))
        assertThat(u.cloud).isEqualTo(YourStashCloud.NoneConnected(listOf(CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE)))
    }

    @Test fun `connected cloud accounts are listed, a signed-out one included`() {
        val dropbox = cloudRow(CloudProvider.DROPBOX)
        val drive = cloudRow(CloudProvider.GOOGLE_DRIVE, signedIn = false)
        assertThat(ui(accounts = listOf(dropbox, drive)).cloud).isEqualTo(YourStashCloud.Connected(listOf(dropbox, drive)))
    }

    @Test fun `a build without cloud keys has no cloud section, whatever is stored`() {
        assertThat(ui(cloudAvailable = false).cloud).isEqualTo(YourStashCloud.Hidden)
        assertThat(ui(cloudAvailable = false, accounts = listOf(cloudRow(CloudProvider.DROPBOX))).cloud).isEqualTo(YourStashCloud.Hidden)
    }

    @Test fun `before the first read, neither supporter nor cloud shows a placeholder`() {
        val u = ui(supporter = null, accounts = null)
        // No late-appearing Supporter row, and no "Connect your cloud storage" flash on a phone with accounts.
        assertThat(u.supporter).isEqualTo(SupporterRow.Hidden)
        assertThat(u.cloud).isEqualTo(YourStashCloud.Hidden)
        // Once read, they show.
        assertThat(ui(supporter = SupporterRow.Offer(false), accounts = emptyList()).cloud)
            .isEqualTo(YourStashCloud.NoneConnected(allProviders))
    }

    // ------------------------------------------------------------------------------------ backups and what's coming

    @Test fun `backups are offered linked or not, supporter or not, with or without cloud`() {
        val cases = listOf(
            ui(link = WebLinkStatus.NotLinked, supporter = SupporterRow.Hidden, cloudAvailable = false),
            ui(link = WebLinkStatus.Loading),
            ui(link = linked(me, browser("c", "Chrome", now)), supporter = SupporterRow.Supporter(true), accounts = listOf(cloudRow(CloudProvider.ONEDRIVE))),
        )
        for (u in cases) assertThat(u.backups).containsExactly(BackupShortcut.BACK_UP, BackupShortcut.RESTORE).inOrder()
    }

    @Test fun `what's coming is the three planned pieces, in order, for everyone`() {
        for (u in listOf(ui(), ui(link = linked(me)), ui(supporter = SupporterRow.Hidden, cloudAvailable = false))) {
            assertThat(u.coming).containsExactly(
                YourStashComing.PASSKEY_RECOVERY,
                YourStashComing.NIGHTLY_BACKUP,
                YourStashComing.RESTORE_NEW_PHONE,
            ).inOrder()
        }
    }
}
