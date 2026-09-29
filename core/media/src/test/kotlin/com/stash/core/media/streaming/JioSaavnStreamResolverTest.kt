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
    fun `Save Data streams JioSaavn's 96 kbps variant, otherwise 320`() = runTest {
        for ((saveData, kbps) in listOf(true to 96, false to 320)) {
            val policy: StreamQualityPolicy = mockk { coEvery { saveData() } returns saveData }

            JioSaavnStreamResolver(jioSaavn, policy).resolve(TrackEntity(title = "Kesariya", artist = "Arijit Singh"))

            coVerify { jioSaavn.resolve(any(), kbps, false) }
        }
    }
}
