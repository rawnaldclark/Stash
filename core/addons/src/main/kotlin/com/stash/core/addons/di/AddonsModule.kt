package com.stash.core.addons.di

import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.DefaultAddons
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.NoDefaultAddons
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The add-on registry. Phase 1 binds [NoDefaultAddons], so [InTreeAddonRegistry] stays empty and
 * every add-on seam is inert. The client's ports (rate gate, first-party rule, install signer) are
 * bound next to the pieces they adapt, in `:data:download` (`AddonHostModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AddonsModule {

    @Binds
    @Singleton
    abstract fun bindAddonRegistry(impl: InTreeAddonRegistry): AddonRegistry

    @Binds
    @Singleton
    abstract fun bindDefaultAddons(impl: NoDefaultAddons): DefaultAddons
}
