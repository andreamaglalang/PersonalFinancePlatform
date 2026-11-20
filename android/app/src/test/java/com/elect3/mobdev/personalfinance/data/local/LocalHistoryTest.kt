package com.elect3.mobdev.personalfinance.data.local

import com.elect3.mobdev.personalfinance.domain.model.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalHistoryTest {
    private val draft = TransactionDraft(TransactionType.Expense, 7, BigDecimal("12.34"), "PHP", LocalDate.of(2026,11,19), "Synthetic")
    private fun tx(id: UUID) = Transaction(501, id, draft.type, 7, "Food", draft.amount, "PHP", BigDecimal.ONE,
        draft.transactionDate, draft.amount, draft.transactionDate, draft.description, Instant.EPOCH, null, null)
    private fun source(result: TransactionPageResult) = object : TransactionRepository {
        override suspend fun readPage(page: Int, pageSize: Int) = result
    }

    @Test fun cachedServerRowsKeepExactAmountsAndDoNotChangeServerPagination() = runTest {
        val ledger = InMemoryLedger()
        val page = TransactionPage(listOf(tx(UUID.randomUUID())), 2, 20, 75)
        val repo = cacheTransactionHistory(source(TransactionPageResult.Success(page)), ledger, 1) { true }
        val result = repo.readPage(2,20) as TransactionPageResult.Success
        assertSame(page, result.page)
        val row = ledger.history(1).single()
        assertEquals(LocalTransactionState.Synced, row.state)
        assertEquals(BigDecimal("12.34"), row.draft.amount)
        assertTrue(ledger.history(2).isEmpty())
    }

    @Test fun unreachableServerRetainsAllThreeLocalStatesWithoutPretendingSuccess() = runTest {
        val ledger = InMemoryLedger()
        val pending = CreateTransactionRequest(UUID.randomUUID(), draft)
        val rejected = CreateTransactionRequest(UUID.randomUUID(), draft)
        ledger.enqueue(1,pending,null); ledger.enqueue(1,rejected,null); ledger.markRejected(1,rejected.clientId)
        ledger.recordSynced(1,listOf(tx(UUID.randomUUID())))
        val repo = cacheTransactionHistory(source(TransactionPageResult.Failure(TransactionFailure.Unavailable)),ledger,1) { true }
        assertEquals(TransactionPageResult.Failure(TransactionFailure.Unavailable), repo.readPage())
        assertEquals(setOf(LocalTransactionState.Synced,LocalTransactionState.Pending,LocalTransactionState.Rejected),
            ledger.history(1).map { it.state }.toSet())
    }

    @Test fun expiredSessionCannotReadOrCacheAnotherResponse() = runTest {
        val ledger = InMemoryLedger(); var calls = 0
        val remote = object : TransactionRepository {
            override suspend fun readPage(page: Int,pageSize: Int): TransactionPageResult { calls++; error("Must not read") }
        }
        assertEquals(TransactionPageResult.Failure(TransactionFailure.SessionExpired),
            cacheTransactionHistory(remote,ledger,1) { false }.readPage())
        assertEquals(0,calls); assertTrue(ledger.history(1).isEmpty())
    }

    @Test fun sessionChangeDuringReadPreventsCacheAndSuccess() = runTest {
        val ledger = InMemoryLedger(); var current = true
        val remote = object : TransactionRepository {
            override suspend fun readPage(page: Int,pageSize: Int): TransactionPageResult {
                current = false
                return TransactionPageResult.Success(TransactionPage(listOf(tx(UUID.randomUUID())),1,20,1))
            }
        }
        assertEquals(TransactionPageResult.Failure(TransactionFailure.SessionExpired),
            cacheTransactionHistory(remote,ledger,1) { current }.readPage())
        assertTrue(ledger.history(1).isEmpty())
    }

    @Test fun filterableSourceReceivesExactFilterAndPagination() = runTest {
        val ledger = InMemoryLedger(); val filter = TransactionFilter(type=TransactionType.Expense)
        val remote = object : FilterableTransactionRepository {
            override suspend fun readPage(page: Int,pageSize: Int): TransactionPageResult = error("Unfiltered call")
            override suspend fun readPage(page: Int,pageSize: Int,filter: TransactionFilter): TransactionPageResult {
                assertEquals(3,page); assertEquals(10,pageSize); assertEquals(TransactionType.Expense,filter.type)
                return TransactionPageResult.Success(TransactionPage(emptyList(),3,10,23))
            }
        }
        val repo = cacheTransactionHistory(remote,ledger,1) { true } as FilterableTransactionRepository
        assertEquals(23L,(repo.readPage(3,10,filter) as TransactionPageResult.Success).page.totalCount)
    }

    @Test fun localFilterHonorsPeriodTypeCategoryAndOrder() {
        val rows = (1..3).map { day -> LocalHistoryTransaction(UUID.randomUUID(),
            TransactionDraft(draft.type,draft.categoryId,draft.amount,draft.currencyCode,LocalDate.of(2026,11,day),draft.description), "Food", LocalTransactionState.Pending) }
        val filter = TransactionFilter(type=TransactionType.Expense, categoryId=7, categoryName="Food",
            fromDate=LocalDate.of(2026,11,2),toDate=LocalDate.of(2026,11,3),sort=TransactionListSort.Oldest)
        assertEquals(listOf(2,3),filterLocalHistory(rows,filter).map { it.draft.transactionDate.dayOfMonth })
        assertTrue(filterLocalHistory(rows,filter.copy(type=TransactionType.Income)).isEmpty())
    }

    @Test fun cancelledReadPropagatesWithoutCaching() = runTest {
        val ledger = InMemoryLedger()
        val remote = object : TransactionRepository {
            override suspend fun readPage(page: Int,pageSize: Int): TransactionPageResult = throw CancellationException()
        }
        try { cacheTransactionHistory(remote,ledger,1) { true }.readPage(); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertTrue(ledger.history(1).isEmpty())
    }

    @Test fun reenqueueCannotResetRejectedOrSyncedRows() = runTest {
        val ledger = InMemoryLedger(); val request = CreateTransactionRequest(UUID.randomUUID(),draft)
        ledger.enqueue(1,request,null); ledger.markRejected(1,request.clientId)
        ledger.enqueue(1,CreateTransactionRequest(request.clientId, TransactionDraft(draft.type,draft.categoryId,BigDecimal("99.99"),draft.currencyCode,draft.transactionDate,draft.description)),null)
        assertEquals(LocalTransactionState.Rejected,ledger.history(1).single().state)
        assertEquals(draft.amount,ledger.history(1).single().draft.amount)
        ledger.recordSynced(1,listOf(tx(request.clientId)))
        ledger.markRejected(1,request.clientId); ledger.enqueue(1,request,null)
        assertEquals(LocalTransactionState.Synced,ledger.history(1).single().state)
    }
}
