package com.elect3.mobdev.personalfinance.testutil

import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.domain.repository.TransactionPageResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Scheduler-confined fake: use from the same test dispatcher as the ViewModel. No real delays. */
class ControlledTransactionRepository(
    private val completeDespiteCancellation: Boolean = false,
) : TransactionRepository {
    class Request internal constructor(
        val page: Int,
        val pageSize: Int,
        private val caller: Job?,
    ) {
        internal val response = CompletableDeferred<TransactionPageResult>()
        val isCancelled: Boolean get() = caller?.isCancelled == true
        val isCompleted: Boolean get() = response.isCompleted

        fun succeed(value: TransactionPage) {
            require(value.page == page && value.pageSize == pageSize)
            check(response.complete(TransactionPageResult.Success(value))) { "Request already completed" }
        }

        fun fail(reason: TransactionFailure = TransactionFailure.Unavailable) {
            check(response.complete(TransactionPageResult.Failure(reason))) { "Request already completed" }
        }
    }

    private val recorded = mutableListOf<Request>()
    val requests: List<Request> get() = recorded.toList()

    override suspend fun readPage(page: Int, pageSize: Int): TransactionPageResult {
        val context = currentCoroutineContext()
        context.ensureActive()
        require(page >= 1 && pageSize in 1..100)
        val request = Request(page, pageSize, context[Job])
        recorded += request
        // Optional deliberately uncooperative producer for stale-result tests. Tests must
        // explicitly complete every such request, even after cancellation, to finish cleanup.
        return if (completeDespiteCancellation) {
            withContext(NonCancellable) { request.response.await() }
        } else {
            request.response.await()
        }
    }
}
