package com.elect3.mobdev.personalfinance.domain.reports

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.FilterableTransactionRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import java.time.YearMonth
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface FinancialReportResult {
    data class Success(val report: FinancialReport) : FinancialReportResult
    data class RepositoryFailure(val reason: TransactionFailure) : FinancialReportResult
    /** The API returned data that breaks its own contract (pagination or amount snapshot). */
    data class InvalidData(val detail: String) : FinancialReportResult
    /** The signed-in account's base currency is unknown or changed while reading, so totals cannot be labelled. */
    data object CurrencyUnavailable : FinancialReportResult
}

/**
 * Mock Demo only: builds a [FinancialReport] from a transaction repository's `fromDate`/`toDate` filter over the
 * selected month's trend window. Signed-in accounts use [LoadServerReport] (the server's own monthly report). Every page is read and checked
 * (page numbers, stable `totalCount`, no duplicate or missing rows) before anything is totalled, so a partial
 * read is never shown as a month's figures. Unexpected repository exceptions and cancellation propagate.
 */
class LoadFinancialReport(
    private val repository: FilterableTransactionRepository,
    /** The account's base currency (the demo passes its fixed PHP); read before and after loading. */
    private val baseCurrency: () -> String?,
    private val calculator: FinancialReportCalculator = FinancialReportCalculator(),
    private val pageSize: Int = MAX_PAGE_SIZE,
) {
    init { require(pageSize in 1..MAX_PAGE_SIZE) { "Invalid page size" } }

    suspend operator fun invoke(month: YearMonth): FinancialReportResult {
        val currency = baseCurrency()?.takeIf { it.matches(CURRENCY) } ?: return FinancialReportResult.CurrencyUnavailable
        val window = ReportPeriod.window(month)
        val filter = TransactionFilter(fromDate = window.start, toDate = window.endInclusive, sort = TransactionListSort.Oldest)
        val records = LinkedHashMap<Int, Transaction>()
        var expectedTotal: Long? = null
        var pageNumber = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = repository.readPage(pageNumber, pageSize, filter)
            currentCoroutineContext().ensureActive()
            val page = when (result) {
                is TransactionPageResult.Failure -> return FinancialReportResult.RepositoryFailure(result.reason)
                is TransactionPageResult.Success -> result.page
            }
            if (page.page != pageNumber || page.pageSize != pageSize) return invalid("Unexpected pagination metadata")
            if (expectedTotal != null && expectedTotal != page.totalCount) return invalid("Total changed during pagination")
            expectedTotal = page.totalCount
            for (transaction in page.items) {
                if (records.putIfAbsent(transaction.transactionId, transaction) != null) return invalid("Repeated transaction ID")
            }
            val remaining = page.totalCount - records.size
            if (remaining < 0) return invalid("Records exceed reported total")
            if (remaining == 0L) break
            if (page.items.size != pageSize) return invalid("Incomplete non-final page")
            if (pageNumber == Int.MAX_VALUE) return invalid("Page number exhausted")
            pageNumber++
        }
        // An account switch or re-login mid-read must not label one account's rows with another's currency.
        if (baseCurrency() != currency) return FinancialReportResult.CurrencyUnavailable
        val report = try {
            calculator.calculate(records.values.toList(), month, currency)
        } catch (e: IllegalArgumentException) {
            return invalid(e.message ?: "Invalid transaction data")
        }
        currentCoroutineContext().ensureActive()
        return FinancialReportResult.Success(report)
    }

    private fun invalid(detail: String) = FinancialReportResult.InvalidData(detail)

    companion object {
        /** The API's maximum page size (FinanceValidation.ValidPage). */
        const val MAX_PAGE_SIZE = 100
        private val CURRENCY = Regex("[A-Z]{3}")
    }
}
