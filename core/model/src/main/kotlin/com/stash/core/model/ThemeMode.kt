package com.stash.core.model

/**
 * User-selectable theme preference.
 *
 * - [LIGHT] — force light color scheme regardless of system setting.
 * - [DARK] — force dark color scheme regardless of system setting.
 * - [SYSTEM] — follow the Android system-wide day/night setting.
 *
 * Default is [SYSTEM] so new installs match user expectations from
 * other apps on their device.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}
