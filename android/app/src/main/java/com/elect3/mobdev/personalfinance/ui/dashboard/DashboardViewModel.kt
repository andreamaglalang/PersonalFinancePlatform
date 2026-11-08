package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.dashboard.DashboardSummaryResult
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth

/** The owner supplies the loader and an explicit fallback month; no clock or repository creation. */
class DashboardViewModel @MainThread constructor(
    private val loader: LoadDashboardSummary,
    private val fallbackMonth: YearMonth,
) : ViewModel() {
    private val mutableState = MutableStateFlow<DashboardUiState>(DashboardUiState.InitialLoading)
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L

    init { load() }

    @MainThread
    fun retry() {
        if (mutableState.value is DashboardUiState.InitialFailure && request?.isActive != true) load()
    }

    @MainThread
    fun refresh() {
        val current = mutableState.value
        if (current is DashboardUiState.Content && !current.isRefreshing) load(current)
        else if (current == DashboardUiState.Empty && request?.isActive != true) load()
    }

    private fun load(previous: DashboardUiState.Content? = null) {
        val token = ++generation
        request?.cancel()
        mutableState.value = previous?.copy(isRefreshing = true, refreshError = null)
            ?: DashboardUiState.InitialLoading
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    loader(fallbackMonth)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DashboardSummaryResult.RepositoryFailure(TransactionFailure.Unavailable)
                }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                mutableState.value = when (result) {
                    is DashboardSummaryResult.Success -> if (result.summary.recordCount == 0L) DashboardUiState.Empty
                        else DashboardUiState.Content(result.summary)
                    is DashboardSummaryResult.RepositoryFailure -> if (result.reason == TransactionFailure.SessionExpired)
                        DashboardUiState.SessionExpired
                        else failure(DashboardError.Repository(result.reason), previous)
                    is DashboardSummaryResult.InvalidSnapshot -> failure(DashboardError.InvalidSnapshot, previous)
                }
            } finally {
                if (token == generation) request = null
            }
        }
        request = job
        job.start()
    }

    private fun failure(error: DashboardError, previous: DashboardUiState.Content?): DashboardUiState =
        previous?.copy(isRefreshing = false, refreshError = error) ?: DashboardUiState.InitialFailure(error)

    class Factory(private val loader: LoadDashboardSummary, private val fallbackMonth: YearMonth) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == DashboardViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(loader, fallbackMonth) as T
        }
    }
}
