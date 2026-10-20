package com.stash.data.cloud.upload

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The upload side's small state: whether "Back up my downloads" is on (and for which Drive
 * account), whether backup is paused because Drive is full, and the remembered Stash folder ids
 * ([DriveFolderCache]). Nothing secret: ids and switches.
 */
interface CloudUploadSettings : DriveFolderCache {
    /** The Drive account backup uploads to; null = backup is off. */
    val backupAccountId: StateFlow<Long?>
    suspend fun setBackupAccount(accountId: Long?)

    /** True after Drive said it's full: uploads wait until the user tries again. */
    val driveFull: StateFlow<Boolean>
    suspend fun setDriveFull(full: Boolean)
}

/** [CloudUploadSettings] in private app preferences. */
@Singleton
class PrefsCloudUploadSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudUploadSettings {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val backup by lazy { MutableStateFlow(prefs.getLong(KEY_BACKUP, -1L).takeIf { it > 0 }) }
    private val full by lazy { MutableStateFlow(prefs.getBoolean(KEY_FULL, false)) }

    override val backupAccountId: StateFlow<Long?> get() = backup.asStateFlow()
    override val driveFull: StateFlow<Boolean> get() = full.asStateFlow()

    override suspend fun setBackupAccount(accountId: Long?) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_BACKUP, accountId ?: -1L).commit()
        backup.value = accountId
    }

    override suspend fun setDriveFull(full: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_FULL, full).commit()
        this@PrefsCloudUploadSettings.full.value = full
    }

    override suspend fun get(accountId: Long, path: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(folderKey(accountId, path), null)
    }

    override suspend fun put(accountId: Long, path: String, id: String) {
        withContext(Dispatchers.IO) { prefs.edit().putString(folderKey(accountId, path), id).apply() }
    }

    /** Forgets [path] and every folder remembered below it. */
    override suspend fun remove(accountId: Long, path: String) {
        withContext(Dispatchers.IO) {
            val key = folderKey(accountId, path)
            val doomed = prefs.all.keys.filter { it == key || it.startsWith("$key/") }
            if (doomed.isNotEmpty()) prefs.edit().apply { doomed.forEach(::remove) }.apply()
        }
    }

    override suspend fun clear(accountId: Long) {
        withContext(Dispatchers.IO) {
            val prefix = "$FOLDER_PREFIX$accountId:"
            val doomed = prefs.all.keys.filter { it.startsWith(prefix) }
            if (doomed.isNotEmpty()) prefs.edit().apply { doomed.forEach(::remove) }.apply()
        }
    }

    private fun folderKey(accountId: Long, path: String) = "$FOLDER_PREFIX$accountId:$path"

    private companion object {
        const val PREFS = "cloud_gdrive_uploads"
        const val KEY_BACKUP = "backup_account"
        const val KEY_FULL = "drive_full"
        const val FOLDER_PREFIX = "folder:"
    }
}
