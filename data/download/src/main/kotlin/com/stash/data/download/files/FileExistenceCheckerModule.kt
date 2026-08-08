package com.stash.data.download.files

import com.stash.core.data.library.FileExistenceChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds [FileExistenceChecker] (declared in :core:data) to the real
 * [FileOrganizer]-backed implementation. This module lives in
 * :data:download specifically because it's the only place both types
 * are visible — see [FileExistenceChecker]'s doc comment.
 */
@Module
@InstallIn(SingletonComponent::class)
object FileExistenceCheckerModule {
    @Provides
    fun provideFileExistenceChecker(fileOrganizer: FileOrganizer): FileExistenceChecker =
        FileExistenceChecker { artist, album, title, filePath ->
            fileOrganizer.checkExists(artist, album, title, filePath)
        }
}