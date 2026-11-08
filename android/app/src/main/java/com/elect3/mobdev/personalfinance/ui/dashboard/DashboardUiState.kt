package com.elect3.mobdev.personalfinance.ui.dashboard

import com.elect3.mobdev.personalfinance.domain.model.DashboardSummary
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure

sealed interface DashboardUiState {
    data object InitialLoading : DashboardUiState
    data object Empty : DashboardUiState
    data class InitialFailure(val error: DashboardError) : DashboardUiState
    data object SessionExpired : DashboardUiState
    data class Content(
        val summary: DashboardSummary,
        val isRefreshing: Boolean = false,
        val refreshError: DashboardError? = null,
    ) : DashboardUiState {
        init {
            require(summary.recordCount > 0)
            require(!isRefreshing || refreshError == null)
        }
    }
}

sealed interface DashboardError {
    data class Repository(val reason: TransactionFailure) : DashboardError
    data object InvalidSnapshot : DashboardError
}
