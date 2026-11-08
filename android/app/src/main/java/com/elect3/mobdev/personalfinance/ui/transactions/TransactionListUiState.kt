package com.elect3.mobdev.personalfinance.ui.transactions

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import java.util.Collections

sealed interface TransactionListUiState {
    data object InitialLoading : TransactionListUiState
    data object Empty : TransactionListUiState
    data class InitialError(val reason: TransactionFailure) : TransactionListUiState
    data class SessionExpired(val restart: DemoRestartState) : TransactionListUiState

    /** Defensive snapshot; refresh and append can never be in progress together. */
    class Content(
        items: List<Transaction>,
        val lastSuccessfulPage: Int,
        val totalCount: Long,
        val append: TransactionAppendState,
        val isRefreshing: Boolean = false,
        val refreshError: TransactionFailure? = null,
    ) : TransactionListUiState {
        val items: List<Transaction> = Collections.unmodifiableList(ArrayList(items))

        init {
            require(this.items.isNotEmpty())
            require(lastSuccessfulPage >= 1 && totalCount >= 0)
            require(!isRefreshing || (append != TransactionAppendState.Loading && refreshError == null))
        }
    }
}

sealed interface TransactionAppendState {
    data object Ready : TransactionAppendState
    data object Loading : TransactionAppendState
    data class Error(val reason: TransactionFailure) : TransactionAppendState
    data object End : TransactionAppendState
}

/** No authentication is performed. Unavailable replacement may be retried explicitly. */
enum class DemoRestartState {
    Available,
    Unsupported,
    ReplacementFailed,
}
