package com.elect3.mobdev.personalfinance.domain.dashboard

import com.elect3.mobdev.personalfinance.domain.model.DashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import java.time.YearMonth
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface DashboardSummaryResult {
    data class Success(val summary: DashboardSummary) : DashboardSummaryResult
    data class RepositoryFailure(val reason: TransactionFailure) : DashboardSummaryResult
    data class InvalidSnapshot(val detail: String) : DashboardSummaryResult
}

/**
 * Complete reads for a stable dataset, such as the deterministic mock repository.
 * Page metadata cannot prove isolation for a concurrently changing repository; such a source
 * requires a snapshot token or authoritative summary API before using this loader.
 * Unexpected repository exceptions propagate, including coroutine cancellation.
 */
class LoadDashboardSummary(
    private val repository: TransactionRepository,
    private val calculator: DashboardSummaryCalculator = DashboardSummaryCalculator(),
) {
    suspend operator fun invoke(fallbackMonth: YearMonth): DashboardSummaryResult {
        val records = LinkedHashMap<Int, Transaction>()
        var expectedTotal: Long? = null
        var pageNumber = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = repository.readPage(pageNumber, PAGE_SIZE)
            currentCoroutineContext().ensureActive()
            when (result) {
                is TransactionPageResult.Failure -> return DashboardSummaryResult.RepositoryFailure(result.reason)
                is TransactionPageResult.Success -> {
                    val page = result.page
                    if (page.page != pageNumber || page.pageSize != PAGE_SIZE) {
                        return invalid("Unexpected pagination metadata")
                    }
                    if (expectedTotal != null && expectedTotal != page.totalCount) {
                        return invalid("Total record count changed during pagination")
                    }
                    expectedTotal = page.totalCount
                    for (transaction in page.items) {
                        val existing = records.putIfAbsent(transaction.transactionId, transaction)
                        if (existing != null) {
                            return invalid(if (existing == transaction) "Repeated transaction ID" else "Conflicting transaction ID")
                        }
                    }
                    val remaining = page.totalCount - records.size.toLong()
                    if (remaining < 0) return invalid("Records exceed reported total")
                    if (remaining == 0L) break
                    if (page.items.isEmpty()) return invalid("Premature empty page")
                    if (page.items.size != PAGE_SIZE) return invalid("Incomplete non-final page")
                    if (pageNumber == Int.MAX_VALUE) return invalid("Page number exhausted")
                    pageNumber++
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val summary = try {
            calculator.calculate(records.values.toList(), fallbackMonth)
        } catch (exception: IllegalArgumentException) {
            return invalid(exception.message ?: "Invalid transaction data")
        }
        currentCoroutineContext().ensureActive()
        return DashboardSummaryResult.Success(summary)
    }

    private fun invalid(detail: String) = DashboardSummaryResult.InvalidSnapshot(detail)

    private companion object {
        const val PAGE_SIZE = 20
    }
}
