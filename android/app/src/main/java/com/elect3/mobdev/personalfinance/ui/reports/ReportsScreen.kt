package com.elect3.mobdev.personalfinance.ui.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReport
import com.elect3.mobdev.personalfinance.domain.reports.MonthlyReport
import com.elect3.mobdev.personalfinance.domain.reports.ReportPeriod
import com.elect3.mobdev.personalfinance.ui.components.LocalOwliShellActions
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupCard
import com.elect3.mobdev.personalfinance.ui.components.OwliIconTile
import com.elect3.mobdev.personalfinance.ui.components.OwliMidnightPanel
import com.elect3.mobdev.personalfinance.ui.components.OwliPanelFooter
import com.elect3.mobdev.personalfinance.ui.components.OwliRibbon
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale

/** Collects the ViewModel; quick-add comes from the app shell when the owner allows adding. */
@Composable
fun ReportsRoute(viewModel: ReportsViewModel, demo: Boolean, currentMonth: () -> YearMonth, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val shell = LocalOwliShellActions.current
    ReportsScreen(
        state = state, currentMonth = currentMonth(), demo = demo,
        onPrevious = viewModel::previousMonth, onNext = viewModel::nextMonth, onSelectMonth = viewModel::selectMonth,
        onRetry = viewModel::retry, onRefresh = viewModel::refresh,
        onAddTransaction = shell.addTransaction?.let { add -> { add(null) } },
        modifier = modifier,
        snapshots = snapshots, onRetrySnapshots = viewModel::retrySnapshots,
    )
}

/** Stateless Financial Reports & Insights screen. Creates no repositories or ViewModels. */
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    currentMonth: YearMonth,
    demo: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectMonth: (YearMonth) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onAddTransaction: (() -> Unit)? = null,
    snapshots: SnapshotsUiState = SnapshotsUiState.Hidden,
    onRetrySnapshots: () -> Unit = {},
) {
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val monthText = reportMonthLabel(state.month, locale)
    val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
    Surface(modifier.fillMaxSize(), color = scheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "header") {
                Column(width, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.reports_title), style = typography.screenTitle, color = scheme.onBackground,
                                modifier = Modifier.semantics { heading() })
                        }
                        if (state is ReportsUiState.Ready) ReportsPill(stringResource(R.string.reports_refresh), onRefresh, enabled = !state.isRefreshing)
                    }
                    if (demo) OwliRibbon(stringResource(R.string.reports_ribbon_demo), stringResource(R.string.reports_ribbon_demo_detail))
                    if (state !is ReportsUiState.SessionExpired) {
                        ReportsPeriodSelector(state.month, currentMonth, locale, onPrevious, onNext,
                            onThisMonth = { onSelectMonth(currentMonth) },
                            enabled = state !is ReportsUiState.Loading)
                    }
                }
            }
            when (state) {
                is ReportsUiState.Loading -> item(key = "loading") {
                    Box(width) { ReportsLoading(stringResource(R.string.reports_loading, monthText)) }
                }
                is ReportsUiState.SessionExpired -> item(key = "session") {
                    Box(width) { ReportsMessage(stringResource(R.string.reports_session_title), stringResource(R.string.auth_session_expired)) }
                }
                is ReportsUiState.Failure -> item(key = "failure") {
                    val (title, message) = errorCopy(state.error)
                    Box(width) {
                        ReportsMessage(stringResource(title), stringResource(message)) {
                            ReportsButton(stringResource(R.string.reports_retry), onRetry)
                        }
                    }
                }
                is ReportsUiState.Ready -> {
                    val report = state.report
                    if (state.isRefreshing) item(key = "refreshing") {
                        Column(width, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.reports_refreshing, monthText), style = typography.caption,
                                color = scheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    if (state.refreshError != null) item(key = "refresh-error") {
                        Box(width) {
                            ReportsMessage(stringResource(errorCopy(state.refreshError).first),
                                stringResource(R.string.reports_refresh_failed, monthText)) {
                                ReportsButton(stringResource(R.string.reports_retry), onRefresh)
                            }
                        }
                    }
                    if (report.windowIsEmpty) item(key = "empty") {
                        val trend = report.trend
                        Box(width) {
                            ReportsMessage(stringResource(R.string.reports_empty_title),
                                stringResource(R.string.reports_empty_message, reportMonthLabel(trend.first().month, locale),
                                    reportMonthLabel(trend.last().month, locale)), showOwl = true) {
                                onAddTransaction?.let { ReportsButton(stringResource(R.string.reports_add_transaction), it) }
                            }
                        }
                    } else {
                        item(key = "overview") { Box(width) { ReportsOverviewPanel(report.selected, locale) } }
                        item(key = "breakdown") { Box(width.padding(top = 6.dp)) { ReportsBreakdown(report.selected, locale) } }
                        if (report.selected.budgets.isNotEmpty()) item(key = "budgets") { Box(width.padding(top = 6.dp)) { ReportsBudgets(report.selected) } }
                        item(key = "trend") { Box(width.padding(top = 6.dp)) { ReportsTrend(report, locale, onSelectMonth) } }
                    }
                    item(key = "source") {
                        Text(if (demo) stringResource(R.string.reports_source_demo) else stringResource(R.string.reports_source_account, report.currency),
                            style = typography.caption, color = scheme.onSurfaceVariant, modifier = width.padding(top = 4.dp))
                    }
                }
            }
            if (state !is ReportsUiState.SessionExpired && snapshots != SnapshotsUiState.Hidden) item(key = "snapshots") {
                Box(width.padding(top = 6.dp)) { ReportsSnapshots(snapshots, locale, onRetrySnapshots) }
            }
        }
    }
}

private fun errorCopy(error: ReportsError): Pair<Int, Int> = when (error) {
    ReportsError.Unavailable -> R.string.reports_unavailable_title to R.string.reports_unavailable_message
    ReportsError.InvalidData -> R.string.reports_invalid_title to R.string.reports_invalid_message
    ReportsError.CurrencyUnavailable -> R.string.reports_currency_title to R.string.reports_currency_message
}

/** ‹ November 2026 › with 48 dp targets; "next" stops at the current month, never a future one. */
@Composable
private fun ReportsPeriodSelector(
    month: YearMonth, current: YearMonth, locale: Locale,
    onPrevious: () -> Unit, onNext: () -> Unit, onThisMonth: () -> Unit, enabled: Boolean,
) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val canBack = ReportPeriod.canGoBack(month)
    val canForward = ReportPeriod.canGoForward(month, current)
    val label = reportMonthLabel(month, locale)
    OwliGroupCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val prevLabel = stringResource(R.string.reports_previous_month, reportMonthLabel(month.minusMonths(1), locale))
            IconButton(onClick = onPrevious, enabled = canBack, modifier = Modifier.size(48.dp).semantics { contentDescription = prevLabel }) {
                Icon(painterResource(R.drawable.ic_owli_chevron_left), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.reports_period_label).uppercase(locale), style = typography.overline, color = scheme.onSurfaceVariant)
                Text(label, style = typography.bodyStrong, textAlign = TextAlign.Center)
            }
            val nextLabel = stringResource(R.string.reports_next_month, reportMonthLabel(month.plusMonths(1), locale))
            IconButton(onClick = onNext, enabled = canForward, modifier = Modifier.size(48.dp).semantics { contentDescription = nextLabel }) {
                Icon(painterResource(R.drawable.ic_owli_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (month != current) {
            Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                Surface(onClick = onThisMonth, enabled = enabled, shape = LocalOwliShapes.current.chip, color = owli.accentSoft,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.reports_this_month), style = typography.label, color = owli.accent)
                    }
                }
            }
        }
    }
}

/** Midnight Panel: the selected month's net balance, income and expenses. */
@Composable
private fun ReportsOverviewPanel(report: MonthlyReport, locale: Locale) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val month = reportMonthLabel(report.month, locale)
    val currency = report.currency
    val net = report.netBalance
    val stacked = LocalConfiguration.current.fontScale > 1.3f
    val description = stringResource(R.string.reports_overview_description, month, formatSignedReportAmount(net, currency),
        formatReportAmount(report.totalIncome, currency), formatReportAmount(report.totalExpenses, currency))
    OwliMidnightPanel {
        Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
            Text(stringResource(R.string.reports_overview_title).uppercase(locale), style = typography.overline, color = owli.onPanelMuted)
            Text(stringResource(R.string.reports_net_balance), style = typography.bodyStrong, color = owli.onPanel, modifier = Modifier.padding(top = 10.dp))
            Text(formatSignedReportAmount(net, currency), style = typography.heroAmount,
                color = when (net.signum()) { 1 -> owli.panelIncome; -1 -> owli.panelExpense; else -> owli.onPanel },
                modifier = Modifier.padding(top = 2.dp))
            Text(stringResource(R.string.reports_net_explanation, month), style = typography.caption, color = owli.onPanelMuted)
            val figure: @Composable (Modifier, Boolean) -> Unit = { m, income ->
                Column(m) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(if (income) R.drawable.ic_owli_arrow_up else R.drawable.ic_owli_arrow_down), null,
                            tint = if (income) owli.panelIncome else owli.panelExpense, modifier = Modifier.size(12.dp))
                        Text(stringResource(if (income) R.string.reports_income else R.string.reports_expenses),
                            style = typography.micro, color = owli.onPanelMuted)
                    }
                    Text(formatReportAmount(if (income) report.totalIncome else report.totalExpenses, currency),
                        style = typography.amountMedium, color = owli.onPanel)
                }
            }
            if (stacked) Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                figure(Modifier.fillMaxWidth(), true); figure(Modifier.fillMaxWidth(), false)
            } else Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                figure(Modifier.weight(1f), true); figure(Modifier.weight(1f), false)
            }
        }
        OwliPanelFooter {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val count = NumberFormat.getIntegerInstance(locale).format(report.transactionCount)
                Text(if (report.isEmpty) stringResource(R.string.reports_month_no_activity, month)
                    else pluralStringResource(R.plurals.reports_transaction_count, report.transactionCount, count, month),
                    style = typography.caption, color = owli.onPanelMuted)
                report.savingsContributed?.let { savings ->
                    Text(stringResource(R.string.reports_savings_description, month, formatReportAmount(savings, currency)),
                        style = typography.caption, color = owli.onPanelMuted)
                }
                if (report.foreignCurrencyCount > 0) Text(
                    pluralStringResource(R.plurals.reports_foreign_note, report.foreignCurrencyCount, report.foreignCurrencyCount,
                        report.foreignCurrencies.joinToString(", "), currency),
                    style = typography.caption, color = owli.onPanelMuted)
            }
        }
    }
}

@Composable
internal fun ReportsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    OwliGroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
private fun ReportsLoading(label: String) {
    ReportsCard {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            Text(label, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReportsMessage(title: String, message: String, showOwl: Boolean = false, action: @Composable () -> Unit = {}) {
    ReportsCard {
        if (showOwl) OwliIconTile(R.drawable.ic_owli_mark, size = 48.dp, iconSize = 28.dp)
        Text(title, style = LocalOwliTypography.current.sectionTitle, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() })
        Text(message, style = LocalOwliTypography.current.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        action()
    }
}

@Composable
private fun ReportsButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = LocalOwliShapes.current.button, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, style = LocalOwliTypography.current.bodyStrong)
    }
}

@Composable
private fun ReportsPill(label: String, onClick: () -> Unit, enabled: Boolean) {
    Surface(onClick = onClick, enabled = enabled, shape = LocalOwliShapes.current.chip, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalOwliColors.current.hairline), modifier = Modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_owli_refresh), contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, style = LocalOwliTypography.current.label)
        }
    }
}
