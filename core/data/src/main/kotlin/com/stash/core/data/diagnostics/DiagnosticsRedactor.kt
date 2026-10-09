package com.stash.core.data.diagnostics

/**
 * Scrubs secrets from a diagnostics bundle. Backstop only — the bundle reports
 * auth as booleans, never raw tokens — but error strings and log lines can embed
 * cookies/headers/emails from upstream API failures. High-precision named patterns
 * to avoid nuking stack traces.
 */
object DiagnosticsRedactor {
    private val patterns: List<Pair<Regex, String>> = listOf(
        // Spotify sp_dc + Google/YT auth cookies: keep the key, redact the value.
        Regex("""(?i)\b(sp_dc|SAPISID|APISID|HSID|SSID|SIDCC|SID|__Secure-[\w-]+|LOGIN_INFO)=[^;\s"']+""")
            to "$1=[REDACTED]",
        // Generic fallback: any Cookie:/Set-Cookie: header line whose value the named
        // pattern above didn't already scrub — redact the whole value to EOL. The
        // negative lookahead skips lines already containing [REDACTED] so we don't
        // collapse (and lose) the precise named-cookie redactions made just above.
        Regex("""(?i)\b(set-cookie|cookie):(?![^\n]*\[REDACTED])\s*.*""") to "$1: [REDACTED]",
        // Authorization / Bearer headers. Bearer first so its long token value is
        // scrubbed before the header catch-all collapses "Authorization: Bearer".
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]+""") to "Bearer [REDACTED]",
        Regex("""(?i)\bauthorization:\s*\S+""") to "Authorization: [REDACTED]",
        // Named token / key fields.
        Regex("""(?i)\b(access_token|refresh_token|id_token|api_key|apikey|client_secret)["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]+["']?""")
            to "$1=[REDACTED]",
        // Email addresses — account PII that can surface in errors/logs/source rows.
        Regex("""(?i)\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""") to "[REDACTED_EMAIL]",
        // Google Cast media server URLs (http://<phone's LAN IP>:<port>/<token>/m/<n>): the phone's
        // address and the URL token, should a log line or a Cast SDK message carry one. Address
        // first, then any token left on its own (a path logged without the host).
        Regex("""(?i)\bhttp://(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}/[0-9a-f]{32}/""") to "http://[REDACTED_LAN]/[REDACTED]/",
        Regex("""(?i)/[0-9a-f]{32}/([ma])/(\d+)""") to "/[REDACTED]/$1/$2",
    )

    fun redact(text: String): String =
        patterns.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}
