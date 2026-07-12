package com.stash.core.data.di

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
    // A later task adds the ArtistAboutEnricher binding here.
}
