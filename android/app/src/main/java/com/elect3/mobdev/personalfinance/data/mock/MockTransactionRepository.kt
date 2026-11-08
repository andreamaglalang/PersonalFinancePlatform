package com.elect3.mobdev.personalfinance.data.mock

import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One instance represents one mock session; recreate it to reset failure-once scenarios. */
class MockTransactionRepository(
    private val scenario: MockScenario = MockScenario.Normal,
) : TransactionRepository {
    private val failureConsumed = AtomicBoolean(false)

    override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult {
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

        val source = if (scenario == MockScenario.Empty) emptyList() else TransactionFixtures.items
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
