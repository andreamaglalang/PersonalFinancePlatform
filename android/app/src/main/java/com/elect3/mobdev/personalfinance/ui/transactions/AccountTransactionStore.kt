package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.repository.TransactionRepository
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

/**
 * Financial state only. Never owns tokens, authentication, or server-session revocation.
 * Holds this login's transaction list, Dashboard and Insights; all are dropped on logout or account switch.
 */
class AccountTransactionStore(
    repository: TransactionRepository,
    isSessionCurrent: () -> Boolean,
    onSessionExpired: () -> Unit,
    /** Account base currency; anything other than PHP (or unknown) yields an explicit unsupported state. */
    accountCurrency: () -> String? = { null },
    /** Used only when the account has no transactions at all. */
    fallbackMonth: YearMonth = YearMonth.now(),
    /** Activity starting period (the app passes the current month). */
    initialFilter: com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter =
        com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter(),
    /** Server reports for Insights; null hides Insights for this account. */
    reports: com.elect3.mobdev.personalfinance.domain.reports.ReportRepository? = null,
) : AutoCloseable {
    private val store = ViewModelStore()
    private var invalidated = false
    private val factory = TransactionListViewModel.Factory(repository,
        isSessionCurrent = { !invalidated && isSessionCurrent() }, onSessionExpired = onSessionExpired, initialFilter = initialFilter)
    private val dashboardFactory = DashboardViewModel.Factory(
        LoadDashboardSummary(repository, pageSize = DASHBOARD_PAGE_SIZE, accountCurrency = accountCurrency),
        fallbackMonth, isSessionCurrent = { !invalidated && isSessionCurrent() }, onSessionExpired = onSessionExpired)
    /** Insights: the server's own monthly report (C6) and stored snapshots (C4); null without a report repository. */
    private val reportsFactory = reports?.let { server ->
        val loader = com.elect3.mobdev.personalfinance.domain.reports.LoadServerReport(server)
        com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel.Factory({ loader(it) },
            isSessionCurrent = { !invalidated && isSessionCurrent() }, onSessionExpired = onSessionExpired,
            snapshots = { server.summaries() })
    }
    private var model: TransactionListViewModel? = null
    private var dashboard: DashboardViewModel? = null
    private var reports: com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel? = null
    private var closed = false

    fun model(): TransactionListViewModel {
        check(!closed) { "Financial scope closed" }
        return model ?: ViewModelProvider(store, factory).get(TransactionListViewModel::class.java).also { model = it }
    }

    /** Created on first Dashboard visit; reads every page of this account's transactions. */
    fun dashboardModel(): DashboardViewModel {
        check(!closed) { "Financial scope closed" }
        return dashboard ?: ViewModelProvider(store, dashboardFactory).get(DashboardViewModel::class.java).also { dashboard = it }
    }

    /** Created on first Insights visit; null when no report repository was supplied. */
    fun reportsModel(): com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel? {
        check(!closed) { "Financial scope closed" }
        val factory = reportsFactory ?: return null
        return reports ?: ViewModelProvider(store, factory).get(com.elect3.mobdev.personalfinance.ui.reports.ReportsViewModel::class.java)
            .also { reports = it }
    }

    /** True when Insights can be offered for this account. */
    val supportsReports: Boolean get() = reportsFactory != null

    /** After a confirmed save: reload the Dashboard (and Insights) only if already opened. */
    fun refreshDashboard() {
        if (!closed && !invalidated) { dashboard?.refresh(); reports?.refresh() }
    }

    /** Collect independently of screen STARTED state, so background expiry drops rows too. */
    suspend fun invalidateOnSessionChange(keys: Flow<String?>, expectedKey: String) {
        keys.collect {
            if (it != expectedKey) {
                invalidated = true
                model?.invalidateSession()
                dashboard?.invalidateSession()
                reports?.invalidateSession()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidated = true
        model?.invalidateSession()
        dashboard?.invalidateSession()
        reports?.invalidateSession()
        store.clear()
        model = null
        dashboard = null
        reports = null
    }

    private companion object {
        /** Server maximum; fewer round trips while every page is still read and verified. */
        const val DASHBOARD_PAGE_SIZE = 100
    }
}
