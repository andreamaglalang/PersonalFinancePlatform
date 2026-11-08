package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionRow
import java.time.format.DateTimeFormatter

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(state, viewModel::retry, viewModel::refresh, modifier)
}

/** Owner supplies theme and actions; this screen creates no repositories or ViewModels. */
@Composable
fun DashboardScreen(state: DashboardUiState, onRetry: () -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary)
                    DashboardHeading(stringResource(R.string.dashboard_title))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(stringResource(R.string.dashboard_demo_data), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    if (state is DashboardUiState.Content) DashboardAction(stringResource(R.string.dashboard_refresh), onRefresh, !state.isRefreshing)
                }
            }
            when (state) {
                DashboardUiState.InitialLoading -> item { DashboardLoading(stringResource(R.string.dashboard_loading)) }
                DashboardUiState.Empty -> item {
                    DashboardMessage(stringResource(R.string.dashboard_empty_title), stringResource(R.string.dashboard_empty_message)) {
                        DashboardAction(stringResource(R.string.dashboard_refresh), onRefresh)
                    }
                }
                is DashboardUiState.InitialFailure -> item {
                    DashboardMessage(stringResource(R.string.dashboard_unavailable_title), dashboardErrorMessage(state.error)) {
                        DashboardAction(stringResource(R.string.dashboard_retry), onRetry)
                    }
                }
                DashboardUiState.SessionExpired -> item {
                    DashboardMessage(stringResource(R.string.dashboard_session_title), stringResource(R.string.dashboard_session_message))
                }
                is DashboardUiState.Content -> {
                    if (state.isRefreshing) item { DashboardLoading(stringResource(R.string.dashboard_refreshing)) }
                    state.refreshError?.let { error -> item {
                        DashboardMessage(stringResource(R.string.dashboard_refresh_failed),
                            stringResource(R.string.dashboard_retained_summary, dashboardErrorMessage(error))) {
                            DashboardAction(stringResource(R.string.dashboard_retry_refresh), onRefresh)
                        }
                    } }
                    item(key = "net") {
                        DashboardCard {
                            DashboardHeading(stringResource(R.string.dashboard_net_flow))
                            DashboardAmount(state.summary.netTransactionFlow, signed = true, prominent = true)
                            Text(stringResource(R.string.dashboard_net_explanation), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "totals") { DashboardCard { DashboardIncomeExpenses(state.summary.totalIncome, state.summary.totalExpenses) } }
                    item(key = "monthly") {
                        val locale = LocalConfiguration.current.locales[0]
                        val month = state.summary.reportingMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                        DashboardCard {
                            DashboardHeading(stringResource(R.string.dashboard_monthly_overview))
                            Text(month, style = MaterialTheme.typography.titleMedium)
                            DashboardIncomeExpenses(state.summary.monthlyIncome, state.summary.monthlyExpenses)
                            Text(stringResource(R.string.dashboard_monthly_net), style = MaterialTheme.typography.bodyMedium)
                            DashboardAmount(state.summary.monthlyNetTransactionFlow, signed = true)
                        }
                    }
                    item(key = "recent") { DashboardHeading(stringResource(R.string.dashboard_recent_transactions)) }
                    items(state.summary.recentTransactions, key = { "transaction-${it.transactionId}" }) { TransactionRow(it) }
                }
            }
        }
    }
}
