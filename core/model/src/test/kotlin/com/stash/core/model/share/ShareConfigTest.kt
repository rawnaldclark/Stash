package com.stash.core.model.share

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class ShareConfigTest {
    @After fun tearDown() { ShareConfig.apiOverride = null }

    @Test fun `share calls go to stashfm-app, then the old host`() {
        assertThat(ShareConfig.API_BASE_URLS)
            .containsExactly("https://stashfm.app", "https://stash-share.rawnaldclark.workers.dev").inOrder()
    }

    @Test fun `a debug build's local Worker takes every share call`() {
        ShareConfig.apiOverride = "http://127.0.0.1:8811"
        assertThat(ShareConfig.API_BASE_URLS).containsExactly("http://127.0.0.1:8811")
    }

    @Test fun `links still come only from the live hosts`() {
        ShareConfig.apiOverride = "http://127.0.0.1:8811"
        assertThat(ShareConfig.HOSTS).containsExactly("stashfm.app", "stash-share.rawnaldclark.workers.dev")
        assertThat(ShareLinks.parse("http://127.0.0.1:8811/m/AbCd1234")).isNull()
        assertThat(ShareLinks.parse("https://stashfm.app/m/AbCd1234")).isEqualTo(ShareLinks.Parsed.Mix("AbCd1234"))
    }
}
