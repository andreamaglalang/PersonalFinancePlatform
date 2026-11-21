package com.elect3.mobdev.personalfinance.domain.reports

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.reports.ReportTestData.expense
import com.elect3.mobdev.personalfinance.domain.repository.FilterableTransactionRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadFinancialReportTest {
    private val nov = YearMonth.of(2026, 11)

    /** Serves [rows] in pages; [override] can replace any page's result. Records every request. */
    private class FakeRepo(
        val rows: List<Transaction>,
        val reportedTotal: (Int) -> Long = { rows.size.toLong() },
        val override: (Int) -> TransactionPageResult? = { null },
    ) : FilterableTransactionRepository {
        val requests = mutableListOf<Triple<Int, Int, TransactionFilter>>()
        override suspend fun readPage(page: Int, pageSize: Int) = error("Reports must use the filtered read")
        override suspend fun readPage(page: Int, pageSize: Int, filter: TransactionFilter): TransactionPageResult {
            requests += Triple(page, pageSize, filter)
            override(page)?.let { return it }
            val items = rows.drop((page - 1) * pageSize).take(pageSize)
            return TransactionPageResult.Success(TransactionPage(items, page, pageSize, reportedTotal(page)))
        }
    }

    private fun rows(n: Int) = (1..n).map { expense(it, "1.00", LocalDate.of(2026, 11, 1 + it % 28)) }

    @Test fun requestsTheSelectedMonthsTrendWindowWithServerSideDateFilter() = runTest {
        val repo = FakeRepo(rows(3))
        val result = LoadFinancialReport(repo, { "PHP" })(nov) as FinancialReportResult.Success
        val (page, size, filter) = repo.requests.single()
        assertEquals(1, page)
        assertEquals(100, size)
        assertEquals(LocalDate.of(2026, 6, 1), filter.fromDate)
        assertEquals(LocalDate.of(2026, 11, 30), filter.toDate)
        assertEquals(TransactionListSort.Oldest, filter.sort)
        assertEquals(null, filter.type); assertEquals(null, filter.categoryId)
        assertEquals(0, BigDecimal("3.00").compareTo(result.report.selected.totalExpenses))
    }

    @Test fun readsEveryPageBeforeTotalling() = runTest {
        val repo = FakeRepo(rows(5))
        val result = LoadFinancialReport(repo, { "PHP" }, pageSize = 2)(nov) as FinancialReportResult.Success
        assertEquals(listOf(1, 2, 3), repo.requests.map { it.first })
        assertEquals(5, result.report.selected.transactionCount)
    }

    @Test fun emptyMonthIsASuccessfulEmptyReport() = runTest {
        val result = LoadFinancialReport(FakeRepo(emptyList()), { "PHP" })(nov) as FinancialReportResult.Success
        assertTrue(result.report.windowIsEmpty)
        assertEquals(nov, result.report.selected.month)
    }

    @Test fun sessionExpiryAndUnavailabilityPropagate() = runTest {
        val expired = FakeRepo(rows(1), override = { TransactionPageResult.Failure(TransactionFailure.SessionExpired) })
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.SessionExpired), LoadFinancialReport(expired, { "PHP" })(nov))
        // A failure on a later page never yields a partial report.
        val second = FakeRepo(rows(3), override = { if (it == 2) TransactionPageResult.Failure(TransactionFailure.Unavailable) else null })
        assertEquals(FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable), LoadFinancialReport(second, { "PHP" }, pageSize = 2)(nov))
    }

    @Test fun paginationThatBreaksTheContractIsInvalid() = runTest {
        val changing = FakeRepo(rows(3), reportedTotal = { if (it == 1) 3 else 4 })
        assertTrue(LoadFinancialReport(changing, { "PHP" }, pageSize = 2)(nov) is FinancialReportResult.InvalidData)
        val short = FakeRepo(rows(1), reportedTotal = { 5 })
        assertTrue(LoadFinancialReport(short, { "PHP" }, pageSize = 2)(nov) is FinancialReportResult.InvalidData)
        val outside = FakeRepo(listOf(expense(1, "1.00", LocalDate.of(2026, 12, 1))))
        assertTrue(LoadFinancialReport(outside, { "PHP" })(nov) is FinancialReportResult.InvalidData)
    }

    @Test fun unknownOrChangedBaseCurrencyIsNeverGuessed() = runTest {
        val repo = FakeRepo(rows(1))
        assertEquals(FinancialReportResult.CurrencyUnavailable, LoadFinancialReport(repo, { null })(nov))
        assertTrue(repo.requests.isEmpty())
        var currency = "PHP"
        val switching = FakeRepo(rows(1), override = { currency = "USD"; null })
        assertEquals(FinancialReportResult.CurrencyUnavailable, LoadFinancialReport(switching, { currency })(nov))
    }
}
