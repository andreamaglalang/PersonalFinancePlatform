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

/** Offline-first recording: queue on connectivity loss, sync with the same ClientId, never duplicate. */
class OfflineFirstTest {
    private val draft = TransactionDraft(TransactionType.Expense, 7, BigDecimal("12.34"), "PHP", LocalDate.of(2026, 11, 19), "Lunch")
    private fun tx(req: CreateTransactionRequest, id: Int = 501) = Transaction(id, req.clientId, req.draft.type, req.draft.categoryId, "Food",
        req.draft.amount, req.draft.currencyCode, BigDecimal.ONE, req.draft.transactionDate, req.draft.amount, req.draft.transactionDate,
        req.draft.description, Instant.EPOCH, null, null)

    /** Server double that honours per-user ClientId idempotency like the API (one record per ClientId). */
    private inner class Server : TransactionCreationRepository {
        var reachable = false; var reject = false; var sessionGone = false
        val records = LinkedHashMap<UUID, Transaction>(); var calls = 0
        override suspend fun create(request: CreateTransactionRequest): CreationResult {
            calls++
            if (sessionGone) return CreationResult.Failure(FinanceFailure.SessionExpired)
            if (!reachable) return CreationResult.UnknownOutcome(UnknownCreationReason.TransportFailure)
            if (reject) return CreationResult.Failure(FinanceFailure.InvalidInput)
            val existing = records[request.clientId]
            return if (existing != null) CreationResult.Success(existing, replayed = true)
            else CreationResult.Success(tx(request, 500 + records.size + 1).also { records[request.clientId] = it }, replayed = false)
        }
    }

    @Test fun offlineSaveIsQueuedWithItsClientIdAndSyncedOnceWhenOnline() = runTest {
        val server = Server(); val ledger = InMemoryLedger()
        val creation = OfflineFirstCreationRepository(server, ledger, { 42 })
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        val result = creation.create(request)
        assertEquals(CreationResult.Queued(request.clientId), result)
        assertEquals(listOf(request.clientId), ledger.pending(42).map { it.clientId })
        assertTrue(server.records.isEmpty())

        val sync = PendingTransactionSync(server, ledger)
        assertEquals(SyncOutcome.Offline, sync.sync(42) { true })
        server.reachable = true
        assertEquals(SyncOutcome.Done(sent = 1, rejected = 0, remaining = 0), sync.sync(42) { true })
        assertEquals(1, server.records.size)
        assertTrue(ledger.pending(42).isEmpty()); assertTrue(ledger.isSynced(42, request.clientId))
        assertEquals(SyncOutcome.Done(0, 0, 0), sync.sync(42) { true }) // nothing left: no second send
        assertEquals(1, server.records.size)
    }

    @Test fun aRequestThatSecretlyArrivedIsNotDuplicatedOnSync() = runTest {
        // The server stored it, but the reply was lost (unknown outcome): the phone queues it.
        val server = Server(); val ledger = InMemoryLedger()
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        server.reachable = true; server.create(request); server.reachable = false
        ledger.enqueue(42, request, "Food")
        server.reachable = true
        PendingTransactionSync(server, ledger).sync(42) { true }
        assertEquals("ClientId idempotency keeps one server record", 1, server.records.size)
        assertTrue(ledger.isSynced(42, request.clientId))
    }

    @Test fun onlineSaveIsRecordedLocallyAndNotQueued() = runTest {
        val server = Server().apply { reachable = true }; val ledger = InMemoryLedger()
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        assertTrue(OfflineFirstCreationRepository(server, ledger, { 42 }).create(request) is CreationResult.Success)
        assertTrue(ledger.pending(42).isEmpty()); assertTrue(ledger.isSynced(42, request.clientId))
        assertEquals(1, ledger.history(42).size)
    }

    @Test fun serverRejectionsAndSessionLossAreNeverQueuedOrSilentlyDropped() = runTest {
        val ledger = InMemoryLedger()
        val rejecting = Server().apply { reachable = true; reject = true }
        val r = OfflineFirstCreationRepository(rejecting, ledger, { 42 }).create(CreateTransactionRequest(UUID.randomUUID(), draft))
        assertEquals(CreationResult.Failure(FinanceFailure.InvalidInput), r)
        assertTrue(ledger.pending(42).isEmpty())
        val expired = Server().apply { sessionGone = true }
        assertEquals(CreationResult.Failure(FinanceFailure.SessionExpired),
            OfflineFirstCreationRepository(expired, ledger, { 42 }).create(CreateTransactionRequest(UUID.randomUUID(), draft)))
        assertTrue(ledger.pending(42).isEmpty())
        // A queued item later rejected by the server is kept as Rejected for review, then discarded on request.
        val q = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(42, q, "Food")
        assertEquals(SyncOutcome.Done(0, 1, 0), PendingTransactionSync(rejecting, ledger).sync(42) { true })
        assertTrue(ledger.unsynced(42).first().single().rejected)
        ledger.discardRejected(42, q.clientId)
        assertTrue(ledger.unsynced(42).first().isEmpty())
    }

    @Test fun syncStopsOnSessionEndAndKeepsTheQueue() = runTest {
        val server = Server().apply { reachable = true; sessionGone = true }; val ledger = InMemoryLedger()
        ledger.enqueue(42, CreateTransactionRequest(UUID.randomUUID(), draft), null)
        assertEquals(SyncOutcome.SessionEnded, PendingTransactionSync(server, ledger).sync(42) { true })
        assertEquals(1, ledger.pending(42).size)
        assertEquals(SyncOutcome.SessionEnded, PendingTransactionSync(server, ledger).sync(42) { false })
    }

    @Test fun queuesAreSeparatePerAccount() = runTest {
        val ledger = InMemoryLedger()
        ledger.enqueue(1, CreateTransactionRequest(UUID.randomUUID(), draft), null)
        assertTrue(ledger.pending(2).isEmpty()); assertEquals(1, ledger.pending(1).size)
        val server = Server().apply { reachable = true }
        PendingTransactionSync(server, ledger).sync(2) { true }
        assertTrue(server.records.isEmpty())
    }

    @Test fun connectivityClassification() {
        assertTrue(CreationResult.Failure(FinanceFailure.Unavailable).isConnectivityProblem())
        assertTrue(CreationResult.UnknownOutcome(UnknownCreationReason.Timeout).isConnectivityProblem())
        assertFalse(CreationResult.UnknownOutcome(UnknownCreationReason.ServerFailure).isConnectivityProblem())
        assertFalse(CreationResult.UnknownOutcome(UnknownCreationReason.TransportFailure, sessionInvalidated = true).isConnectivityProblem())
        assertFalse(CreationResult.Failure(FinanceFailure.Conflict).isConnectivityProblem())
    }
}
