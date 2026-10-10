package com.stash.core.data.weblink.store

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * This phone's link with Stash on the web (spec §4.4): one row. The device's identity (id, token, long-term key) exists from
 * the first link until it unlinks; the space columns are set while it is in a space. Secrets are sealed with the app's
 * Tink keyset (Android Keystore master key, `core:auth` `TinkEncryptionManager`); with an Android Keystore device key
 * ([deviceKeyWrapped] null) the private key never leaves the Keystore at all.
 */
@Entity(tableName = "sync_space")
data class SyncSpaceEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    /** The 32-byte device token, sealed. */
    val tokenSealed: ByteArray,
    /** The device's long-term P-256 public key, 65 bytes uncompressed. */
    val devicePub: ByteArray,
    /** The software device key (PKCS#8), sealed; null when the key lives in the Android Keystore. */
    val deviceKeyWrapped: ByteArray?,
    /** This phone's name in its label ("Pixel 6"). */
    val deviceName: String,
    val spaceId: String? = null,
    val epoch: Int = 0,
    /** K of [epoch], sealed. */
    val keySealed: ByteArray? = null,
    /** The epoch before [epoch], kept until a snapshot under the new key lands (sync-v1 §3.5); 0 = none. */
    val prevEpoch: Int = 0,
    val prevKeySealed: ByteArray? = null,
    val linkedAt: Long = 0,
)

/**
 * Who is in the space, as this phone verified it (sync-v1 §3.6): each device's id, type and name from a `stash-label` this
 * phone opened itself, and its **pinned** public key (trust on first use per id). [nickname] is this phone's own name for it
 * (a rename of another device stays on this phone: the server only lets a device rewrite its own label).
 */
@Entity(tableName = "sync_roster")
data class SyncRosterEntity(
    @PrimaryKey val deviceId: String,
    val type: String,
    val labelName: String,
    val pub: ByteArray,
    val nickname: String? = null,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_space WHERE id = 1")
    suspend fun space(): SyncSpaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSpace(row: SyncSpaceEntity)

    @Query("SELECT * FROM sync_roster ORDER BY deviceId")
    suspend fun roster(): List<SyncRosterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoster(rows: List<SyncRosterEntity>)

    @Query("DELETE FROM sync_roster")
    suspend fun clearRoster()

    @Query("DELETE FROM sync_space")
    suspend fun clearSpace()

    @Transaction
    suspend fun replaceRoster(rows: List<SyncRosterEntity>) {
        clearRoster()
        putRoster(rows)
    }

    @Transaction
    suspend fun wipe() {
        clearRoster()
        clearSpace()
    }
}

/**
 * `stash_sync.db`: a database of its own, apart from the library's `stash.db`, so `DatabaseBackupManager` (which carries
 * `stash.db` and `datastore/`) never puts a link into a backup and a restored backup can never bring one back. Android's
 * cloud backup is off for the whole app (`allowBackup="false"`); a device-to-device transfer ignores that flag at targetSdk
 * 31+, so `res/xml/data_extraction_rules.xml` excludes this database and the keyset that seals it from both.
 */
@Database(entities = [SyncSpaceEntity::class, SyncRosterEntity::class], version = 1, exportSchema = true)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao

    companion object {
        const val NAME = "stash_sync.db"
    }
}
