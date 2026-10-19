package com.stash.core.network.di

/**
 * Masks credential query parameters in an HTTP log line before it reaches logcat.
 *
 * The shared client logs at BASIC in debug builds, which prints each request's full
 * URL (`--> GET https://…?…`). Qobuz's login is a GET whose query carries the user's
 * email and MD5(password), and the MD5 is what Qobuz accepts as the password, so a
 * usable credential landed in logcat, and from there in the diagnostics bundle
 * (LogcatCapture tails the app's logcat). Beta testers run debug builds and connect
 * their own Qobuz accounts. OkHttp 4's logger can only redact headers, so the line
 * is rewritten here instead.
 */
internal object LogRedaction {

    /** Query parameter names whose values are never logged (matched case-insensitively). */
    private val SECRET_PARAMS = listOf(
        "password",
        "email",
        "username",
        "user_auth_token",
        "app_secret",
        // Last.fm: a session key is a lasting credential; the signature and auth token travel with it.
        "sk",
        "api_sig",
        "token",
    )

    private val SECRET_QUERY_PARAM = Regex(
        "([?&](?:${SECRET_PARAMS.joinToString("|")})=)[^&#\\s]*",
        RegexOption.IGNORE_CASE,
    )

    /** [line] with each secret parameter's value replaced by `██`. */
    fun redactSecrets(line: String): String =
        SECRET_QUERY_PARAM.replace(line) { it.groupValues[1] + "██" }
}
