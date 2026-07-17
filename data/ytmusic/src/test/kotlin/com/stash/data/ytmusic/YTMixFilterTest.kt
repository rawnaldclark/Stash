package com.stash.data.ytmusic

import com.stash.data.ytmusic.YTMusicApiClient.Companion.isAllowedMixPlaylist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [isAllowedMixPlaylist], the pure keep-check that decides
 * which home-feed playlist IDs the sync widens to.
 *
 * The rule: reject albums/user-playlists/channels by prefix first, then keep
 * any personalized radio (RD* or VLRD*) plus the built-in My Mix / Liked Music.
 */
class YTMixFilterTest {

    @Test fun keepsDailyAndDiscover() = assertTrue(isAllowedMixPlaylist("RDTMAK5uy_abc"))
    @Test fun keepsMyMix()            = assertTrue(isAllowedMixPlaylist("RDMM"))
    @Test fun keepsPersonalRadio()    = assertTrue(isAllowedMixPlaylist("RDAT1234"))   // newly allowed
    @Test fun keepsEmMix()            = assertTrue(isAllowedMixPlaylist("RDEMxyz"))     // newly allowed
    @Test fun rejectsAlbum()          = assertFalse(isAllowedMixPlaylist("OLAK5uy_abc"))
    @Test fun rejectsUserPlaylist()   = assertFalse(isAllowedMixPlaylist("VLPLabc"))
    @Test fun rejectsChannel()        = assertFalse(isAllowedMixPlaylist("UCabc"))
    @Test fun rejectsAlbumBrowse()    = assertFalse(isAllowedMixPlaylist("MPREabc"))
}
