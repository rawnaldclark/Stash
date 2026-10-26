package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NowPlayingPreferenceTest {
    private lateinit var context: Context
    private lateinit var preference: NowPlayingPreference
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("now_playing_preference")
        if (file.exists()) file.delete()
        preference = NowPlayingPreference(context)
    }

    @After fun tearDown() {
        // The preferencesDataStore delegate is one instance per process, so deleting the file does
        // not reset what it holds in memory: put the pins back for whichever test runs next.
        kotlinx.coroutines.runBlocking { preference.resetPinnedActions() }
        if (file.exists()) file.delete()
    }

    @Test fun ambientAnimation_defaultsToTrue() = runTest {
        assertTrue(preference.ambientAnimationEnabled.first())
    }

    @Test fun `a fresh install opens Now Playing as the owner set it up`() = runTest {
        // Full art on, close to the whole cover, a slow pan over a solid blend, the title at the left.
        assertTrue(preference.fullArt.first())
        org.junit.Assert.assertEquals(1.1f, preference.fullArtZoom.first(), 0.0001f)
        org.junit.Assert.assertEquals(FullArtPan.SLOW, preference.fullArtPan.first())
        org.junit.Assert.assertEquals(FullArtBlend.SOLID, preference.fullArtBlend.first())
        org.junit.Assert.assertEquals(NpTitleAlign.START, preference.titleAlign.first())
        // Unchanged: the sleep timer beside the title, Discogs art and the cycle on.
        org.junit.Assert.assertEquals(TitleButton.SLEEP_TIMER, preference.titleButton.first())
        assertTrue(preference.discogsArt.first())
        assertTrue(preference.cycleAlbumArt.first())
    }

    @Test fun ambientStyle_defaultsToSoftGlow() = runTest {
        org.junit.Assert.assertEquals(AmbientStyle.SOFT_GLOW, preference.ambientStyle.first())
    }

    @Test fun ambientStyle_persistsClassicAndBack() = runTest {
        preference.setAmbientStyle(AmbientStyle.CLASSIC)
        org.junit.Assert.assertEquals(AmbientStyle.CLASSIC, preference.ambientStyle.first())
        preference.setAmbientStyle(AmbientStyle.SOFT_GLOW)
        org.junit.Assert.assertEquals(AmbientStyle.SOFT_GLOW, preference.ambientStyle.first())
    }

    @Test fun pinnedActions_defaultToRadioAndQueue() = runTest {
        org.junit.Assert.assertEquals(NowPlayingAction.DefaultPins, preference.pinnedActions.first())
    }

    @Test fun pinnedActions_persistIncludingEmpty() = runTest {
        val pins = setOf(NowPlayingAction.RADIO, NowPlayingAction.LYRICS, NowPlayingAction.SHARE)
        preference.setPinnedActions(pins)
        org.junit.Assert.assertEquals(pins, preference.pinnedActions.first())
        // Unpinning everything is a real choice, not "unset": it must not fall back to the default.
        preference.setPinnedActions(emptySet())
        org.junit.Assert.assertEquals(emptySet<NowPlayingAction>(), preference.pinnedActions.first())
    }

    @Test fun pinnedActions_resetRestoresTheDefault() = runTest {
        preference.setPinnedActions(setOf(NowPlayingAction.SHARE))
        preference.resetPinnedActions()
        org.junit.Assert.assertEquals(NowPlayingAction.DefaultPins, preference.pinnedActions.first())
    }

    @Test fun togglePin_twoConcurrentTogglesBothLand() = runTest {
        // Two quick taps in the editor: each toggle is one DataStore transaction, so the second
        // reads the first's write instead of a stale set (which used to drop a pin).
        kotlinx.coroutines.coroutineScope {
            launch(kotlinx.coroutines.Dispatchers.IO) { preference.togglePin(NowPlayingAction.SHARE) }
            launch(kotlinx.coroutines.Dispatchers.IO) { preference.togglePin(NowPlayingAction.DOWNLOAD) }
        }
        org.junit.Assert.assertEquals(
            setOf(NowPlayingAction.RADIO, NowPlayingAction.QUEUE, NowPlayingAction.SHARE, NowPlayingAction.DOWNLOAD),
            preference.pinnedActions.first(),
        )
    }

    @Test fun togglePin_capHoldsUnderConcurrentPins() = runTest {
        // Every other action races for the three free places: exactly three win, the rest are refused.
        val results = kotlinx.coroutines.coroutineScope {
            NowPlayingAction.entries.filter { it !in NowPlayingAction.DefaultPins }.map { action ->
                async(kotlinx.coroutines.Dispatchers.IO) { preference.togglePin(action) }
            }.map { it.await() }
        }
        org.junit.Assert.assertEquals(NowPlayingAction.MAX_PINS - 2, results.count { it })
        org.junit.Assert.assertEquals(NowPlayingAction.MAX_PINS, preference.pinnedActions.first().size)
    }

    @Test fun togglePin_unpinsAndReportsTheRefusal() = runTest {
        assertTrue(preference.togglePin(NowPlayingAction.RADIO))
        org.junit.Assert.assertEquals(setOf(NowPlayingAction.QUEUE), preference.pinnedActions.first())
        preference.setPinnedActions(NowPlayingAction.entries.take(NowPlayingAction.MAX_PINS).toSet())
        assertFalse(preference.togglePin(NowPlayingAction.FLAG_WRONG_MATCH))
    }

    @Test fun ambientAnimation_persistsFalseAndTrue() = runTest {
        preference.setAmbientAnimationEnabled(false)
        assertFalse(preference.ambientAnimationEnabled.first())
        preference.setAmbientAnimationEnabled(true)
        assertTrue(preference.ambientAnimationEnabled.first())
    }
}
