package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.data.mock.MockScenario
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LoadDashboardSummaryTest {
    private val fallback = YearMonth.of(2020, 1)
    private val rows = TransactionFixtures.items

    private class ScriptedRepository(
        private val response: suspend (Int, Int) -> TransactionPageResult,
    ) : TransactionRepository {
        val requests = mutableListOf<Pair<Int, Int>>()
        override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult {
            requests += page to pageSize
            return response(page, pageSize)
        }
    }

    private fun success(page: Int, size: Int = 20, total: Long = 45) = TransactionPageResult.Success(
        TransactionPage(rows.drop((page - 1) * 20).take(20), page, size, total),
    )

    @Test fun allThreeMockPagesAreLoadedBeforeSuccess() = runTest {
        val mock = MockTransactionRepository()
        val repository = ScriptedRepository { page, size -> mock.readPage(page, size) }
        val result = LoadDashboardSummary(repository)(fallback) as DashboardSummaryResult.Success
        assertEquals(listOf(1 to 20, 2 to 20, 3 to 20), repository.requests)
        assertEquals(45L, result.summary.recordCount)
        assertEquals(YearMonth.of(2026, 11), result.summary.reportingMonth)
        assertEquals(0, BigDecimal("46250.75").compareTo(result.summary.totalIncome))
    }

    @Test fun emptyCompleteDatasetSucceedsUsingFallback() = runTest {
        val result = LoadDashboardSummary(MockTransactionRepository(MockScenario.Empty))(fallback)
            as DashboardSummaryResult.Success
        assertEquals(0L, result.summary.recordCount)
        assertEquals(fallback, result.summary.reportingMonth)
        assertTrue(result.summary.recentTransactions.isEmpty())
    }

    @Test fun laterPageFailurePreservesTypedReasonAndProducesNoSummary() = runTest {
        for (reason in TransactionFailure.entries) {
            val repository = ScriptedRepository { page, _ ->
                if (page == 1) success(1) else TransactionPageResult.Failure(reason)
            }
            assertEquals(DashboardSummaryResult.RepositoryFailure(reason), LoadDashboardSummary(repository)(fallback))
            assertEquals(2, repository.requests.size)
        }
    }

    @Test fun existingFailureOnceScenarioCanBeRetriedFromBeginning() = runTest {
        val loader = LoadDashboardSummary(MockTransactionRepository(MockScenario.SecondPageFailureOnce))
        assertEquals(DashboardSummaryResult.RepositoryFailure(TransactionFailure.Unavailable), loader(fallback))
        assertEquals(45L, (loader(fallback) as DashboardSummaryResult.Success).summary.recordCount)
    }

    @Test fun repeatedAndConflictingIdsAcrossPagesAreRejected() = runTest {
        for (duplicate in listOf(rows.first(), rows.first().copy(description = "Conflict"))) {
            val repository = ScriptedRepository { page, _ ->
                if (page == 1) success(1) else TransactionPageResult.Success(
                    TransactionPage(listOf(duplicate), 2, 20, 45),
                )
            }
            val result = LoadDashboardSummary(repository)(fallback) as DashboardSummaryResult.InvalidSnapshot
            assertEquals(if (duplicate == rows.first()) "Repeated transaction ID" else "Conflicting transaction ID", result.detail)
        }
    }

    @Test fun repeatedIdsWithinOnePageAreRejected() = runTest {
        val repository = ScriptedRepository { _, _ ->
            TransactionPageResult.Success(TransactionPage(listOf(rows.first(), rows.first()), 1, 20, 2))
        }
        assertTrue(LoadDashboardSummary(repository)(fallback) is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun wrongPageNumberOrPageSizeIsRejected() = runTest {
        for ((number, size) in listOf(2 to 20, 1 to 21)) {
            val repository = ScriptedRepository { _, _ ->
                TransactionPageResult.Success(TransactionPage(rows.take(20), number, size, 45))
            }
            assertTrue(LoadDashboardSummary(repository)(fallback) is DashboardSummaryResult.InvalidSnapshot)
        }
    }

    @Test fun inconsistentTotalsAreRejected() = runTest {
        val repository = ScriptedRepository { page, _ -> success(page, total = if (page == 1) 45 else 44) }
        assertTrue(LoadDashboardSummary(repository)(fallback) is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun prematureEmptyShortAndExcessPagesAreRejected() = runTest {
        val responses = listOf(
            TransactionPage(emptyList(), 2, 20, 45),
            TransactionPage(rows.drop(20).take(1), 2, 20, 45),
        )
        for (response in responses) {
            val repository = ScriptedRepository { page, _ ->
                if (page == 1) success(1) else TransactionPageResult.Success(response)
            }
            assertTrue(LoadDashboardSummary(repository)(fallback) is DashboardSummaryResult.InvalidSnapshot)
        }
        val excess = ScriptedRepository { _, _ ->
            TransactionPageResult.Success(TransactionPage(rows.take(2), 1, 20, 1))
        }
        assertTrue(LoadDashboardSummary(excess)(fallback) is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun invalidMoneyIsNotPublishedAsSuccess() = runTest {
        val repository = ScriptedRepository { _, _ ->
            TransactionPageResult.Success(TransactionPage(listOf(rows.first().copy(currencyCode = "USD")), 1, 20, 1))
        }
        assertTrue(LoadDashboardSummary(repository)(fallback) is DashboardSummaryResult.InvalidSnapshot)
    }

    @Test fun cancellationWhileWaitingForLaterPagePropagates() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repository = ScriptedRepository { page, _ ->
            if (page == 1) success(1) else {
                entered.complete(Unit)
                CompletableDeferred<TransactionPageResult>().await()
            }
        }
        val request = async { LoadDashboardSummary(repository)(fallback) }
        entered.await()
        request.cancel()
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(2, repository.requests.size)
        try {
            request.await()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // No partial summary result.
        }
    }

    @Test fun cancellationAfterImmediateRepositoryResponseIsChecked() = runTest {
        val repository = ScriptedRepository { _, _ ->
            currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            TransactionPageResult.Success(TransactionPage(emptyList(), 1, 20, 0))
        }
        val request = async { LoadDashboardSummary(repository)(fallback) }
        try {
            request.await()
            fail("Cancelled response must not produce success")
        } catch (_: CancellationException) {
            assertTrue(request.isCancelled)
        }
    }

    @Test fun unexpectedRepositoryExceptionIsNotConvertedToFinancialSuccess() = runTest {
        val repository = ScriptedRepository { _, _ -> throw IllegalStateException("Broken repository") }
        try {
            LoadDashboardSummary(repository)(fallback)
            fail("Unexpected exception must propagate")
        } catch (exception: IllegalStateException) {
            assertEquals("Broken repository", exception.message)
        }
    }
}
