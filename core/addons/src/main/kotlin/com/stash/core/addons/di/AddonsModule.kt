package com.stash.core.addons.di

import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InTreeAddonRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The add-on registry. Its inputs (the signed config's default add-ons, the user's own add-ons,
 * the kept manifests) and the client's ports (rate gate, first-party rule, install signer, host
 * options) are bound next to the pieces they adapt, in `:data:download` (`AddonHostModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AddonsModule {

    @Binds
    @Singleton
    abstract fun bindAddonRegistry(impl: InTreeAddonRegistry): AddonRegistry
}
