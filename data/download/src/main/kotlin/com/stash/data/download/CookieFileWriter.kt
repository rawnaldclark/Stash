package com.stash.data.download

import java.io.File

/**
 * Converts a browser cookie header string into a Netscape HTTP Cookie File
 * that yt-dlp can read via its --cookies flag.
 *
 * Input format:  "SAPISID=abc123; SID=def456; __Secure-3PSID=ghi789"
 * Output format: Netscape HTTP Cookie File (tab-separated fields)
 *
 * Security: callers should write to [Context.noBackupFilesDir] and delete
 * the file in a finally block after use.
 */
object CookieFileWriter {

    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val DOMAIN = ".youtube.com"

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

            cookieString.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) return@forEach
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex <= 0) return@forEach

                val name = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                // Format: domain \t flag \t path \t secure \t expiry \t name \t value
                writer.write("$DOMAIN\tTRUE\t/\tTRUE\t0\t$name\t$value")
                writer.newLine()
            }
        }
    }
}
