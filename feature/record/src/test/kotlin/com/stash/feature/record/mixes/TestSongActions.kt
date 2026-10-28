package com.stash.feature.record.mixes

import com.stash.core.data.radio.RadioSeed
import com.stash.core.model.Playlist
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import com.stash.feature.record.bridge.RecordSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** [RecordSongActions] over seams: by default no song has a row, and nothing is done. */
internal fun testSongActions(
    rows: suspend (List<RecordSong>) -> List<Track?> = { songs -> songs.map { null } },
    like: suspend (Long) -> Unit = {},
    unlike: suspend (Long) -> Unit = {},
    addNext: suspend (Track) -> Boolean = { true },
    addToQueue: suspend (Track) -> Boolean = { true },
    startRadio: suspend (RadioSeed.Song) -> RadioStartResult = { RadioStartResult.Started },
    addToPlaylist: suspend (Long, Long) -> Unit = { _, _ -> },
    createPlaylist: suspend (String) -> Long = { 1L },
    playlists: Flow<List<Playlist>> = flowOf(emptyList()),
    toast: (SongLine) -> Unit = {},
    scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) = RecordSongActions(rows, like, unlike, addNext, addToQueue, startRadio, addToPlaylist, createPlaylist, playlists, toast, scope)
