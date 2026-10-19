package com.stash.core.network.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The shared client's debug logger prints request URLs. Qobuz's login is a GET
 * carrying email + MD5(password), a usable credential that reached logcat and the
 * diagnostics bundle.
 */
class LogRedactionTest {

    @Test
    fun `the Qobuz login line keeps no email or password`() {
        val line = "--> GET https://www.qobuz.com/api.json/0.2/user/login" +
            "?email=someone%40example.com&password=5f4dcc3b5aa765d61d8327deb882cf99&app_id=798273057"

        val redacted = LogRedaction.redactSecrets(line)

        assertEquals(
            "--> GET https://www.qobuz.com/api.json/0.2/user/login?email=██&password=██&app_id=798273057",
            redacted,
        )
        assertFalse(redacted.contains("5f4dcc3b"))
    }

    @Test
    fun `parameter names match case-insensitively and only as whole names`() {
        assertEquals(
            "<-- 200 https://h/x?Password=██&oldpassword_hint=keep (12ms)",
            LogRedaction.redactSecrets("<-- 200 https://h/x?Password=hunter2&oldpassword_hint=keep (12ms)"),
        )
    }

    @Test
    fun `a Last_fm session key, signature and auth token are never logged`() {
        val line = "--> GET https://ws.audioscrobbler.com/2.0/?api_key=k&api_sig=0123abcd&method=user.getRecentTracks" +
            "&sk=SESSIONKEY123&token=TOKEN456&user=rawnclark"

        val redacted = LogRedaction.redactSecrets(line)

        assertEquals(
            "--> GET https://ws.audioscrobbler.com/2.0/?api_key=k&api_sig=██&method=user.getRecentTracks&sk=██&token=██&user=rawnclark",
            redacted,
        )
        assertFalse(redacted.contains("SESSIONKEY123"))
    }

    @Test
    fun `lines without secrets are untouched`() {
        val line = "--> GET https://www.qobuz.com/api.json/0.2/catalog/search?query=radiohead&limit=10"
        assertEquals(line, LogRedaction.redactSecrets(line))
    }
}
