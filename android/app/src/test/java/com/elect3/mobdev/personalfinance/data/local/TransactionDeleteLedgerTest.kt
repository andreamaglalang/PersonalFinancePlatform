package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Device copy after server delete/edit/reload, local-only discard of unsynced items, and C2 create conflicts. */
class TransactionDeleteLedgerTest {
    private val draft = TransactionDraft(TransactionType.Expense, 7, BigDecimal("12.34"), "PHP", LocalDate.of(2026, 11, 19), "Lunch")
    private fun tx(clientId: UUID, version: String? = "A".repeat(64)) = Transaction(501, clientId, draft.type, 7, "Food", draft.amount, "PHP",
        BigDecimal.ONE, draft.transactionDate, draft.amount, draft.transactionDate, draft.description, Instant.EPOCH, null, null, version)

    private class Remote(var deleteReply: FinanceResult<Unit>) : TransactionUpdateRepository {
        var getReply: FinanceResult<Transaction> = FinanceResult.Failure(FinanceFailure.Unavailable)
        override suspend fun update(transactionId: Int, draft: TransactionDraft, version: String): FinanceResult<Transaction> = error("unused")
        override suspend fun get(transactionId: Int) = getReply
        override suspend fun delete(transaction: Transaction) = deleteReply
    }

    @Test fun aConfirmedOrAlreadyGoneDeleteRemovesTheDeviceCopyAndAFailureKeepsIt() = runTest {
        for ((reply, kept) in listOf<Pair<FinanceResult<Unit>, Boolean>>(
            FinanceResult.Success(Unit) to false,
            FinanceResult.Failure(FinanceFailure.NotFound) to false,
            FinanceResult.Failure(FinanceFailure.Conflict) to true,
            FinanceResult.Failure(FinanceFailure.Unavailable, isConnectivityFailure = true) to true,
            FinanceResult.Failure(FinanceFailure.Timeout) to true)) {
            val ledger = InMemoryLedger(); val t = tx(UUID.randomUUID())
            ledger.recordSynced(42, listOf(t))
            val result = LedgerRecordingUpdateRepository(Remote(reply), ledger, { 42 }).delete(t)
            assertSame(reply, result)
            assertEquals(kept, ledger.history(42).isNotEmpty())
        }
    }

    @Test fun aReloadRefreshesTheDeviceCopy() = runTest {
        val ledger = InMemoryLedger(); val id = UUID.randomUUID()
        ledger.recordSynced(42, listOf(tx(id)))
        val fresh = tx(id).copy(amount = BigDecimal("20.00"), version = "B".repeat(64))
        val remote = Remote(FinanceResult.Success(Unit)).apply { getReply = FinanceResult.Success(fresh) }
        LedgerRecordingUpdateRepository(remote, ledger, { 42 }).get(501)
        assertEquals(BigDecimal("20.00"), ledger.history(42).single().draft.amount)
    }

    @Test fun pendingItemsAreDiscardedLocallyButSyncedRowsAreNot() = runTest {
        val ledger = InMemoryLedger()
        val pending = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(42, pending, "Food")
        ledger.discardUnsynced(42, pending.clientId)
        assertTrue(ledger.unsynced(42).first().isEmpty()); assertTrue(ledger.pending(42).isEmpty())
        val synced = tx(UUID.randomUUID()); ledger.recordSynced(42, listOf(synced))
        ledger.discardUnsynced(42, synced.clientId)
        assertTrue(ledger.isSynced(42, synced.clientId))
    }

    @Test fun aCreateConflictDuringSyncIsRejectedAndNeverRetried() = runTest {
        val ledger = InMemoryLedger(); var calls = 0
        val conflicting = TransactionCreationRepository { calls++; CreationResult.Failure(FinanceFailure.Conflict) }
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        assertEquals(CreationResult.Failure(FinanceFailure.Conflict), OfflineFirstCreationRepository(conflicting, ledger, { 42 }).create(request))
        assertTrue(ledger.history(42).isEmpty()) // neither queued nor shown as confirmed
        ledger.enqueue(42, request, "Food")
        assertEquals(SyncOutcome.Done(0, 1, 0), PendingTransactionSync(conflicting, ledger).sync(42) { true })
        assertEquals(LocalTransactionState.Rejected, ledger.history(42).single().state)
        assertEquals(SyncOutcome.Done(0, 0, 0), PendingTransactionSync(conflicting, ledger).sync(42) { true })
        assertEquals(2, calls) // one create, one sync attempt; the rejected item is not sent again
    }
}
