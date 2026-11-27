package com.elect3.mobdev.personalfinance.domain.reports

import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A stored, immutable monthly snapshot written by the worker at the start of the next month (C4). */
data class MonthlySnapshot(
    val month: YearMonth,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    val netCashFlow: BigDecimal,
    val savingsAllocated: BigDecimal,
    val generatedAtUtc: Instant,
    val currency: String,
)

/** Server reporting contracts. Figures are shown exactly as received after validation, never recomputed. */
interface ReportRepository {
    /** `GET api/reports/monthly?month=YYYY-MM-01` (C6): the same report MVC shows. */
    suspend fun monthly(month: YearMonth): FinanceResult<MonthlyReport>
    /** `GET api/reports/summaries` (C4), newest first. */
    suspend fun summaries(page: Int = 1, pageSize: Int = 12): FinanceResult<List<MonthlySnapshot>>
}

/**
 * Signed-in Insights: the selected month and each trend month are the server's own monthly report, so totals,
 * savings contributed and budgets match MVC to the cent. Any failed month withholds the whole report.
 */
class LoadServerReport(private val repository: ReportRepository) {
    suspend operator fun invoke(month: YearMonth): FinancialReportResult {
        val reports = ArrayList<MonthlyReport>()
        for (m in ReportPeriod.trendMonths(month)) {
            currentCoroutineContext().ensureActive()
            when (val result = repository.monthly(m)) {
                is FinanceResult.Failure -> return failure(result.reason)
                is FinanceResult.Success -> reports += result.value
            }
        }
        val selected = reports.last()
        // An account switch or base-currency change mid-read must not mix currencies in one chart.
        if (reports.any { it.currency != selected.currency }) return FinancialReportResult.InvalidData("Mixed report currencies")
        val trend = reports.map { MonthTrendPoint(it.month, it.totalIncome, it.totalExpenses, it.transactionCount) }
        return FinancialReportResult.Success(FinancialReport(selected.currency, selected, trend))
    }

    private fun failure(reason: FinanceFailure): FinancialReportResult = when (reason) {
        FinanceFailure.MissingSession, FinanceFailure.SessionExpired -> FinancialReportResult.RepositoryFailure(TransactionFailure.SessionExpired)
        FinanceFailure.InvalidResponse -> FinancialReportResult.InvalidData("Invalid server report")
        else -> FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable)
    }
}
