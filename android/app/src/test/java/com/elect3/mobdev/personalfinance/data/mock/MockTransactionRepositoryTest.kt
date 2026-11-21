package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MockTransactionRepositoryTest {
    @Test
    fun fixturesHaveStableUniqueIdentityAndCategoryCoverage() {
        val rows = TransactionFixtures.items
        assertEquals(45, rows.size)
        assertEquals((1..45).toSet(), rows.map { it.transactionId }.toSet())
        assertEquals(45, rows.map { it.clientId }.toSet().size)
        assertEquals(setOf("Salary", "Freelance", "Food", "Transport", "Utilities", "Shopping", "Healthcare"), rows.map { it.categoryName }.toSet())
        assertEquals(6, rows.count { it.type == TransactionType.Income })
        assertEquals(39, rows.count { it.type == TransactionType.Expense })
        assertTrue(rows.any { it.description == null })
        assertTrue(rows.any { it.billId != null && it.billDueDate != null })
        assertTrue(rows.any { it.billId == null && it.billDueDate == null })
        assertTrue(rows.zipWithNext().any { (a, b) -> a.transactionDate == b.transactionDate })
        for ((a, b) in rows.zipWithNext()) {
            assertTrue(a.transactionDate > b.transactionDate || (a.transactionDate == b.transactionDate && a.transactionId > b.transactionId))
        }
        for (row in rows) {
            assertEquals("PHP", row.currencyCode)
            assertEquals(2, row.amount.scale())
            assertTrue(row.amount > BigDecimal.ZERO)
            assertEquals(BigDecimal("1.00000000"), row.fxRate)
            assertEquals(row.amount, row.amountBase)
            assertEquals(row.transactionDate, row.fxRateDate)
            val expectedType = if (row.categoryName in setOf("Salary", "Freelance")) TransactionType.Income else TransactionType.Expense
            assertEquals(expectedType, row.type)
        }
    }

    @Test
    fun normalPaginationIncludesEmptyFourthPageAndStableSlices() = runTest {
        val repository = MockTransactionRepository()
        val loadedIds = mutableListOf<Int>()
        for ((index, count) in listOf(20, 20, 5, 0).withIndex()) {
            val page = (repository.readPage(index + 1) as TransactionPageResult.Success).page
            assertEquals(index + 1, page.page)
            assertEquals(20, page.pageSize)
            assertEquals(45L, page.totalCount)
            assertEquals(count, page.items.size)
            loadedIds += page.items.map { it.transactionId }
        }
        assertEquals((45 downTo 1).toList(), loadedIds)
        val first = (repository.readPage() as TransactionPageResult.Success).page
        assertEquals((45 downTo 26).toList(), first.items.map { it.transactionId })
        assertTrue((repository.readPage(Int.MAX_VALUE, 100) as TransactionPageResult.Success).page.items.isEmpty())
        assertEquals(45, (repository.readPage(1, 100) as TransactionPageResult.Success).page.items.size)
        assertEquals(1, (repository.readPage(1, 1) as TransactionPageResult.Success).page.items.size)
    }

    @Test
    fun emptyAndExpiredScenariosRemainConsistent() = runTest {
        for (pageNumber in listOf(1, 2)) {
            val empty = (MockTransactionRepository(MockScenario.Empty).readPage(pageNumber) as TransactionPageResult.Success).page
            assertTrue(empty.items.isEmpty())
            assertEquals(0L, empty.totalCount)
        }
        val expired = MockTransactionRepository(MockScenario.SessionExpired)
        repeat(2) {
            assertEquals(TransactionPageResult.Failure(TransactionFailure.SessionExpired), expired.readPage())
        }
    }

    @Test
    fun failureOnceScenariosOnlyConsumeTheirTargetPageAndAreInstanceScoped() = runTest {
        for ((scenario, target) in listOf(MockScenario.InitialFailureOnce to 1, MockScenario.SecondPageFailureOnce to 2)) {
            val repository = MockTransactionRepository(scenario)
            assertTrue(repository.readPage(if (target == 1) 2 else 1) is TransactionPageResult.Success)
            assertEquals(TransactionPageResult.Failure(TransactionFailure.Unavailable), repository.readPage(target))
            repeat(2) { assertTrue(repository.readPage(target) is TransactionPageResult.Success) }
            assertEquals(TransactionPageResult.Failure(TransactionFailure.Unavailable), MockTransactionRepository(scenario).readPage(target))
        }
    }

    @Test
    fun invalidPaginationIsRejected() = runTest {
        val repository = MockTransactionRepository()
        for ((page, size) in listOf(0 to 20, -1 to 20, 1 to 0, 1 to 101)) {
            try {
                repository.readPage(page, size)
                fail("Expected invalid pagination to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected caller error, not a typed availability failure.
            }
        }
    }

    @Test
    fun cancelledCallerCannotConsumeFailureOnceState() = runTest {
        val repository = MockTransactionRepository(MockScenario.InitialFailureOnce)
        val request = async(start = CoroutineStart.LAZY) { repository.readPage() }
        request.cancel()
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(TransactionPageResult.Failure(TransactionFailure.Unavailable), repository.readPage())
    }
}
