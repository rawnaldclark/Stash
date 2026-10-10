package com.stash.core.data.weblink

/** What the screen shows (spec §2.2, §2.6, §12; sync-v1 §3.4, §3.6). One place, so the tests check the same words. */
object WebLinkCopy {
    const val NOT_A_CODE = "That's not a Stash code."
    const val USED = "This code was already used. Show a new one on your computer."
    const val EXPIRED = "This code expired. Show a new one."
    const val UNFINISHED = "Linking didn't finish. Show a new code on your computer."
    const val OFFLINE = "Can't reach Stash right now. Try again in a minute."
    const val SLOW_DOWN = "Too many tries. Wait a minute and try again."
    const val FULL = "You've linked 4 browsers. Remove one first."
    const val ALREADY_LINKED = "That browser is already linked."
    const val KEY_CHANGED = "Key changed: remove it and link it again"
    const val NOT_VERIFIED = "Not verified"
    const val CANT_READ = "Can't read your other devices: link again."
    const val UPDATE_APP = "Update Stash to keep syncing."

    fun unlinkedFrom(name: String) = "Unlinked from $name."
}

/** How far this phone trusts a member's key (sync-v1 §3.6). */
enum class DeviceTrust {
    /** Its label opened and its key is the pinned one (or this phone). */
    VERIFIED,

    /** Listed by the server, but no label this phone can open yet. */
    NOT_VERIFIED,

    /** Its label carries another key than the one pinned for that id: remove it and link it again. */
    KEY_CHANGED,
}

/** One device in the linked-devices list. */
data class LinkedDevice(
    val id: String,
    /** `phone` or `web`. */
    val type: String,
    /** Its label's name, or this phone's nickname for it. */
    val name: String,
    val isMe: Boolean,
    val trust: DeviceTrust,
    val lastSeenAt: Long,
)

/** What the Link Stash on the web screen shows. */
sealed interface WebLinkStatus {
    data object Loading : WebLinkStatus

    data object NotLinked : WebLinkStatus

    /** [serverTime] is the server's clock when the list was read, so "last used" ages don't depend on this phone's clock. */
    data class Linked(
        val devices: List<LinkedDevice>,
        val epoch: Int,
        val serverTime: Long,
        /** Set when the last refresh failed; the list is the last one read. */
        val problem: String? = null,
    ) : WebLinkStatus {
        val browsers: List<LinkedDevice> get() = devices.filter { !it.isMe }
    }
}

/** The outcome of an action on the link (rename, remove, unlink everything, refresh). */
sealed interface WebLinkResult {
    data object Ok : WebLinkResult

    data class Failed(val message: String) : WebLinkResult

    /** This phone is no longer linked (revoked, or the space is gone): the local state was deleted. */
    data class Unlinked(val notice: String) : WebLinkResult
}

/** The user-facing words for a failed call. */
internal fun SyncResult.Error.userMessage(): String = when {
    status == 429 -> WebLinkCopy.SLOW_DOWN
    code == SyncErrorCode.EXPIRED -> WebLinkCopy.EXPIRED
    code == SyncErrorCode.USED -> WebLinkCopy.USED
    code == SyncErrorCode.FULL -> WebLinkCopy.FULL
    status >= 500 -> WebLinkCopy.OFFLINE
    else -> WebLinkCopy.UNFINISHED
}
