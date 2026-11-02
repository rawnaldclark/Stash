package com.stash.feature.search

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.SearchUnavailableException
import kotlinx.coroutines.CancellationException
import org.junit.Test

/** [searchErrorMessage]: plain words for a failed search, never for a cancelled one. */
class SearchErrorCopyTest {

    @Test fun `no connection reads as offline`() {
        assertThat(searchErrorMessage(java.net.UnknownHostException("Unable to resolve host music.youtube.com")))
            .isEqualTo(SearchErrorCopy.OFFLINE)
        assertThat(searchErrorMessage(java.net.ConnectException("Failed to connect")))
            .isEqualTo(SearchErrorCopy.OFFLINE)
        assertThat(searchErrorMessage(java.net.NoRouteToHostException("No route")))
            .isEqualTo(SearchErrorCopy.OFFLINE)
    }

    @Test fun `a search YouTube never answered reads as offline`() {
        assertThat(searchErrorMessage(SearchUnavailableException(statusCode = -1)))
            .isEqualTo(SearchErrorCopy.OFFLINE)
    }

    @Test fun `no error copy says offline, which is the mode's name`() {
        for (copy in listOf(SearchErrorCopy.OFFLINE, SearchErrorCopy.SLOW, SearchErrorCopy.BUSY, SearchErrorCopy.NO_ANSWER)) {
            assertThat(copy.lowercase()).doesNotContain("offline")
        }
    }

    @Test fun `a timeout says it was slow`() {
        assertThat(searchErrorMessage(java.net.SocketTimeoutException("timeout")))
            .isEqualTo(SearchErrorCopy.SLOW)
    }

    @Test fun `rate limiting says to wait`() {
        assertThat(searchErrorMessage(SearchUnavailableException(statusCode = 429)))
            .isEqualTo(SearchErrorCopy.BUSY)
    }

    @Test fun `a server error or anything else says YouTube didn't answer`() {
        assertThat(searchErrorMessage(SearchUnavailableException(statusCode = 503)))
            .isEqualTo(SearchErrorCopy.NO_ANSWER)
        assertThat(searchErrorMessage(IllegalStateException("parser blew up")))
            .isEqualTo(SearchErrorCopy.NO_ANSWER)
    }

    @Test fun `the raw exception text never reaches the screen`() {
        val raw = "Unable to resolve host music.youtube.com: No address associated with hostname"
        assertThat(searchErrorMessage(java.net.UnknownHostException(raw))).doesNotContain("resolve host")
        assertThat(searchErrorMessage(RuntimeException(raw))).doesNotContain("resolve host")
    }

    @Test fun `a cancellation is not an error`() {
        assertThat(searchErrorMessage(CancellationException("superseded by a newer keystroke"))).isNull()
    }
}
