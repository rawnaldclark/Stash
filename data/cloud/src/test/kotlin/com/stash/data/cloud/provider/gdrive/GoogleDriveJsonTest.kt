package com.stash.data.cloud.provider.gdrive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Drive v3 metadata, error classification and the picker's id list. */
class GoogleDriveJsonTest {

    @Test
    fun `files get metadata parses with size as a string and md5 as the version`() {
        val f = GoogleDriveJson.file(
            """{"id":"1AbCdEfGhIj","name":"01 Song.flac","mimeType":"audio/flac","size":"31457280",
               "md5Checksum":"9e107d9d372bb6826bd81d3542a419d6","headRevisionId":"0B-rev","modifiedTime":"2026-10-20T10:00:00.000Z",
               "parents":["0BFolder"],"trashed":false,"capabilities":{"canDownload":true}}""",
        )
        assertThat(f.sizeBytes).isEqualTo(31_457_280L)
        assertThat(f.version).isEqualTo("9e107d9d372bb6826bd81d3542a419d6")
        assertThat(f.parents).containsExactly("0BFolder")
        assertThat(GoogleDriveJson.classify(200, """{"id":"1AbCdEfGhIj","trashed":false}""")).isInstanceOf(GoogleDriveJson.Lookup.Present::class.java)
    }

    @Test
    fun `version falls back to headRevisionId then modifiedTime, size to zero`() {
        assertThat(GoogleDriveJson.file("""{"id":"x1234567","headRevisionId":"rev9"}""").version).isEqualTo("rev9")
        assertThat(GoogleDriveJson.file("""{"id":"x1234567","modifiedTime":"t"}""").version).isEqualTo("t")
        assertThat(GoogleDriveJson.file("""{"id":"x1234567","size":"nope"}""").sizeBytes).isEqualTo(0)
    }

    @Test
    fun `trashed, not found and lost grants are gone`() {
        assertThat(GoogleDriveJson.classify(200, """{"id":"abcdefgh","trashed":true}""")).isEqualTo(GoogleDriveJson.Lookup.Gone)
        assertThat(GoogleDriveJson.classify(404, """{"error":{"code":404,"errors":[{"reason":"notFound"}]}}""")).isEqualTo(GoogleDriveJson.Lookup.Gone)
        assertThat(GoogleDriveJson.classify(403, error403("appNotAuthorizedToFile"))).isEqualTo(GoogleDriveJson.Lookup.Gone)
        assertThat(GoogleDriveJson.classify(403, error403("insufficientFilePermissions"))).isEqualTo(GoogleDriveJson.Lookup.Gone)
    }

    @Test
    fun `rate limits are never read as gone`() {
        assertThat(GoogleDriveJson.classify(403, error403("userRateLimitExceeded"))).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        assertThat(GoogleDriveJson.classify(403, error403("rateLimitExceeded"))).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        assertThat(GoogleDriveJson.classify(403, error403("sharingRateLimitExceeded"))).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        // Daily quotas: the project's downloads are shared by every user, so these do happen.
        assertThat(GoogleDriveJson.classify(403, error403("dailyLimitExceeded"))).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        assertThat(GoogleDriveJson.classify(403, error403("quotaExceeded"))).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        assertThat(GoogleDriveJson.classify(403, error403("downloadQuotaExceeded"))).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        assertThat(GoogleDriveJson.classify(429, "")).isEqualTo(GoogleDriveJson.Lookup.Throttled)
        assertThat(GoogleDriveJson.classify(503, "")).isEqualTo(GoogleDriveJson.Lookup.Throttled)
    }

    @Test
    fun `an unexplained 403 or 400 fails rather than removing anything`() {
        assertThat(GoogleDriveJson.classify(403, "<html>forbidden</html>")).isEqualTo(GoogleDriveJson.Lookup.Failed(403))
        assertThat(GoogleDriveJson.classify(400, error403("badRequest"))).isEqualTo(GoogleDriveJson.Lookup.Failed(400))
    }

    @Test
    fun `abusive-file refusal is recognised only on a 403 that says so`() {
        val body = error403(GoogleDriveJson.ABUSE_REASON)
        assertThat(GoogleDriveJson.isAbusiveFileRefusal(403, body)).isTrue()
        assertThat(GoogleDriveProvider.isAbusiveFileRefusal(403, body.toByteArray())).isTrue()
        assertThat(GoogleDriveJson.isAbusiveFileRefusal(404, body)).isFalse()
        assertThat(GoogleDriveJson.isAbusiveFileRefusal(403, error403("userRateLimitExceeded"))).isFalse()
        assertThat(GoogleDriveProvider.isAbusiveFileRefusal(403, null)).isFalse()
    }

    @Test
    fun `picked ids come from a comma list, trimmed, de-duplicated and sanitised`() {
        assertThat(GoogleDriveJson.pickedIds("1AbCdEfGhIjK, 1ZyXwVuTsRqP,1AbCdEfGhIjK")).containsExactly("1AbCdEfGhIjK", "1ZyXwVuTsRqP").inOrder()
        assertThat(GoogleDriveJson.pickedIds(arrayListOf("1AbCdEfGhIjK"))).containsExactly("1AbCdEfGhIjK")
        assertThat(GoogleDriveJson.pickedIds(arrayOf("1AbCdEfGhIjK"))).containsExactly("1AbCdEfGhIjK")
        // Path pieces, query strings and empty items never reach a request URL.
        assertThat(GoogleDriveJson.pickedIds("../files,abc?alt=media,,short,1AbCdEfGhIjK")).containsExactly("1AbCdEfGhIjK")
        assertThat(GoogleDriveJson.pickedIds(null)).isEmpty()
        assertThat(GoogleDriveJson.pickedIds("")).isEmpty()
    }

    @Test
    fun `about gives the stable account id`() {
        val u = GoogleDriveJson.about("""{"user":{"displayName":"Ron","emailAddress":"ron@example.com","permissionId":"0123456789"}}""").user!!
        assertThat(u.permissionId).isEqualTo("0123456789")
        assertThat(u.emailAddress).isEqualTo("ron@example.com")
    }

    @Test
    fun `cursor round-trips and anything unreadable restarts a full pass`() {
        val c = GoogleDriveProvider.Cursor(GoogleDriveProvider.Mode.NEW, "1AbC")
        assertThat(GoogleDriveProvider.Cursor.parse(c.encode())).isEqualTo(c)
        assertThat(GoogleDriveProvider.Cursor.parse(null)).isEqualTo(GoogleDriveProvider.Cursor(GoogleDriveProvider.Mode.FULL, null))
        assertThat(GoogleDriveProvider.Cursor.parse("done").mode).isEqualTo(GoogleDriveProvider.Mode.DONE)
        assertThat(GoogleDriveProvider.Cursor.parse("https://graph.microsoft.com/x")).isEqualTo(GoogleDriveProvider.Cursor(GoogleDriveProvider.Mode.FULL, null))
    }

    @Test
    fun `the media url carries no token`() {
        val url = GoogleDriveProvider.mediaUrl("1AbCdEfGhIjK")
        assertThat(url).isEqualTo("https://www.googleapis.com/drive/v3/files/1AbCdEfGhIjK?alt=media&supportsAllDrives=true")
        assertThat(url).doesNotContain("token")
    }

    private fun error403(reason: String) = """{"error":{"code":403,"message":"m","errors":[{"domain":"usageLimits","reason":"$reason","message":"m"}]}}"""
}
