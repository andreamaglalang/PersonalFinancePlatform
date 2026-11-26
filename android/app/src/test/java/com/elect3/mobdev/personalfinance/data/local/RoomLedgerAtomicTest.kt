package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.data.local.room.*
import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** JVM verifies the ledger uses atomic DAO operations; real SQLite races are covered by RoomLedgerTest. */
class RoomLedgerAtomicTest {
    private class AtomicDao : LocalTransactionDao {
        private val rows = linkedMapOf<Pair<Int,String>,LocalTransactionEntity>()
        override suspend fun upsert(rows: List<LocalTransactionEntity>) { synchronized(this) { rows.forEach { this.rows[it.accountId to it.clientId] = it } } }
        override suspend fun insertPending(row: LocalTransactionEntity): Long = synchronized(this) {
            val key = row.accountId to row.clientId
            if (rows.containsKey(key)) -1 else { rows[key] = row; 1 }
        }
        override suspend fun rejectPending(accountId: Int,clientId: String,now: Long): Int = synchronized(this) {
            val key = accountId to clientId
            val row = rows[key]
            if (row?.syncState != SyncState.PENDING) 0 else { rows[key] = row.copy(syncState=SyncState.REJECTED,updatedAtEpochMs=now); 1 }
        }
        override suspend fun find(accountId: Int,clientId: String): LocalTransactionEntity? = error("Ledger must not use a stale read/check/write")
        override suspend fun withState(accountId: Int,state: String): List<LocalTransactionEntity> = synchronized(this) {
            rows.values.filter { it.accountId == accountId && it.syncState == state }
        }
        override fun unsynced(accountId: Int): Flow<List<LocalTransactionEntity>> = flowOf(emptyList())
        override suspend fun history(accountId: Int,limit: Int): List<LocalTransactionEntity> = synchronized(this) { rows.values.filter { it.accountId == accountId }.take(limit) }
        override suspend fun discardUnsynced(accountId: Int,clientId: String) {
            synchronized(this) { val key = accountId to clientId; if (rows[key]?.syncState != SyncState.SYNCED) rows.remove(key) }
        }
        override suspend fun delete(accountId: Int,clientId: String) { synchronized(this) { rows.remove(accountId to clientId) } }
    }
    private val draft = TransactionDraft(TransactionType.Expense,7,BigDecimal("12.34"),"PHP",LocalDate.of(2026,11,19),"Synthetic")
    private fun server(id: UUID) = Transaction(501,id,draft.type,7,"Food",draft.amount,"PHP",BigDecimal.ONE,
        draft.transactionDate,draft.amount,draft.transactionDate,draft.description,Instant.EPOCH,null,null)

    @Test fun concurrentEnqueueRejectSyncAndReplayPreserveConfirmation() = runTest {
        val ledger = RoomLedger(AtomicDao())
        repeat(100) {
            val req = CreateTransactionRequest(UUID.randomUUID(),draft)
            coroutineScope {
                listOf(
                    launch(Dispatchers.Default) { ledger.enqueue(1,req,"Food") },
                    launch(Dispatchers.Default) { ledger.markRejected(1,req.clientId) },
                    launch(Dispatchers.Default) { ledger.recordSynced(1,listOf(server(req.clientId))) },
                    launch(Dispatchers.Default) { ledger.enqueue(1,req,"Food") }
                ).joinAll()
            }
            val row = ledger.history(1).single { it.clientId == req.clientId }
            assertEquals(LocalTransactionState.Synced,row.state)
            assertEquals(BigDecimal("12.34"),row.draft.amount)
        }
        assertTrue(ledger.pending(1).isEmpty())
        assertTrue(ledger.history(2).isEmpty())
    }

    @Test fun rejectionAndReplayAreAtomicAndAccountScoped() = runTest {
        val ledger = RoomLedger(AtomicDao()); val req = CreateTransactionRequest(UUID.randomUUID(),draft)
        ledger.enqueue(1,req,"Food"); ledger.enqueue(2,req,"Food")
        ledger.markRejected(1,req.clientId)
        ledger.enqueue(1,CreateTransactionRequest(req.clientId, TransactionDraft(draft.type,draft.categoryId,BigDecimal("99.99"),draft.currencyCode,draft.transactionDate,draft.description)),"Other")
        assertEquals(LocalTransactionState.Rejected,ledger.history(1).single().state)
        assertEquals(draft.amount,ledger.history(1).single().draft.amount)
        assertEquals(LocalTransactionState.Pending,ledger.history(2).single().state)
        ledger.recordSynced(1,listOf(server(req.clientId)))
        ledger.markRejected(1,req.clientId)
        ledger.discardUnsynced(1,req.clientId)
        assertEquals(LocalTransactionState.Synced,ledger.history(1).single().state)
    }
}
