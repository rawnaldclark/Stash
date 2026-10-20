package com.stash.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudRefTest {

    @Test
    fun `round trips an item id and cTag with reserved characters`() {
        val ref = CloudRef(CloudProvider.ONEDRIVE, 12, "8A0B1C!123", "\"c:{9F2A-11},3\"")
        val uri = ref.toUri()
        assertThat(uri).startsWith("stash-cloud://onedrive/12/8A0B1C%21123?v=")
        assertThat(uri).doesNotContain("{")
        assertThat(CloudRef.parse(uri)).isEqualTo(ref)
    }

    @Test
    fun `cache key is provider, item and version, never a URL`() {
        val ref = CloudRef(CloudProvider.ONEDRIVE, 3, "ITEM", "ctag-1")
        assertThat(ref.cacheKey).isEqualTo("onedrive:ITEM:ctag-1")
        assertThat(ref.copy(version = "ctag-2").cacheKey).isNotEqualTo(ref.cacheKey)
    }

    @Test
    fun `parses without a version and with extra query params`() {
        assertThat(CloudRef.parse("stash-cloud://onedrive/5/abc")).isEqualTo(CloudRef(CloudProvider.ONEDRIVE, 5, "abc"))
        assertThat(CloudRef.parse("stash-cloud://onedrive/5/abc?x=1&v=9")!!.version).isEqualTo("9")
    }

    @Test
    fun `plus signs in ids survive`() {
        val ref = CloudRef(CloudProvider.ONEDRIVE, 1, "a+b c")
        assertThat(CloudRef.parse(ref.toUri())!!.itemId).isEqualTo("a+b c")
    }

    @Test
    fun `rejects anything that is not a cloud ref`() {
        listOf(
            null,
            "",
            "stash-resolve://track/1",
            "https://graph.microsoft.com/v1.0/me",
            "stash-cloud://box/1/abc", // not a provider Stash knows
            "stash-cloud://onedrive/0/abc",
            "stash-cloud://onedrive/x/abc",
            "stash-cloud://onedrive/1/",
            "stash-cloud://onedrive/1/a/b",
        ).forEach { assertThat(CloudRef.parse(it)).isNull() }
    }

    @Test
    fun `google drive refs round-trip with the md5 as version`() {
        val ref = CloudRef(CloudProvider.GOOGLE_DRIVE, 7, "1AbCdEfGhIj-_K", "9e107d9d372bb6826bd81d3542a419d6")
        assertThat(ref.toUri()).isEqualTo("stash-cloud://gdrive/7/1AbCdEfGhIj-_K?v=9e107d9d372bb6826bd81d3542a419d6")
        assertThat(CloudRef.parse(ref.toUri())).isEqualTo(ref)
        assertThat(ref.cacheKey).isEqualTo("gdrive:1AbCdEfGhIj-_K:9e107d9d372bb6826bd81d3542a419d6")
    }
}
