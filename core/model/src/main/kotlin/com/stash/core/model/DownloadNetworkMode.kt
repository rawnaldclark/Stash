package com.stash.core.model

/**
 * Network-and-power conditions under which background downloads (Stash
 * Discover, Tag Enrichment) are allowed to run. Controls the WorkManager
 * `Constraints` attached to those workers — changing the mode requires
 * re-scheduling the workers so the updated constraints take effect.
 *
 * Ordered most-conservative (least battery + data impact) to least-
 * conservative (ships downloads whenever possible). The default is
 * [WIFI_AND_CHARGING] to match historical behaviour.
 */
enum class DownloadNetworkMode(
    /** Short label shown in the Settings radio row. */
    val label: String,
    /** Sub-label explaining what enabling this mode does. */
    val description: String,
) {
    /**
     * Unmetered network (Wi-Fi / Ethernet) AND device charging AND battery
     * not low. The original default — safe for users who don't want Stash
     * using cellular data or draining battery on the move.
     */
    WIFI_AND_CHARGING(
        label = "Wi-Fi + charging",
        description = "Most conservative. Downloads only when on Wi-Fi and plugged in.",
    ),

    /**
     * Unmetered network only — no charging required. Good for users who
     * keep the app in the foreground on Wi-Fi but rarely plug in.
     */
    WIFI_ANY(
        label = "Wi-Fi any time",
        description = "Downloads over Wi-Fi even without a charger.",
    ),

    /**
     * Any connected network, including cellular. Uses mobile data. Battery
     * check stays on so an unplugged phone at 5% doesn't burn the last
     * bit of power downloading discoveries.
     */
    ANY_NETWORK(
        label = "Any network",
        description = "Downloads over cellular too. Uses mobile data.",
    );
}
