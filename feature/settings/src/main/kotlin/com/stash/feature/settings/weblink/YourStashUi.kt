package com.stash.feature.settings.weblink

import com.stash.core.data.weblink.DeviceTrust
import com.stash.core.data.weblink.LinkedDevice
import com.stash.core.data.weblink.WebLinkStatus
import com.stash.core.model.CloudProvider
import com.stash.feature.settings.SupporterRow
import com.stash.feature.settings.cloud.CloudAccountRow

/**
 * What Settings › Library & Storage › Your Stash shows (owner decision D21, phase P0: presentation only).
 *
 * Your Stash is the end-to-end encrypted sync space the link already is: its members are your devices, and there is no
 * account and no email. The page gathers what the phone already knows next to it: the supporter claim, the user's own cloud
 * accounts and local backups. Nothing here is read from a new place or stored; this only decides what each section shows
 * from the existing state, so the screen stays a plain renderer. Pure, for the unit test.
 *
 * @property devices this phone and the browsers in the space.
 * @property linkFeatures Pick up where you left off, Mirror, Send my library, Waiting for you and Unlink everything: they act
 *   on the space, so they show only while this phone is in one (null otherwise).
 * @property supporter the Ko-fi claim as Lossless sources shows it ([SupporterRow.Hidden]: no section, also while it is
 *   not read yet).
 * @property cloud the user's own cloud accounts.
 * @property backups the existing local backup actions. Always offered, linked or not: nothing on this page is gated.
 * @property coming planned work, shown as future with no dates. Never actionable.
 */
internal data class YourStashUi(
    val devices: YourStashDevices,
    val linkFeatures: YourStashLinkFeatures?,
    val supporter: SupporterRow,
    val cloud: YourStashCloud,
    val backups: List<BackupShortcut> = BackupShortcut.entries,
    val coming: List<YourStashComing> = YourStashComing.entries,
)

/** The Devices section. */
internal sealed interface YourStashDevices {
    /** The space is being read for the first time (a progress bar). */
    data object Loading : YourStashDevices

    /** No browser linked: this phone alone, and the way to link one. */
    data object ThisPhoneOnly : YourStashDevices

    /**
     * This phone first, then the browsers in the order they joined (the repository's order).
     * [problem]: the last read failed and [rows] is the last list known.
     */
    data class Linked(val rows: List<YourStashDevice>, val problem: String?) : YourStashDevices
}

/** What the link does, while linked. [canSend]: a browser is linked to send the library to. */
internal data class YourStashLinkFeatures(val canSend: Boolean)

/** One device row: the device, and what its second line says. */
internal data class YourStashDevice(val device: LinkedDevice, val seen: DeviceSeen)

/** A device row's second line, before it is put into words. */
internal sealed interface DeviceSeen {
    data object ThisPhone : DeviceSeen

    /** Its label carries another key than the one pinned here: remove it and link it again. */
    data object KeyChanged : DeviceSeen

    /** Listed by the server, but no label this phone can open yet. */
    data object NotVerified : DeviceSeen

    /** Linked, with no usable last-seen time (an old server, or no server clock). */
    data object Linked : DeviceSeen

    /** Last seen [days] whole days before the server's clock: 0 is today, 1 yesterday. */
    data class DaysAgo(val days: Int) : DeviceSeen
}

/** The Cloud section: the user's own Dropbox, OneDrive or Google Drive, signed in on this phone. */
internal sealed interface YourStashCloud {
    /** This build offers no cloud provider (no keys), or the accounts aren't read yet: no section. */
    data object Hidden : YourStashCloud

    /** Nothing connected: one row to connect, naming what this build offers. */
    data class NoneConnected(val providers: List<CloudProvider>) : YourStashCloud

    /** The connected accounts, each with whether its sign-in is still on this phone. */
    data class Connected(val accounts: List<CloudAccountRow>) : YourStashCloud
}

/**
 * A backup action Your Stash starts on Library & Storage, where the existing flow lives (its file pickers, its scope
 * dialogs and the restart a restore needs). The app passes it in the Library & Storage route.
 */
enum class BackupShortcut {
    /** "Export backup": the scope dialog, then the file to save. */
    BACK_UP,

    /** "Import backup": the backup file picker, then the restore dialog. */
    RESTORE,
}

/** What's coming (D21's later phases), worded as future, with no dates. */
internal enum class YourStashComing { PASSKEY_RECOVERY, NIGHTLY_BACKUP, RESTORE_NEW_PHONE }

/** One day, in the server's milliseconds. */
private const val DAY_MS = 86_400_000L

/**
 * Builds the page from the existing state: the link's status ([WebLinkStatus]), the supporter row ([SupporterRow]) and the
 * cloud accounts ([CloudAccountRow]). [cloudAvailable] and [cloudProviders] are what this build offers. A null [supporter]
 * or [cloudAccounts] hasn't been read yet: its section waits rather than showing a placeholder that then changes
 * (see [YourStashViewModel]).
 */
internal fun yourStashUi(
    link: WebLinkStatus,
    supporter: SupporterRow?,
    cloudAvailable: Boolean,
    cloudProviders: List<CloudProvider>,
    cloudAccounts: List<CloudAccountRow>?,
): YourStashUi {
    val devices = when (link) {
        WebLinkStatus.Loading -> YourStashDevices.Loading
        WebLinkStatus.NotLinked -> YourStashDevices.ThisPhoneOnly
        is WebLinkStatus.Linked -> YourStashDevices.Linked(
            rows = link.devices.map { YourStashDevice(it, deviceSeen(it, link.serverTime)) },
            problem = link.problem,
        )
    }
    val cloud = when {
        !cloudAvailable || cloudAccounts == null -> YourStashCloud.Hidden
        cloudAccounts.isEmpty() -> YourStashCloud.NoneConnected(cloudProviders)
        else -> YourStashCloud.Connected(cloudAccounts)
    }
    return YourStashUi(
        devices = devices,
        linkFeatures = (link as? WebLinkStatus.Linked)?.let { YourStashLinkFeatures(canSend = it.browsers.isNotEmpty()) },
        supporter = supporter ?: SupporterRow.Hidden,
        cloud = cloud,
    )
}

/**
 * What a device row's second line says. The age is measured against [serverTime], the server's clock when the list was
 * read, so a phone with a wrong clock still says "today" for a browser used today. Moved here unchanged from the old
 * Link Stash on the web screen.
 */
internal fun deviceSeen(d: LinkedDevice, serverTime: Long): DeviceSeen = when {
    d.isMe -> DeviceSeen.ThisPhone
    d.trust == DeviceTrust.KEY_CHANGED -> DeviceSeen.KeyChanged
    d.trust == DeviceTrust.NOT_VERIFIED -> DeviceSeen.NotVerified
    serverTime <= 0 || d.lastSeenAt <= 0 -> DeviceSeen.Linked
    else -> DeviceSeen.DaysAgo(((serverTime - d.lastSeenAt).coerceAtLeast(0) / DAY_MS).toInt())
}
