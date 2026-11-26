package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.data.local.room.LocalTransactionDao
import com.elect3.mobdev.personalfinance.data.local.room.LocalTransactionEntity
import com.elect3.mobdev.personalfinance.data.local.room.SyncState
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CreateTransactionRequest
import com.elect3.mobdev.personalfinance.domain.repository.TransactionDraft
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A transaction recorded on this device that the server has not confirmed yet (or rejected). */
enum class LocalTransactionState { Pending, Synced, Rejected }
data class LocalHistoryTransaction(val clientId: UUID, val draft: TransactionDraft, val categoryName: String?, val state: LocalTransactionState)

data class LocalPendingTransaction(val clientId: UUID, val draft: TransactionDraft, val categoryName: String?, val rejected: Boolean)

/** Device-local transaction store for one or more accounts. Every call is scoped to an account ID. */
interface LocalLedger {
    suspend fun recordSynced(accountId: Int, transactions: List<Transaction>)
    suspend fun enqueue(accountId: Int, request: CreateTransactionRequest, categoryName: String?)
    suspend fun pending(accountId: Int): List<LocalPendingTransaction>
    suspend fun markRejected(accountId: Int, clientId: UUID)
    /** Removes a pending or rejected row (never a synced one): local only, nothing is sent. */
    suspend fun discardUnsynced(accountId: Int, clientId: UUID)
    /** Removes this device's copy of a transaction the server deleted (or no longer has). */
    suspend fun forget(accountId: Int, clientId: UUID)
    fun unsynced(accountId: Int): Flow<List<LocalPendingTransaction>>
    /** Most recent locally stored transactions (synced and pending), newest first. */
    suspend fun history(accountId: Int, limit: Int = 200): List<LocalHistoryTransaction>
}

private fun Transaction.toEntity(accountId: Int, now: Long) = LocalTransactionEntity(accountId, clientId.toString(), transactionId, type.wireValue,
    categoryId, categoryName, amount.toPlainString(), currencyCode, amountBase.toPlainString(), transactionDate.toString(), description, SyncState.SYNCED, now)

private fun LocalTransactionEntity.toPending() = LocalPendingTransaction(UUID.fromString(clientId),
    TransactionDraft(TransactionType.fromWireValue(type), categoryId, BigDecimal(amount), currencyCode, LocalDate.parse(transactionDate), description),
    categoryName, syncState == SyncState.REJECTED)

/** Room-backed ledger (app-private database, this device only). */
class RoomLedger(private val dao: LocalTransactionDao, private val now: () -> Long = System::currentTimeMillis) : LocalLedger {
    override suspend fun recordSynced(accountId: Int, transactions: List<Transaction>) {
        if (transactions.isNotEmpty()) dao.upsert(transactions.map { it.toEntity(accountId, now()) })
    }
    override suspend fun enqueue(accountId: Int, request: CreateTransactionRequest, categoryName: String?) {
        val d = request.draft
        dao.insertPending(LocalTransactionEntity(accountId, request.clientId.toString(), null, d.type.wireValue, d.categoryId, categoryName,
            d.amount.toPlainString(), d.currencyCode, null, d.transactionDate.toString(), d.description, SyncState.PENDING, now()))
    }
    override suspend fun pending(accountId: Int) = dao.withState(accountId, SyncState.PENDING).map { it.toPending() }
    override suspend fun markRejected(accountId: Int, clientId: UUID) {
        dao.rejectPending(accountId, clientId.toString(), now())
    }
    override suspend fun discardUnsynced(accountId: Int, clientId: UUID) = dao.discardUnsynced(accountId, clientId.toString())
    override suspend fun forget(accountId: Int, clientId: UUID) = dao.delete(accountId, clientId.toString())
    override fun unsynced(accountId: Int): Flow<List<LocalPendingTransaction>> = dao.unsynced(accountId).map { rows -> rows.map { it.toPending() } }
    override suspend fun history(accountId: Int, limit: Int) = dao.history(accountId, limit).map { row ->
        val item = row.toPending()
        LocalHistoryTransaction(item.clientId, item.draft, item.categoryName, LocalTransactionState.valueOf(row.syncState))
    }
}

/** Same contract in memory (JVM tests). */
class InMemoryLedger : LocalLedger {
    private data class Row(val accountId: Int, val pending: LocalPendingTransaction, val synced: Transaction?)
    private val rows = LinkedHashMap<Pair<Int, UUID>, Row>()
    private val changes = MutableStateFlow(0)
    private val lock = Any()

    override suspend fun recordSynced(accountId: Int, transactions: List<Transaction>) {
        synchronized(lock) {
            transactions.forEach { t ->
                rows[accountId to t.clientId] = Row(accountId, LocalPendingTransaction(t.clientId,
                    TransactionDraft(t.type, t.categoryId, t.amount, t.currencyCode, t.transactionDate, t.description), t.categoryName, false), t)
            }
        }
        changes.value++
    }
    override suspend fun enqueue(accountId: Int, request: CreateTransactionRequest, categoryName: String?) {
        synchronized(lock) {
            if (rows.containsKey(accountId to request.clientId)) return
            rows[accountId to request.clientId] = Row(accountId, LocalPendingTransaction(request.clientId, request.draft, categoryName, false), null)
        }
        changes.value++
    }
    override suspend fun pending(accountId: Int): List<LocalPendingTransaction> = synchronized(lock) {
        rows.values.filter { it.accountId == accountId && it.synced == null && !it.pending.rejected }.map { it.pending }
    }
    override suspend fun markRejected(accountId: Int, clientId: UUID) {
        synchronized(lock) {
            val row = rows[accountId to clientId]?.takeIf { it.synced == null } ?: return
            rows[accountId to clientId] = row.copy(pending = row.pending.copy(rejected = true))
        }
        changes.value++
    }
    override suspend fun discardUnsynced(accountId: Int, clientId: UUID) {
        synchronized(lock) { if (rows[accountId to clientId]?.synced == null) rows.remove(accountId to clientId) }
        changes.value++
    }
    override suspend fun forget(accountId: Int, clientId: UUID) {
        synchronized(lock) { rows.remove(accountId to clientId) }
        changes.value++
    }
    override fun unsynced(accountId: Int): Flow<List<LocalPendingTransaction>> = changes.map {
        synchronized(lock) { rows.values.filter { it.accountId == accountId && it.synced == null }.map { it.pending } }
    }
    override suspend fun history(accountId: Int, limit: Int): List<LocalHistoryTransaction> = synchronized(lock) {
        rows.values.filter { it.accountId == accountId }.map {
            LocalHistoryTransaction(it.pending.clientId, it.pending.draft, it.pending.categoryName,
                if (it.synced != null) LocalTransactionState.Synced else if (it.pending.rejected) LocalTransactionState.Rejected else LocalTransactionState.Pending)
        }.sortedByDescending { it.draft.transactionDate }.take(limit)
    }
    fun isSynced(accountId: Int, clientId: UUID): Boolean = synchronized(lock) { rows[accountId to clientId]?.synced != null }
}
