package com.stash.core.data.di

import com.stash.core.data.backcover.AlbumBackCoverRepository
import com.stash.core.data.backcover.AlbumBackCovers
import com.stash.core.data.backcover.CoverArtArchiveClient
import com.stash.core.data.backcover.CoverArtArchiveClientImpl
import com.stash.core.data.backcover.DiscogsClient
import com.stash.core.data.backcover.DiscogsClientImpl
import com.stash.core.data.lastfm.LastFmTrackAlbums
import com.stash.core.data.lastfm.TrackAlbums
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackCoverModule {
    @Binds abstract fun bindCoverArtArchiveClient(impl: CoverArtArchiveClientImpl): CoverArtArchiveClient
    @Binds abstract fun bindDiscogsClient(impl: DiscogsClientImpl): DiscogsClient
    @Binds abstract fun bindAlbumBackCovers(impl: AlbumBackCoverRepository): AlbumBackCovers
    @Binds abstract fun bindTrackAlbums(impl: LastFmTrackAlbums): TrackAlbums
}
