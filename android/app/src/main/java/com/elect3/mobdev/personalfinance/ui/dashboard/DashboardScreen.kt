package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.components.LocalOwliShellActions
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupCard
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupDivider
import com.elect3.mobdev.personalfinance.ui.components.OwliOwlAvatar
import com.elect3.mobdev.personalfinance.ui.components.OwliRibbon
import com.elect3.mobdev.personalfinance.ui.components.OwliSectionHeader
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionRow
import java.time.LocalTime

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, modifier: Modifier = Modifier, source: DashboardSource = DashboardSource.Demo) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shell = LocalOwliShellActions.current
    DashboardScreen(
        state, viewModel::retry, viewModel::refresh, modifier, source,
        onAddExpense = shell.addTransaction?.let { add -> { add(TransactionType.Expense) } },
        onAddIncome = shell.addTransaction?.let { add -> { add(TransactionType.Income) } },
        onOpenActivity = shell.openActivity,
    )
}

/**
 * Owlight Dashboard. Owner supplies theme and actions; this screen creates no repositories or ViewModels.
 * Demo: every figure is sample data, so the sample-data ribbon is shown in every state.
 * Account: figures come from the signed-in account and are labelled as such.
 * Quick actions appear only when the owner supplies them.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    source: DashboardSource = DashboardSource.Demo,
    onAddExpense: (() -> Unit)? = null,
    onAddIncome: (() -> Unit)? = null,
    onOpenActivity: (() -> Unit)? = null,
) {
    val copy = dashboardCopy(source)
    val typography = LocalOwliTypography.current
    // Period choice survives configuration changes. Defaults to all recorded transactions,
    // preserving the existing launch summary; the month option is always explicitly labelled.
    var allTime by rememberSaveable { mutableStateOf(true) }
    // Readable line length on wide windows; full width on phones.
    val contentWidth = Modifier.widthIn(max = 640.dp).fillMaxWidth()
    val greeting = stringResource(remember {
        val hour = LocalTime.now().hour
        when { hour < 12 -> R.string.dashboard_greeting_morning; hour < 18 -> R.string.dashboard_greeting_afternoon
            else -> R.string.dashboard_greeting_evening }
    })
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "header") {
                Column(contentWidth, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OwliOwlAvatar()
                        Column(Modifier.weight(1f)) {
                            Text(greeting, style = typography.greeting, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.dashboard_title), style = typography.screenTitle,
                                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
                        }
                        if (state is DashboardUiState.Content) DashboardPillAction(stringResource(R.string.dashboard_refresh), onRefresh, !state.isRefreshing)
                    }
                    OwliRibbon(stringResource(copy.dataLabel), stringResource(copy.ribbonDetail))
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
                    item(key = "net") { Box(contentWidth) { DashboardNetFlowPanel(state.summary, allTime, copy) { allTime = it } } }
                    if (onAddExpense != null || onAddIncome != null || onOpenActivity != null) item(key = "quick") {
                        Box(contentWidth) { DashboardQuickActions(onAddExpense, onAddIncome, onOpenActivity) }
                    }
                    item(key = "summary") {
                        Column(contentWidth.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OwliSectionHeader(stringResource(R.string.dashboard_overview))
                            DashboardOverview(state.summary, allTime)
                        }
                    }
                    item(key = "recent") {
                        Row(contentWidth.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { DashboardHeading(stringResource(R.string.dashboard_recent_transactions)) }
                            onOpenActivity?.let {
                                TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.dashboard_see_all), style = typography.label,
                                        color = LocalOwliColors.current.accent)
                                }
                            }
                        }
                    }
                    item(key = "recent-list") {
                        Box(contentWidth) {
                            OwliGroupCard {
                                state.summary.recentTransactions.forEachIndexed { index, transaction ->
                                    if (index > 0) OwliGroupDivider()
                                    TransactionRow(transaction, framed = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
