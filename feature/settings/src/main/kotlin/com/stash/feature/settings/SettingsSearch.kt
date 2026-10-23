package com.stash.feature.settings

import android.content.Context

/** Where a search result lives; the hub opens that page. */
enum class SettingsPage { PLAYBACK, AUDIO, LOSSLESS_SOURCES, EQUALIZER, ACCOUNTS, LIBRARY, APPEARANCE, HOME_LAYOUT, ABOUT }

/**
 * One searchable setting: its name as the screen shows it, the page it is on,
 * and the other words people use for it ("eq", "dark mode", "dac").
 */
data class SettingsSearchEntry(
    val title: String,
    val page: SettingsPage,
    val keywords: List<String> = emptyList(),
)

/**
 * Settings search. Every query word must match the start of a word in the
 * setting's name, its synonyms, or its page's name, so "dark theme", "bit
 * perfect" and "eq" all land. Settings whose name matches come first, then
 * the rest in index order (which follows the screens).
 */
fun searchSettings(
    query: String,
    index: List<SettingsSearchEntry>,
    pageTitle: (SettingsPage) -> String,
): List<SettingsSearchEntry> {
    val tokens = normalize(query).split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return index
        .mapIndexedNotNull { i, entry ->
            val titleWords = words(entry.title)
            val all = titleWords + entry.keywords.flatMap(::words) + words(pageTitle(entry.page))
            if (!tokens.all { t -> all.any { it.startsWith(t) } }) return@mapIndexedNotNull null
            val rank = when {
                normalize(entry.title).startsWith(normalize(query)) -> 0
                tokens.all { t -> titleWords.any { it.startsWith(t) } } -> 1
                else -> 2
            }
            Triple(rank, i, entry)
        }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { it.third }
}

private fun normalize(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * "Wi-Fi" → [wi, fi, wifi]: each pair of neighbouring words joined too, so
 * "wifi" finds "Wi-Fi" and "lastfm" finds "Last.fm".
 */
private fun words(s: String): List<String> {
    val parts = normalize(s).split(' ').filter { it.isNotEmpty() }
    return parts + parts.zipWithNext { a, b -> a + b }
}

/**
 * Every setting the hub can find, in screen order. Names come from the same
 * strings the screens use where there is one, so a renamed row stays findable.
 */
internal fun settingsSearchIndex(context: Context): List<SettingsSearchEntry> {
    fun s(id: Int) = context.getString(id)
    return listOf(
        // Playback
        SettingsSearchEntry(s(R.string.settings_stream_on_cellular_title), SettingsPage.PLAYBACK, listOf("mobile data", "5g", "lte")),
        SettingsSearchEntry("Stream or download", SettingsPage.PLAYBACK, listOf("stream only", "offline", "mode")),
        SettingsSearchEntry(s(R.string.settings_autoplay_radio_title), SettingsPage.PLAYBACK, listOf("endless", "keep playing", "continue")),
        SettingsSearchEntry(s(R.string.settings_crossfade_title), SettingsPage.PLAYBACK, listOf("fade", "gapless", "transition")),
        SettingsSearchEntry("Lyrics", SettingsPage.PLAYBACK, listOf("lrc", "synced", "karaoke", "apple music")),
        SettingsSearchEntry(s(R.string.settings_stream_via_youtube_title), SettingsPage.PLAYBACK, listOf("fallback")),
        // Audio & Quality
        SettingsSearchEntry(s(R.string.settings_lossless_title), SettingsPage.AUDIO, listOf("flac", "hifi", "hi res", "quality")),
        SettingsSearchEntry(s(R.string.settings_streaming_on_wifi_title), SettingsPage.AUDIO, listOf("quality", "bitrate")),
        SettingsSearchEntry(s(R.string.settings_streaming_on_cellular_title), SettingsPage.AUDIO, listOf("quality", "mobile data")),
        SettingsSearchEntry(s(R.string.settings_save_data_title), SettingsPage.AUDIO, listOf("data saver", "mobile data", "low quality")),
        SettingsSearchEntry(s(R.string.settings_prefer_tidal_aac_streaming_title), SettingsPage.AUDIO),
        SettingsSearchEntry(s(R.string.settings_download_quality_title), SettingsPage.AUDIO, listOf("format", "flac", "aac", "opus", "mp3")),
        SettingsSearchEntry(s(R.string.settings_flac_quality_title), SettingsPage.AUDIO, listOf("max", "hi res", "cd", "24 bit", "192")),
        SettingsSearchEntry(s(R.string.settings_auto_flac_upgrade_title), SettingsPage.AUDIO),
        SettingsSearchEntry(s(R.string.settings_tidal_aac_fallback_title), SettingsPage.AUDIO),
        SettingsSearchEntry(s(R.string.settings_lossy_fallback_title), SettingsPage.AUDIO, listOf("jiosaavn", "youtube")),
        SettingsSearchEntry(s(R.string.settings_youtube_quality_title), SettingsPage.AUDIO, listOf("opus", "bitrate")),
        SettingsSearchEntry(s(R.string.settings_hires_output_title), SettingsPage.AUDIO, listOf("usb", "dac", "dongle", "bit perfect", "bitperfect", "sample rate", "exclusive")),
        SettingsSearchEntry(s(R.string.settings_equalizer_title), SettingsPage.EQUALIZER, listOf("eq", "bass boost", "treble", "preset", "loudness", "normalization", "volume", "preamp")),
        // Lossless sources
        SettingsSearchEntry(s(R.string.settings_lossless_sources_title), SettingsPage.LOSSLESS_SOURCES, listOf("routing", "allowance", "daily limit", "quota", "tidal", "qobuz")),
        SettingsSearchEntry(s(R.string.settings_qobuz_account_title), SettingsPage.LOSSLESS_SOURCES, listOf("sign in", "login", "subscription")),
        SettingsSearchEntry(s(R.string.settings_supporter_offer), SettingsPage.LOSSLESS_SOURCES, listOf("supporter", "ko fi", "kofi", "unlock", "code", "donate")),
        SettingsSearchEntry(s(R.string.settings_advanced), SettingsPage.LOSSLESS_SOURCES, listOf("endpoint", "relay", "bitchord", "captcha", "reset")),
        // Accounts & Sync
        SettingsSearchEntry("Spotify", SettingsPage.ACCOUNTS, listOf("connect", "sign in", "login")),
        SettingsSearchEntry("YouTube Music", SettingsPage.ACCOUNTS, listOf("google", "connect", "sign in", "login")),
        SettingsSearchEntry("Discord", SettingsPage.ACCOUNTS, listOf("rich presence", "status")),
        SettingsSearchEntry("Last.fm", SettingsPage.ACCOUNTS, listOf("scrobble", "love")),
        SettingsSearchEntry("ListenBrainz", SettingsPage.ACCOUNTS, listOf("scrobble", "musicbrainz")),
        SettingsSearchEntry("Sync your likes", SettingsPage.ACCOUNTS, listOf("heart", "liked songs", "mirror")),
        SettingsSearchEntry(s(R.string.settings_yt_sync_title), SettingsPage.ACCOUNTS, listOf("watch history", "recommendations")),
        SettingsSearchEntry("Cloud storage", SettingsPage.ACCOUNTS, listOf("dropbox", "onedrive", "google drive", "folder")),
        SettingsSearchEntry("Back up my downloads to Google Drive", SettingsPage.ACCOUNTS, listOf("backup", "drive")),
        // Library & Storage
        SettingsSearchEntry("Download location", SettingsPage.LIBRARY, listOf("storage", "sd card", "folder", "external", "usb")),
        SettingsSearchEntry("Folder structure", SettingsPage.LIBRARY, listOf("artist", "album", "organize")),
        SettingsSearchEntry("Move library", SettingsPage.LIBRARY, listOf("sd card", "external")),
        SettingsSearchEntry("Reorganize existing tracks", SettingsPage.LIBRARY, listOf("organize", "folders")),
        SettingsSearchEntry("Backup", SettingsPage.LIBRARY, listOf("restore", "export", "import")),
        SettingsSearchEntry("Run recommendations when", SettingsPage.LIBRARY, listOf("wifi", "charging", "any network", "downloads")),
        SettingsSearchEntry(s(R.string.settings_stash_mixes_title), SettingsPage.LIBRARY, listOf("daily discover", "deep cuts", "first listen")),
        SettingsSearchEntry(s(R.string.settings_qobuz_discovery_title), SettingsPage.LIBRARY, listOf("new releases", "qobuz playlists", "top albums")),
        SettingsSearchEntry(s(R.string.settings_library_health_title), SettingsPage.LIBRARY, listOf("duplicates", "missing", "flac upgrade")),
        SettingsSearchEntry(s(R.string.settings_lyrics_recheck_title), SettingsPage.PLAYBACK, listOf("lyrics", "synced")),
        SettingsSearchEntry(s(R.string.settings_listening_history_title), SettingsPage.LIBRARY, listOf("plays", "stats", "import", "recap")),
        SettingsSearchEntry(s(R.string.settings_web_link_nav_title), SettingsPage.LIBRARY, listOf("web player", "browser", "qr", "pair", "mirror", "computer")),
        SettingsSearchEntry("Cloud folders", SettingsPage.LIBRARY, listOf("rescan", "dropbox", "onedrive")),
        SettingsSearchEntry("Storage used", SettingsPage.LIBRARY, listOf("space", "size")),
        // Appearance
        SettingsSearchEntry("Theme", SettingsPage.APPEARANCE, listOf("dark mode", "light mode", "night", "system")),
        SettingsSearchEntry(s(R.string.settings_amoled_title), SettingsPage.APPEARANCE, listOf("oled", "black", "dark")),
        SettingsSearchEntry(s(R.string.settings_ui_scale_title), SettingsPage.APPEARANCE, listOf("display size", "text size", "font", "zoom", "bigger", "smaller")),
        SettingsSearchEntry(s(R.string.settings_ambient_motion_title), SettingsPage.APPEARANCE, listOf("now playing", "animation", "background", "battery", "ambient style")),
        SettingsSearchEntry("Home layout", SettingsPage.HOME_LAYOUT, listOf("sections", "order", "made for you", "radios", "community", "moods", "decades", "your listening")),
        SettingsSearchEntry(s(R.string.settings_home_show_liked_title), SettingsPage.HOME_LAYOUT),
        SettingsSearchEntry("Open Stash on", SettingsPage.APPEARANCE, listOf("start tab", "launch", "startup")),
        // About & Help
        SettingsSearchEntry("Check for updates", SettingsPage.ABOUT, listOf("update", "version", "new version")),
        SettingsSearchEntry("Share diagnostics", SettingsPage.ABOUT, listOf("logs", "bug", "report", "problem")),
        SettingsSearchEntry("Share crash report", SettingsPage.ABOUT, listOf("crash", "bug")),
        SettingsSearchEntry("License", SettingsPage.ABOUT, listOf("open source")),
    )
}
