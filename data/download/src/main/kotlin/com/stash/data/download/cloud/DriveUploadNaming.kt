package com.stash.data.download.cloud

import com.stash.core.data.prefs.LibraryLayout
import com.stash.data.cloud.upload.UploadNaming
import com.stash.data.cloud.upload.UploadPlace
import com.stash.data.download.files.LibraryLayoutResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where "Save to Google Drive" puts a song inside the user's Stash folder: the phone's Artist/Album
 * layout, through the same resolver the downloader uses (same slugs, `singles` for no album,
 * placeholders for names that slug to nothing). Always Artist/Album, whatever layout the phone's
 * library uses: one predictable tree in Drive, the same from every phone.
 */
@Singleton
class DriveUploadNaming @Inject constructor() : UploadNaming {
    override fun place(artist: String, album: String, title: String, extension: String): UploadPlace {
        val loc = LibraryLayoutResolver.resolve(LibraryLayout.ARTIST_ALBUM, artist, album, title, playlistName = null)
        return UploadPlace(loc.segments, "${loc.baseName}.$extension")
    }
}
