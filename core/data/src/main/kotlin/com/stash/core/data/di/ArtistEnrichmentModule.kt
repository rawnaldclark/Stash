package com.stash.core.data.di

import com.stash.core.data.cache.ArtistAboutEnricher
import com.stash.core.data.cache.RealArtistAboutEnricher
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.core.data.musicbrainz.MusicBrainzClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ArtistEnrichmentModule {
    @Binds abstract fun bindMusicBrainzClient(impl: MusicBrainzClientImpl): MusicBrainzClient
    @Binds abstract fun bindArtistAboutEnricher(impl: RealArtistAboutEnricher): ArtistAboutEnricher
}
