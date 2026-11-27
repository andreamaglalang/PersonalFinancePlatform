package com.elect3.mobdev.personalfinance.ui.reports

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReport
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReportResult
import com.elect3.mobdev.personalfinance.domain.reports.MonthlySnapshot
import com.elect3.mobdev.personalfinance.domain.reports.ReportPeriod
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every state names the month it describes, so figures can never appear under another month's label. */
sealed interface ReportsUiState {
    val month: YearMonth

    data class Loading(override val month: YearMonth) : ReportsUiState

    /**
     * A complete report for [month]. When a refresh fails, the figures read earlier in this session stay visible
     * with [refreshError]; nothing is persisted, so there is no offline copy beyond that.
     */
    data class Ready(
        override val month: YearMonth,
        val report: FinancialReport,
        val isRefreshing: Boolean = false,
        val refreshError: ReportsError? = null,
    ) : ReportsUiState {
        init {
            require(report.selected.month == month) { "Report month must match the label" }
            require(!isRefreshing || refreshError == null)
        }
    }

    data class Failure(override val month: YearMonth, val error: ReportsError) : ReportsUiState
    data class SessionExpired(override val month: YearMonth) : ReportsUiState
}

/** Stored monthly snapshots (C4) below the report. Hidden where the owner supplies none (Mock Demo). */
sealed interface SnapshotsUiState {
    data object Hidden : SnapshotsUiState
    data object Loading : SnapshotsUiState
    data class Ready(val items: List<MonthlySnapshot>) : SnapshotsUiState
    data class Failure(val error: ReportsError) : SnapshotsUiState
}

enum class ReportsError {
    /** No response or a server error: offline, unreachable or temporarily unavailable. */
    Unavailable,
    /** The server's data did not match its contract; figures are withheld rather than guessed. */
    InvalidData,
    /** The account's base currency is not known, so totals cannot be labelled correctly. */
    CurrencyUnavailable,
}

/**
 * Financial Reports & Insights. The owner supplies the loader, the clock's current month and (for an account)
 * the session check and expiry callback. Month changes cancel the previous read and ignore its late result.
 */
class ReportsViewModel @MainThread constructor(
    private val loader: suspend (YearMonth) -> FinancialReportResult,
    private val currentMonth: () -> YearMonth,
    initialMonth: YearMonth = currentMonth(),
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
    private val snapshotLoader: (suspend () -> FinanceResult<List<MonthlySnapshot>>)? = null,
) : ViewModel() {
    private val mutableSnapshots = MutableStateFlow<SnapshotsUiState>(SnapshotsUiState.Hidden)
    val snapshots = mutableSnapshots.asStateFlow()
    private var snapshotRequest: Job? = null
    private val mutableState = MutableStateFlow<ReportsUiState>(ReportsUiState.Loading(ReportPeriod.clamp(initialMonth, currentMonth())))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var invalidated = false

    init { load(state.value.month); loadSnapshots() }

    val canGoBack: Boolean get() = ReportPeriod.canGoBack(state.value.month)
    val canGoForward: Boolean get() = ReportPeriod.canGoForward(state.value.month, currentMonth())

    @MainThread fun previousMonth() { if (canGoBack) selectMonth(state.value.month.minusMonths(1)) }
    @MainThread fun nextMonth() { if (canGoForward) selectMonth(state.value.month.plusMonths(1)) }

    /** Future months are clamped to the current month; the same month is a no-op. */
    @MainThread
    fun selectMonth(month: YearMonth) {
        if (invalidated) return
        val target = ReportPeriod.clamp(month, currentMonth())
        if (target == state.value.month && state.value !is ReportsUiState.Failure) return
        load(target)
    }

    @MainThread
    fun retry() {
        if (invalidated) return
        if (state.value is ReportsUiState.Failure) load(state.value.month)
    }

    /** Re-reads the shown month (e.g. on revisiting Insights or after a save), keeping figures visible meanwhile. */
    @MainThread
    fun refresh() {
        if (invalidated) return
        loadSnapshots()
        when (val current = state.value) {
            is ReportsUiState.Ready -> if (!current.isRefreshing) load(current.month, current)
            is ReportsUiState.Failure -> load(current.month)
            else -> Unit
        }
    }

    /** Logout or account switch: drop figures immediately and ignore late responses. */
    @MainThread
    fun invalidateSession() {
        invalidated = true
        generation++
        request?.cancel()
        request = null
        snapshotRequest?.cancel()
        if (snapshotLoader != null) mutableSnapshots.value = SnapshotsUiState.Hidden
        mutableState.value = ReportsUiState.SessionExpired(state.value.month)
    }

    /** Re-reads the stored snapshots; a read already running is kept. */
    @MainThread
    fun retrySnapshots() { if (!invalidated) loadSnapshots() }

    private fun loadSnapshots() {
        val loader = snapshotLoader ?: return
        if (snapshotRequest?.isActive == true) return
        if (mutableSnapshots.value !is SnapshotsUiState.Ready) mutableSnapshots.value = SnapshotsUiState.Loading
        snapshotRequest = viewModelScope.launch {
            val result = try { loader() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }
            if (invalidated) return@launch
            mutableSnapshots.value = when (result) {
                is FinanceResult.Success -> SnapshotsUiState.Ready(result.value)
                is FinanceResult.Failure -> when (result.reason) {
                    FinanceFailure.MissingSession, FinanceFailure.SessionExpired -> {
                        invalidated = true
                        generation++
                        request?.cancel()
                        mutableState.value = ReportsUiState.SessionExpired(state.value.month)
                        onSessionExpired()
                        SnapshotsUiState.Hidden
                    }
                    FinanceFailure.InvalidResponse -> SnapshotsUiState.Failure(ReportsError.InvalidData)
                    else -> SnapshotsUiState.Failure(ReportsError.Unavailable)
                }
            }
        }
    }

    private fun load(month: YearMonth, previous: ReportsUiState.Ready? = null) {
        val token = ++generation
        request?.cancel()
        // A different month never keeps the old month's figures on screen.
        mutableState.value = previous?.takeIf { it.month == month }?.copy(isRefreshing = true, refreshError = null)
            ?: ReportsUiState.Loading(month)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    loader(month)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FinancialReportResult.RepositoryFailure(TransactionFailure.Unavailable)
                }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                if (invalidated || !isSessionCurrent()) { invalidateSession(); return@launch }
                when (result) {
                    is FinancialReportResult.Success -> mutableState.value = ReportsUiState.Ready(month, result.report)
                    is FinancialReportResult.RepositoryFailure -> if (result.reason == TransactionFailure.SessionExpired) {
                        invalidated = true
                        mutableState.value = ReportsUiState.SessionExpired(month)
                        onSessionExpired()
                    } else mutableState.value = failure(month, ReportsError.Unavailable, previous)
                    is FinancialReportResult.InvalidData -> mutableState.value = failure(month, ReportsError.InvalidData, previous)
                    // Never keep earlier figures under a currency label that can no longer be confirmed.
                    FinancialReportResult.CurrencyUnavailable -> mutableState.value = ReportsUiState.Failure(month, ReportsError.CurrencyUnavailable)
                }
            } finally {
                if (token == generation) request = null
            }
        }
        request = job
        job.start()
    }

    private fun failure(month: YearMonth, error: ReportsError, previous: ReportsUiState.Ready?): ReportsUiState =
        previous?.takeIf { it.month == month }?.copy(isRefreshing = false, refreshError = error) ?: ReportsUiState.Failure(month, error)

    class Factory(
        private val loader: suspend (YearMonth) -> FinancialReportResult,
        private val currentMonth: () -> YearMonth = { YearMonth.now() },
        private val isSessionCurrent: () -> Boolean = { true },
        private val onSessionExpired: () -> Unit = {},
        private val snapshots: (suspend () -> FinanceResult<List<MonthlySnapshot>>)? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ReportsViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return ReportsViewModel(loader, currentMonth, isSessionCurrent = isSessionCurrent, onSessionExpired = onSessionExpired,
                snapshotLoader = snapshots) as T
        }
    }
}
