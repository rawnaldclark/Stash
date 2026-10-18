package com.stash.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which tab is on screen, from a back stack's route names bottom to top: the rule the tab bar picks
 * its tab by and the scaffold's tab switch decides "already here" by.
 */
class TabRoutesTest {

    private val home = HomeRoute::class.qualifiedName
    private val library = LibraryRoute::class.qualifiedName
    private val settings = SettingsRoute::class.qualifiedName
    private val sync = SyncRoute::class.qualifiedName

    /** A detail route as the graph names one with arguments. */
    private val playlistDetail = PlaylistDetailRoute::class.qualifiedName + "/{playlistId}?openShare={openShare}"

    @Test
    fun `the start tab alone is the tab on screen, whichever it is`() {
        assertThat(tabRouteOf(listOf(library))).isEqualTo(library)
        assertThat(tabRouteOf(listOf(home))).isEqualTo(home)
    }

    @Test
    fun `a tab switch that restores a stack on a detail screen still names that tab`() {
        val stack = listOf(home, settings, SettingsAppearanceRoute::class.qualifiedName)
        assertThat(tabRouteOf(stack)).isEqualTo(settings)
    }

    @Test
    fun `Now Playing over a tab's detail screen keeps that tab`() {
        val stack = listOf(home, library, playlistDetail, NowPlayingRoute::class.qualifiedName)
        assertThat(tabRouteOf(stack)).isEqualTo(library)
    }

    @Test
    fun `the captcha deep link sits in the Settings tab`() {
        val stack = listOf(home, settings, SquidWtfCaptchaRoute::class.qualifiedName)
        assertThat(tabRouteOf(stack)).isEqualTo(settings)
    }

    @Test
    fun `the top-most tab wins over the start tab beneath it`() {
        assertThat(tabRouteOf(listOf(home, playlistDetail, sync))).isEqualTo(sync)
    }

    @Test
    fun `an empty or tabless stack names no tab`() {
        assertThat(tabRouteOf(emptyList())).isNull()
        assertThat(tabRouteOf(listOf(null, playlistDetail))).isNull()
    }
}
