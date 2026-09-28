package com.stash.core.data.di

import com.stash.core.data.diagnostics.BackgroundWorkDiagnosticsContributor
import com.stash.core.data.diagnostics.DiagnosticsContributor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Hilt multibinding scaffolding for [DiagnosticsContributor]s, mirroring
 * `LosslessModule`: the empty default set makes `Set<DiagnosticsContributor>`
 * injectable even in a build where no other module contributes a section.
 * Contributors in other modules bind themselves with `@Binds @IntoSet` in their
 * own module; `core:data`'s own contributors bind here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Multibinds
    abstract fun diagnosticsContributors(): Set<DiagnosticsContributor>

    /** The diagnostics bundle's "Background work" section: Android's battery rules and WorkManager's queue. */
    @Binds
    @IntoSet
    abstract fun backgroundWorkDiagnostics(impl: BackgroundWorkDiagnosticsContributor): DiagnosticsContributor
}
