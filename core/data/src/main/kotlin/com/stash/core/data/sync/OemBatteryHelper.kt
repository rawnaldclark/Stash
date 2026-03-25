package com.stash.core.data.sync

import android.os.Build

/**
 * Utility that identifies OEM manufacturers known for aggressive battery
 * optimisation that can kill background workers (including WorkManager chains).
 *
 * When running on one of these devices the UI can prompt the user to
 * whitelist Stash from battery restrictions, linking to the relevant
 * guide on [dontkillmyapp.com](https://dontkillmyapp.com).
 */
object OemBatteryHelper {

    /**
     * @property name  Human-readable manufacturer name.
     * @property helpUrl Direct link to the dontkillmyapp.com guide page.
     */
    data class OemInfo(val name: String, val helpUrl: String)

    /**
     * Returns [OemInfo] if the current device manufacturer is known to
     * aggressively restrict background work, or null for stock-like OEMs.
     */
    fun getAggressiveOemInfo(): OemInfo? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                OemInfo("Xiaomi", "https://dontkillmyapp.com/xiaomi")
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                OemInfo("Huawei", "https://dontkillmyapp.com/huawei")
            manufacturer.contains("samsung") ->
                OemInfo("Samsung", "https://dontkillmyapp.com/samsung")
            manufacturer.contains("oneplus") ->
                OemInfo("OnePlus", "https://dontkillmyapp.com/oneplus")
            manufacturer.contains("oppo") ->
                OemInfo("OPPO", "https://dontkillmyapp.com/oppo")
            manufacturer.contains("vivo") ->
                OemInfo("Vivo", "https://dontkillmyapp.com/vivo")
            else -> null
        }
    }

    /** True when the current device is from an OEM known for aggressive battery policies. */
    val isAggressiveOem: Boolean get() = getAggressiveOemInfo() != null
}
