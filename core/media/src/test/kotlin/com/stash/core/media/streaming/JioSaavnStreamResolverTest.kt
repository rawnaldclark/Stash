package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.jiosaavn.JioSaavnResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JioSaavnStreamResolverTest {

    private val jioSaavn: JioSaavnResolver = mockk { coEvery { resolve(any(), any(), any()) } returns null }

    @Test
    fun `JioSaavn streams the policy's variant - 320, 160 under Data saver with Lossless on, 96 with it off`() = runTest {
        for ((quality, kbps) in listOf(LossyQuality.FULL to 320, LossyQuality.DATA_SAVER to 160, LossyQuality.LOWEST to 96)) {
            val policy: StreamQualityPolicy = mockk { coEvery { lossyQuality() } returns quality }

            JioSaavnStreamResolver(jioSaavn, policy).resolve(TrackEntity(title = "Kesariya", artist = "Arijit Singh"))

            coVerify { jioSaavn.resolve(any(), kbps, false) }
        }
    }
}
