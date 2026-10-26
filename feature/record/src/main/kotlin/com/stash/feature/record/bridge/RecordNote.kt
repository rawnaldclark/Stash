package com.stash.feature.record.bridge

import android.content.res.Resources
import com.stash.feature.record.R

/** A line for the listener over the record (a snackbar), worded by the screen from its resources ([text]). */
sealed interface RecordNote {
    /** The archive stopped part-way: the record is pressed from what came. */
    data object ArchiveFailed : RecordNote

    /** A tap in the record started one song ("Playing Nude"). */
    data class Playing(val title: String) : RecordNote

    /** A tap started a list ("Playing 33 songs"). */
    data class PlayingSongs(val count: Int) : RecordNote

    /** None of the songs could be found anywhere. */
    data object NoneFound : RecordNote

    /** Offline, and none of them is on the phone. */
    data object NoneDownloaded : RecordNote

    /** Playing failed for another reason. */
    data object PlayFailed : RecordNote

    /** A poster or a video is in Downloads. */
    data object Saved : RecordNote

    /** It couldn't be saved (or shared). */
    data object SaveFailed : RecordNote

    /** More files than one visit may save ([RecordSaves.MAX_FILES]): refused. */
    data object TooManySaves : RecordNote

    /** A file was ready to share while Stash was in the background, and the record closed before it came back. */
    data object ShareDropped : RecordNote
}

/** [note] in the app's language. */
fun RecordNote.text(res: Resources): String = when (this) {
    RecordNote.ArchiveFailed -> res.getString(R.string.record_note_archive_failed)
    is RecordNote.Playing -> res.getString(R.string.record_note_playing, title)
    is RecordNote.PlayingSongs -> res.getQuantityString(R.plurals.record_note_playing_songs, count, count)
    RecordNote.NoneFound -> res.getString(R.string.record_note_none_found)
    RecordNote.NoneDownloaded -> res.getString(R.string.record_note_none_downloaded)
    RecordNote.PlayFailed -> res.getString(R.string.record_note_play_failed)
    RecordNote.Saved -> res.getString(R.string.record_note_saved)
    RecordNote.SaveFailed -> res.getString(R.string.record_note_save_failed)
    RecordNote.TooManySaves -> res.getString(R.string.record_note_too_many_saves)
    RecordNote.ShareDropped -> res.getString(R.string.record_note_share_dropped)
}
