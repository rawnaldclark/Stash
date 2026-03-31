package com.stash.data.download

import java.io.File

/**
 * Converts a browser cookie header string into a Netscape HTTP Cookie File
 * that yt-dlp can read via its `--cookies` flag.
 *
 * ## Netscape format
 * Each cookie is a tab-separated line with 7 fields:
 * `domain  flag  path  secure  expiry  name  value`
 *
 * ## yt-dlp cookie validation
 * yt-dlp's YouTube extractor checks `_has_auth_cookies` which requires:
 * - `LOGIN_INFO` cookie present **AND**
 * - At least one of: `SAPISID`, `__Secure-1PAPISID`, `__Secure-3PAPISID`
 *
 * If `LOGIN_INFO` is missing, yt-dlp warns "cookies are no longer valid"
 * even when SAPISID is present. All cookies must be on `.youtube.com` domain
 * so that yt-dlp's `_get_cookies('https://www.youtube.com')` finds them.
 *
 * ## Expiry handling
 * A value of `0` means "session cookie" which some cookie jars treat as
 * "already expired". We use a far-future timestamp to keep cookies alive.
 *
 * Security: callers should write to [Context.noBackupFilesDir] and delete
 * the file in a finally block after use.
 */
object CookieFileWriter {

    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val DOMAIN = ".youtube.com"

    // 2035-01-01 — far-future expiry so cookies aren't treated as expired sessions
    private const val FAR_FUTURE_EXPIRY = "2051222400"

    /** Cookie names that yt-dlp's YouTube extractor requires for authentication. */
    private val REQUIRED_COOKIES = setOf("LOGIN_INFO", "SAPISID", "__Secure-3PAPISID")

    /**
     * Checks whether the cookie string contains the cookies required by yt-dlp.
     *
     * @return A list of missing required cookie names, empty if all present.
     */
    fun findMissingCookies(cookieString: String): List<String> {
        val names = parseCookies(cookieString).map { it.first }.toSet()
        // Need LOGIN_INFO, and at least one of SAPISID or __Secure-3PAPISID
        val missing = mutableListOf<String>()
        if ("LOGIN_INFO" !in names) missing.add("LOGIN_INFO")
        if ("SAPISID" !in names && "__Secure-3PAPISID" !in names) missing.add("SAPISID or __Secure-3PAPISID")
        return missing
    }

    /**
     * Writes the cookie string to [outputFile] in Netscape format.
     *
     * @param cookieString The raw cookie header value (semicolon-separated key=value pairs).
     * @param outputFile   The file to write. Will be created or overwritten.
     */
    fun write(cookieString: String, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.bufferedWriter().use { writer ->
            writer.write(HEADER)
            writer.newLine()
            writer.newLine()

            for ((name, value) in parseCookies(cookieString)) {
                val secure = if (name.startsWith("__Secure-")) "TRUE" else "FALSE"
                // Format: domain \t flag \t path \t secure \t expiry \t name \t value
                writer.write("$DOMAIN\tTRUE\t/\t$secure\t$FAR_FUTURE_EXPIRY\t$name\t$value")
                writer.newLine()
            }
        }
    }

    /**
     * Parses a semicolon-separated cookie header into name/value pairs.
     */
    private fun parseCookies(cookieString: String): List<Pair<String, String>> {
        return cookieString.split(";").mapNotNull { pair ->
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            name to value
        }
    }
}
