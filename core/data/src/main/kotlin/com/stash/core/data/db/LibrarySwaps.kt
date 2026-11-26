package com.stash.core.data.db

import android.content.Context

/**
 * How many times a restore has swapped this phone's library database for a backup's
 * ([DatabaseBackupManager.importDatabase], a replace). Song row ids mean something only in the library that gave them
 * out: the swapped-in one gives the same ids out again, to other songs. What is kept outside the database by row id
 * (`:data:download`'s Send to computer memory of the songs the computer has) keeps the count it was written under, and
 * trusts nothing written under another.
 *
 * In SharedPreferences, which a restore leaves alone and a backup never carries.
 */
object LibrarySwaps {
    private const val PREFS_NAME = "library_swaps"
    private const val COUNT_KEY = "count"

    /** How many times the library has been swapped: 0 until the first replace-restore. */
    fun count(context: Context): Long = prefs(context).getLong(COUNT_KEY, 0L)

    /** A swap is about to happen (before it, so a phone that dies during it has let go already). */
    internal fun note(context: Context) {
        val prefs = prefs(context)
        check(prefs.edit().putLong(COUNT_KEY, prefs.getLong(COUNT_KEY, 0L) + 1).commit()) { "The library swap could not be noted." }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
