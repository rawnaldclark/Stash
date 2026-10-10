package com.stash.core.data.weblink

/** The ids this phone mints (sync-v1 §1). */
object WebLinkIds {
    private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** `d_` + 16 of A–Z a–z 0–9, unbiased (bytes ≥ 248 are drawn again). */
    fun newDeviceId(): String {
        val sb = StringBuilder("d_")
        while (sb.length < 18) {
            val b = SyncCrypto.randomBytes(1)[0].toInt() and 0xff
            if (b < 248) sb.append(ID_CHARS[b % 62])
        }
        return sb.toString()
    }

    /** `s_` + 16 random bytes in base64url: the phone mints the space id (it is the HKDF salt and in every AAD). */
    fun newSpaceId(): String = "s_" + Base64Url.encode(SyncCrypto.randomBytes(16))
}
