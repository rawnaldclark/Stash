package com.stash.core.data.social.ytmusic

import com.stash.core.data.social.LikeMirrorError
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.InnerTubeClient.RequestOutcome
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** A like or unlike YouTube Music didn't take is an exception carrying its status, so the outbox knows why. */
class YtMusicLibraryApiClientTest {

    private val innerTube = mockk<InnerTubeClient>()
    private val client = YtMusicLibraryApiClient(innerTube)
    private val ok = RequestOutcome(JsonObject(emptyMap()), 200)

    @Test fun `removeLike passes through on success`() = runBlocking<Unit> {
        coEvery { innerTube.setLiked("vid", false) } returns ok
        client.removeLike("vid") // must not throw
    }

    @Test fun `removeLike lifts a failure into YtMusicLibraryException`() = runBlocking<Unit> {
        coEvery { innerTube.setLiked("vid", false) } returns RequestOutcome(null, 500)
        val e = failure { client.removeLike("vid") }
        assertEquals(500, e.status)
    }

    @Test fun `likeVideo lifts a failure into YtMusicLibraryException`() = runBlocking<Unit> {
        coEvery { innerTube.setLiked("vid", true) } returns RequestOutcome(null, 401)
        val e = failure { client.likeVideo("vid") }
        assertEquals(LikeMirrorError.SIGNED_OUT, LikeMirrorError.of(e))
    }

    @Test fun `a 2xx with no body is still a failure`() = runBlocking<Unit> {
        coEvery { innerTube.setLiked("vid", true) } returns RequestOutcome(null, 200)
        failure { client.likeVideo("vid") }
        Unit
    }

    @Test fun `nothing came back reads as offline, not as a refusal`() = runBlocking<Unit> {
        coEvery { innerTube.setLiked("vid", true) } returns RequestOutcome(null, RequestOutcome.STATUS_NETWORK_ERROR)
        assertEquals(LikeMirrorError.OFFLINE, LikeMirrorError.of(failure { client.likeVideo("vid") }))
    }

    private suspend fun failure(block: suspend () -> Unit): YtMusicLibraryException {
        try {
            block()
        } catch (e: YtMusicLibraryException) {
            return e
        }
        fail("expected YtMusicLibraryException")
        error("unreachable")
    }
}
