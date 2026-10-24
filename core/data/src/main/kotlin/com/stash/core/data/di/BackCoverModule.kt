package com.stash.core.data.di

import com.stash.core.data.backcover.AlbumBackCoverRepository
import com.stash.core.data.backcover.AlbumBackCovers
import com.stash.core.data.backcover.CoverArtArchiveClient
import com.stash.core.data.backcover.CoverArtArchiveClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackCoverModule {
    @Binds abstract fun bindCoverArtArchiveClient(impl: CoverArtArchiveClientImpl): CoverArtArchiveClient
    @Binds abstract fun bindAlbumBackCovers(impl: AlbumBackCoverRepository): AlbumBackCovers
}
