package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.FilterableTransactionRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One instance represents one mock session; recreate it to reset failure-once scenarios. */
class MockTransactionRepository(
    private val scenario: MockScenario = MockScenario.Normal,
    /** Demo-session additions (in memory only); null keeps the fixed fixtures alone. */
    private val ledger: DemoLedger? = null,
) : FilterableTransactionRepository {
    private val failureConsumed = AtomicBoolean(false)

    override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult =
        readPage(page, pageSize, TransactionFilter())

    /** Filters and orders the COMPLETE fixture set before paging, mirroring the API's server-side behaviour. */
    override suspend fun readPage(page: Int, pageSize: Int, filter: TransactionFilter): TransactionPageResult {
        currentCoroutineContext().ensureActive()
        require(page >= 1) { "page must be at least 1" }
        require(pageSize in 1..100) { "pageSize must be within 1..100" }

        if (scenario == MockScenario.SessionExpired) {
            return TransactionPageResult.Failure(TransactionFailure.SessionExpired)
        }

        val shouldFail = (scenario == MockScenario.InitialFailureOnce && page == 1) ||
            (scenario == MockScenario.SecondPageFailureOnce && page == 2)
        if (shouldFail && failureConsumed.compareAndSet(false, true)) {
            return TransactionPageResult.Failure(TransactionFailure.Unavailable)
        }

        val fixtures = if (scenario == MockScenario.Empty) emptyList() else TransactionFixtures.items
        val added = ledger?.snapshot().orEmpty()
        val all = fixtures + added
        val source = if (filter.isDefault && added.isEmpty()) all else all.filter { t ->
            (filter.type == null || t.type == filter.type) &&
                (filter.categoryId == null || t.categoryId == filter.categoryId) &&
                (filter.fromDate == null || !t.transactionDate.isBefore(filter.fromDate)) &&
                (filter.toDate == null || !t.transactionDate.isAfter(filter.toDate))
        }.sortedWith(when (filter.sort) {
            // Same keys as the API: transaction date, then transaction ID in the same direction.
            TransactionListSort.Newest -> compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.transactionId }
            TransactionListSort.Oldest -> compareBy<Transaction> { it.transactionDate }.thenBy { it.transactionId }
        })
        // Long arithmetic avoids overflow even for requests far beyond the last page.
        val offset = (page.toLong() - 1L) * pageSize
        val items = if (offset >= source.size.toLong()) {
            emptyList()
        } else {
            val start = offset.toInt()
            source.subList(start, minOf(start + pageSize, source.size))
        }
        currentCoroutineContext().ensureActive()
        return TransactionPageResult.Success(
            TransactionPage(items, page, pageSize, source.size.toLong()),
        )
    }
}
