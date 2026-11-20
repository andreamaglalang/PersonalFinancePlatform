package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Foreign-currency transactions in the offline-first path: the device never stores or invents a rate. A queued
 * transaction keeps its source currency, amount, date and ClientId; the server converts it at sync time.
 */
class ForeignCurrencyOfflineTest {
    private val account = 42
    private val day = LocalDate.of(2026, 11, 15)
    private val draft = TransactionDraft(TransactionType.Expense, 2, BigDecimal("10.25"), "USD", day, "Taxi")

    /** Fake API with ClientId idempotency: the same ClientId always yields the same single server row. */
    private class Server {
        var mode: (CreateTransactionRequest) -> CreationResult? = { null }
        val rows = LinkedHashMap<UUID, Transaction>()
        val requests = mutableListOf<CreateTransactionRequest>()
        val remote = TransactionCreationRepository { r ->
            requests += r
            mode(r) ?: run {
                val existing = rows[r.clientId]
                val rate = BigDecimal("58.50000000")
                val row = existing ?: Transaction(rows.size + 1, r.clientId, r.draft.type, r.draft.categoryId, "Travel", r.draft.amount,
                    r.draft.currencyCode, rate, r.draft.transactionDate.minusDays(1), r.draft.amount.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                    r.draft.transactionDate, r.draft.description, Instant.parse("2026-11-15T01:02:03Z"), null, null).also { rows[r.clientId] = it }
                CreationResult.Success(row, replayed = existing != null)
            }
        }
    }

    @Test fun offlineForeignTransactionIsQueuedUnconvertedWithItsClientIdAndSyncsOnce() = runTest {
        val server = Server(); val ledger = InMemoryLedger()
        val creation = OfflineFirstCreationRepository(server.remote, ledger, { account })
        val request = CreateTransactionRequest(UUID.randomUUID(), draft)
        server.mode = { CreationResult.Failure(FinanceFailure.Unavailable) }
        assertEquals(CreationResult.Queued(request.clientId), creation.create(request))
        val pending = ledger.pending(account).single()
        assertEquals(request.clientId, pending.clientId)
        assertEquals(draft, pending.draft) // USD 10.25 on its date, exactly as entered: no rate, no base amount
        assertTrue(server.rows.isEmpty())

        server.mode = { null }
        val sync = PendingTransactionSync(server.remote, ledger)
        assertEquals(SyncOutcome.Done(1, 0, 0), sync.sync(account) { true })
        assertEquals(request.clientId, server.requests.last().clientId)
        assertEquals(draft, server.requests.last().draft)
        assertTrue(ledger.isSynced(account, request.clientId))
        val row = server.rows.values.single()
        assertEquals(BigDecimal("58.50000000"), row.fxRate); assertEquals(BigDecimal("599.63"), row.amountBase) // converted by the server
        // A second sync sends nothing; even a replay of the same ClientId stays one server row.
        assertEquals(SyncOutcome.Done(0, 0, 0), sync.sync(account) { true })
        assertEquals(1, server.rows.size)
        assertTrue((server.remote.create(request) as CreationResult.Success).replayed)
        assertEquals(1, server.rows.size)
    }

    @Test fun rateUnavailableOrRejectedResultsAreNeverQueuedAsOffline() = runTest {
        val server = Server(); val ledger = InMemoryLedger()
        val creation = OfflineFirstCreationRepository(server.remote, ledger, { account })
        server.mode = { CreationResult.UnknownOutcome(UnknownCreationReason.ServerFailure) } // e.g. HTTP 503: no acceptable rate
        assertEquals(CreationResult.UnknownOutcome(UnknownCreationReason.ServerFailure), creation.create(CreateTransactionRequest(UUID.randomUUID(), draft)))
        server.mode = { CreationResult.Failure(FinanceFailure.InvalidInput) } // e.g. HTTP 400: currency/date not available
        assertEquals(CreationResult.Failure(FinanceFailure.InvalidInput), creation.create(CreateTransactionRequest(UUID.randomUUID(), draft)))
        assertTrue(ledger.pending(account).isEmpty())
    }

    @Test fun aRateFailureDuringSyncKeepsThePendingItemOrMarksARejectionForReview() = runTest {
        val server = Server(); val ledger = InMemoryLedger()
        val first = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(account, first, "Travel")
        val sync = PendingTransactionSync(server.remote, ledger)
        server.mode = { CreationResult.UnknownOutcome(UnknownCreationReason.ServerFailure) }
        assertEquals(SyncOutcome.Offline, sync.sync(account) { true })
        assertEquals(first.clientId, ledger.pending(account).single().clientId) // kept, retried later with the same ClientId
        server.mode = { CreationResult.Failure(FinanceFailure.InvalidInput) }
        assertEquals(SyncOutcome.Done(0, 1, 0), sync.sync(account) { true })
        assertTrue(ledger.pending(account).isEmpty())
        assertTrue(ledger.history(account).single().state == LocalTransactionState.Rejected) // kept for review, never dropped
    }

    @Test fun aConfirmedEditReplacesTheLocalRowWithoutDuplicatingIt() = runTest {
        val ledger = InMemoryLedger()
        val clientId = UUID.randomUUID()
        val original = Transaction(7, clientId, TransactionType.Expense, 2, "Travel", BigDecimal("10.00"), "USD", BigDecimal("58.12345678"),
            day.minusDays(1), BigDecimal("581.23"), day, "Taxi", Instant.parse("2026-11-15T01:02:03Z"), null, null)
        ledger.recordSynced(account, listOf(original))
        val edited = original.copy(amount = BigDecimal("12.50"), amountBase = BigDecimal("726.54"))
        val updates = LedgerRecordingUpdateRepository({ _, _ -> FinanceResult.Success(edited) }, ledger, { account })
        assertTrue(updates.update(7, TransactionDraft(TransactionType.Expense, 2, BigDecimal("12.50"), "USD", day, "Taxi")) is FinanceResult.Success)
        val rows = ledger.history(account)
        assertEquals(1, rows.size); assertEquals(BigDecimal("12.50"), rows.single().draft.amount)
        // A failed edit never touches the local copy.
        val failing = LedgerRecordingUpdateRepository({ _, _ -> FinanceResult.Failure(FinanceFailure.Unavailable) }, ledger, { account })
        failing.update(7, TransactionDraft(TransactionType.Expense, 2, BigDecimal("99.00"), "USD", day, "Taxi"))
        assertEquals(BigDecimal("12.50"), ledger.history(account).single().draft.amount)
    }
}
