package com.stash.data.lyrics.source

import android.content.Context
import android.content.pm.PackageInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.LyricLine
import com.stash.addons.contract.Lyrics
import com.stash.addons.contract.Manifest
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.data.lyrics.di.LyricsModule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.IOException

/** The add-ons' place in the lyrics chain (Stash Add-ons v1, phase 1: empty, so inert). */
class AddonLyricsSourceTest {

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private fun addon(id: String) = InstalledAddon(
        base = "https://$id.example/",
        manifest = Manifest(stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER, resources = listOf("lyrics")),
    )

    private val query = LyricsQuery(
        trackId = 1, title = "One More Time", artist = "Daft Punk", album = null, albumArtist = null,
        durationMs = 320_000, youtubeVideoId = null,
    )

    @Test fun `an empty registry is a miss at once, and asks nothing`() = runTest {
        // Strict mock: any call to the client fails the test.
        assertThat(AddonLyricsSource(Registry(emptyList()), mockk()).resolve(query)).isNull()
    }

    @Test fun `the chain keeps its order with the add-ons between KuGou and InnerTube`() {
        val apple = mockk<AppleTtmlLyricsSource>()
        val lrclib = mockk<LrclibLyricsSource>()
        val kugou = mockk<KugouLyricsSource>()
        val ytmusic = mockk<YtMusicLyricsSource>()
        val spicy = mockk<SpicyLyricsSource>()
        val addons = AddonLyricsSource(Registry(emptyList()), mockk())
        val context = mockk<Context> {
            every { packageName } returns "com.stash.test"
            every { packageManager } returns mockk { every { getPackageInfo("com.stash.test", 0) } returns PackageInfo() }
        }
        val chain = LyricsModule.provideLyricsSources(
            apple, lrclib, kugou, ytmusic, spicy, addons, OkHttpClient(), context, mockk(relaxed = true), mockk(relaxed = true),
        )
        assertThat(chain.take(6)).containsExactly(apple, spicy, lrclib, kugou, addons, ytmusic).inOrder()
        assertThat(chain.drop(6).map { it.id })
            .containsExactly(IsrcTtmlLyricsSource.LRC_RED_ID, IsrcTtmlLyricsSource.BINIMUM_ID).inOrder()
        assertThat(addons.onDemandOnly).isFalse()
    }

    @Test fun `the first add-on with lyrics answers, as LRC`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { lyrics(match { it.id == "a" }, any(), any(), any()) } returns
                AddonResult.Failed(AddonFailure.Refused(ErrorCode.NOT_FOUND, 404, null, null))
            coEvery { lyrics(match { it.id == "b" }, any(), any(), any()) } returns AddonResult.Ok(
                Lyrics(synced = listOf(LyricLine(startMs = 0, text = "One more time"), LyricLine(startMs = 65_430, text = "We're gonna celebrate"))),
            )
        }
        val r = AddonLyricsSource(Registry(listOf(addon("a"), addon("b"))), client).resolve(query)!!
        assertThat(r.sourceId).isEqualTo("addon:b")
        assertThat(r.syncedLrc).isEqualTo("[00:00.00]One more time\n[01:05.43]We're gonna celebrate")
        assertThat(r.plainText).isEqualTo("One more time\nWe're gonna celebrate")
    }

    @Test fun `every add-on saying not_found is a definitive miss`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { lyrics(any(), any(), any(), any()) } returns AddonResult.Failed(AddonFailure.Refused(ErrorCode.NOT_FOUND, 404, null, null))
        }
        assertThat(AddonLyricsSource(Registry(listOf(addon("a"))), client).resolve(query)).isNull()
    }

    @Test fun `an add-on that was asked and could not answer is never filed as no lyrics`() = runTest {
        for (failure in listOf(
            AddonFailure.Timeout,
            AddonFailure.Network,
            AddonFailure.InvalidResponse("/synced", "not a list"),
            AddonFailure.Refused(ErrorCode.UNAVAILABLE, 500, null, null),
        )) {
            val client: HttpAddonClient = mockk {
                coEvery { lyrics(any(), any(), any(), any()) } returns AddonResult.Failed(failure)
            }
            val e = runCatching { AddonLyricsSource(Registry(listOf(addon("a"))), client).resolve(query) }.exceptionOrNull()
            assertWithMessage("$failure").that(e).isInstanceOf(IOException::class.java)
        }
    }

    @Test fun `an add-on not asked, or refusing on its own terms, is absent and the song can settle`() = runTest {
        // A paused add-on (a 401's hour, an open breaker) must not keep every lyric-less song
        // walking the whole chain again on each open, for as long as it stays paused.
        for (failure in listOf(
            AddonFailure.Paused,
            AddonFailure.NotOffered,
            AddonFailure.Refused(ErrorCode.RATE_LIMITED, 429, 30, null),
            AddonFailure.Refused(ErrorCode.UNAUTHORIZED, 401, null, null),
            AddonFailure.Refused(ErrorCode.QUOTA_EXCEEDED, 429, 600, null),
        )) {
            val client: HttpAddonClient = mockk {
                coEvery { lyrics(any(), any(), any(), any()) } returns AddonResult.Failed(failure)
            }
            val r = runCatching { AddonLyricsSource(Registry(listOf(addon("a"))), client).resolve(query) }
            assertWithMessage("$failure").that(r.exceptionOrNull()).isNull()
            assertWithMessage("$failure").that(r.getOrNull()).isNull()
        }
    }
}
