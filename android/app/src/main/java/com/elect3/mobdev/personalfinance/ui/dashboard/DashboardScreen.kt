package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.components.OwliSummaryGrid
import com.elect3.mobdev.personalfinance.ui.components.OwliTopBar
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionRow

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, modifier: Modifier = Modifier, source: DashboardSource = DashboardSource.Demo) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(state, viewModel::retry, viewModel::refresh, modifier, source)
}

/**
 * Owner supplies theme and actions; this screen creates no repositories or ViewModels.
 * Demo: every figure is sample data, so the sample-data chip is shown in every state.
 * Account: figures come from the signed-in account and are labelled as such.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    source: DashboardSource = DashboardSource.Demo,
) {
    val copy = dashboardCopy(source)
    val typography = LocalOwliTypography.current
    // Period choice survives configuration changes. Defaults to all recorded transactions,
    // preserving the existing launch summary; the month option is always explicitly labelled.
    var allTime by rememberSaveable { mutableStateOf(true) }
    // Readable line length on wide windows; full width on phones.
    val contentWidth = Modifier.widthIn(max = 640.dp).fillMaxWidth()
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "header") {
                Column(contentWidth, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OwliTopBar(dataLabel = stringResource(copy.dataLabel))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DashboardHeading(stringResource(R.string.dashboard_title), typography.screenTitle)
                        Text(stringResource(R.string.app_tagline), style = typography.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state is DashboardUiState.Content) DashboardAction(stringResource(R.string.dashboard_refresh), onRefresh, !state.isRefreshing)
                }
            }
            when (state) {
                DashboardUiState.InitialLoading -> item { Box(contentWidth) { DashboardLoading(stringResource(copy.loading)) } }
                DashboardUiState.Empty -> item {
                    Box(contentWidth) {
                        DashboardMessage(stringResource(copy.emptyTitle), stringResource(copy.emptyMessage), showOwl = true) {
                            DashboardAction(stringResource(R.string.dashboard_refresh), onRefresh)
                        }
                    }
                }
                is DashboardUiState.InitialFailure -> item {
                    Box(contentWidth) {
                        DashboardMessage(stringResource(R.string.dashboard_unavailable_title), dashboardErrorMessage(state.error, copy)) {
                            DashboardAction(stringResource(R.string.dashboard_retry), onRetry)
                        }
                    }
                }
                DashboardUiState.SessionExpired -> item {
                    Box(contentWidth) { DashboardMessage(stringResource(copy.sessionTitle), stringResource(copy.sessionMessage)) }
                }
                is DashboardUiState.Content -> {
                    if (state.isRefreshing) item { Box(contentWidth) { DashboardLoading(stringResource(copy.refreshing)) } }
                    state.refreshError?.let { error -> item {
                        Box(contentWidth) {
                            DashboardMessage(stringResource(R.string.dashboard_refresh_failed),
                                stringResource(R.string.dashboard_retained_summary, dashboardErrorMessage(error, copy))) {
                                DashboardAction(stringResource(R.string.dashboard_retry_refresh), onRefresh)
                            }
                        }
                    } }
                    item(key = "net") { Box(contentWidth) { DashboardNetFlowHero(state.summary, allTime, copy) { allTime = it } } }
                    item(key = "summary") { OwliSummaryGrid(dashboardSummaryItems(state.summary, allTime), contentWidth) }
                    item(key = "recent") { Box(contentWidth.padding(top = 4.dp)) { DashboardHeading(stringResource(R.string.dashboard_recent_transactions)) } }
                    items(state.summary.recentTransactions, key = { "transaction-${it.transactionId}" }) { TransactionRow(it, contentWidth) }
                }
            }
        }
    }
}
