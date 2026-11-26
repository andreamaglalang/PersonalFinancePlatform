package com.elect3.mobdev.personalfinance.data.local.room

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Sync state of a locally stored transaction. */
object SyncState { const val SYNCED = "Synced"; const val PENDING = "Pending"; const val REJECTED = "Rejected" }

/**
 * One transaction of one signed-in account, stored on this device. Keyed by (accountId, clientId): the same
 * ClientId the server uses for idempotency, so a pending row and its eventual server row are the same record.
 * Money and dates are stored as exact strings (BigDecimal / ISO date), never floating point.
 */
@Entity(tableName = "local_transactions", primaryKeys = ["accountId", "clientId"],
    indices = [Index(value = ["accountId", "syncState"]), Index(value = ["accountId", "transactionDate"])])
data class LocalTransactionEntity(
    val accountId: Int,
    val clientId: String,
    /** Server ID once synced; null while pending. */
    val transactionId: Int?,
    val type: String,
    val categoryId: Int,
    val categoryName: String?,
    val amount: String,
    val currencyCode: String,
    val amountBase: String?,
    val transactionDate: String,
    val description: String?,
    val syncState: String,
    val updatedAtEpochMs: Long,
)

@Dao
interface LocalTransactionDao {
    @Upsert suspend fun upsert(rows: List<LocalTransactionEntity>)

    // ClientId is immutable: a replay must never replace an existing pending, rejected or synced row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPending(row: LocalTransactionEntity): Long

    @Query("UPDATE local_transactions SET syncState = 'Rejected', updatedAtEpochMs = :now WHERE accountId = :accountId AND clientId = :clientId AND syncState = 'Pending'")
    suspend fun rejectPending(accountId: Int, clientId: String, now: Long): Int

    @Query("SELECT * FROM local_transactions WHERE accountId = :accountId AND syncState = :state ORDER BY updatedAtEpochMs, clientId")
    suspend fun withState(accountId: Int, state: String): List<LocalTransactionEntity>

    @Query("SELECT * FROM local_transactions WHERE accountId = :accountId AND syncState != 'Synced' ORDER BY updatedAtEpochMs DESC, clientId")
    fun unsynced(accountId: Int): Flow<List<LocalTransactionEntity>>

    @Query("SELECT * FROM local_transactions WHERE accountId = :accountId ORDER BY transactionDate DESC, updatedAtEpochMs DESC LIMIT :limit")
    suspend fun history(accountId: Int, limit: Int): List<LocalTransactionEntity>

    @Query("SELECT * FROM local_transactions WHERE accountId = :accountId AND clientId = :clientId")
    suspend fun find(accountId: Int, clientId: String): LocalTransactionEntity?

    @Query("DELETE FROM local_transactions WHERE accountId = :accountId AND clientId = :clientId AND syncState = 'Rejected'")
    suspend fun discardRejected(accountId: Int, clientId: String)
}

/**
 * A category the server returned to one signed-in account, kept so the Add Transaction form can offer it while the
 * API is unreachable. Read-only cache: categories are never created or edited offline.
 */
@Entity(tableName = "local_categories", primaryKeys = ["accountId", "categoryId"],
    indices = [Index(value = ["accountId", "type", "isActive"])])
data class LocalCategoryEntity(
    val accountId: Int,
    val categoryId: Int,
    val type: String,
    val name: String,
    val colorHex: String?,
    val isActive: Boolean,
    val isSystemDefault: Boolean,
    val updatedAtEpochMs: Long,
)

@Dao
abstract class LocalCategoryDao {
    @Upsert abstract suspend fun upsert(rows: List<LocalCategoryEntity>)

    @Query("UPDATE local_categories SET isActive = 0 WHERE accountId = :accountId AND type = :type AND isActive = 1")
    abstract suspend fun deactivateType(accountId: Int, type: String)

    /** Reconciles a complete active list atomically, retaining previously cached inactive metadata. */
    @Transaction
    open suspend fun replaceType(accountId: Int, type: String, rows: List<LocalCategoryEntity>) {
        deactivateType(accountId, type)
        if (rows.isNotEmpty()) upsert(rows)
    }

    @Query("SELECT * FROM local_categories WHERE accountId = :accountId AND (:type IS NULL OR type = :type) AND (:includeInactive OR isActive = 1) ORDER BY name COLLATE NOCASE, categoryId")
    abstract suspend fun read(accountId: Int, type: String?, includeInactive: Boolean): List<LocalCategoryEntity>
}

/**
 * Version 1 -> 2 is purely additive: it creates local_categories and its index and never touches
 * local_transactions, so cached history, pending expenses, ClientIds and sync states are preserved.
 * Statements must match exported schema v2; real Room open/migration validation requires instrumentation.
 */
val MIGRATION_1_2_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `local_categories` (`accountId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT, `isActive` INTEGER NOT NULL, `isSystemDefault` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `categoryId`))",
    "CREATE INDEX IF NOT EXISTS `index_local_categories_accountId_type_isActive` ON `local_categories` (`accountId`, `type`, `isActive`)",
)

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_1_2_SQL.forEach(db::execSQL) }
}

/**
 * An uncertain savings contribution or bill payment, persisted before it is sent. Keyed by (namespace, path):
 * namespace is endpoint + account ID, so another signed-in account never sees it. [body] is the exact JSON
 * payload with its ClientId; the row is deleted only after a definitive server answer.
 */
@Entity(tableName = "pending_requests", primaryKeys = ["namespace", "path"])
data class PendingRequestEntity(
    val namespace: String,
    val path: String,
    val body: String,
    val createdAtEpochMs: Long,
)

@Dao
interface PendingRequestDao {
    @Upsert suspend fun upsert(row: PendingRequestEntity)

    @Query("SELECT * FROM pending_requests WHERE namespace = :namespace AND path = :path")
    suspend fun find(namespace: String, path: String): PendingRequestEntity?

    @Query("SELECT path FROM pending_requests WHERE namespace = :namespace ORDER BY path")
    suspend fun paths(namespace: String): List<String>

    @Query("DELETE FROM pending_requests WHERE namespace = :namespace AND path = :path")
    suspend fun delete(namespace: String, path: String)
}

/** Version 2 -> 3 is purely additive: it creates pending_requests and never touches existing tables. */
val MIGRATION_2_3_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `pending_requests` (`namespace` TEXT NOT NULL, `path` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`namespace`, `path`))",
)

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_2_3_SQL.forEach(db::execSQL) }
}

@Database(entities = [LocalTransactionEntity::class, LocalCategoryEntity::class, PendingRequestEntity::class], version = 3, exportSchema = true)
abstract class OwliDatabase : RoomDatabase() {
    abstract fun transactions(): LocalTransactionDao
    abstract fun categories(): LocalCategoryDao
    abstract fun pendingRequests(): PendingRequestDao

    companion object {
        @Volatile private var instance: OwliDatabase? = null
        // Explicit migrations only: never fall back to a destructive rebuild that would drop pending expenses.
        fun get(context: Context): OwliDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, OwliDatabase::class.java, "owli-local.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
