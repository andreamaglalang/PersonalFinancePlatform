package com.elect3.mobdev.personalfinance.domain.repository

import com.elect3.mobdev.personalfinance.domain.model.TransactionPage

interface TransactionRepository {
    /** Invalid pagination is a caller error. Implementations must propagate cancellation. */
    suspend fun readPage(page: Int = 1, pageSize: Int = 20): TransactionPageResult
}

sealed interface TransactionPageResult {
    data class Success(val page: TransactionPage) : TransactionPageResult
    data class Failure(val reason: TransactionFailure) : TransactionPageResult
}

enum class TransactionFailure {
    Unavailable,
    SessionExpired,
}
