package com.elect3.mobdev.personalfinance.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.reports.MonthlyReport
import com.elect3.mobdev.personalfinance.domain.repository.BudgetStatus
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupDivider
import com.elect3.mobdev.personalfinance.ui.components.OwliSectionHeader
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.util.Locale

/** The month's budgets exactly as the server computed them (spent, limit, status); never recomputed here. */
@Composable
internal fun ReportsBudgets(report: MonthlyReport) {
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    ReportsCard {
        OwliSectionHeader(stringResource(R.string.reports_budgets_title))
        report.budgets.forEachIndexed { index, budget ->
            if (index > 0) OwliGroupDivider()
            val status = stringResource(when (budget.status) {
                BudgetStatus.OK -> R.string.reports_budget_status_ok
                BudgetStatus.Warning -> R.string.reports_budget_status_warning
                BudgetStatus.Exceeded -> R.string.reports_budget_status_exceeded
            })
            val spent = formatReportAmount(budget.spentAmount, budget.baseCurrency)
            val limit = formatReportAmount(budget.limitAmount, budget.baseCurrency)
            val description = stringResource(R.string.reports_budget_row_description, budget.categoryName, spent, limit, status)
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(budget.categoryName, style = typography.bodyStrong)
                    Text(stringResource(R.string.reports_budget_row, spent, limit), style = typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(status, style = typography.label, color = when (budget.status) {
                    BudgetStatus.OK -> owli.income
                    BudgetStatus.Warning -> MaterialTheme.colorScheme.onSurface
                    BudgetStatus.Exceeded -> owli.expense
                })
            }
        }
    }
}

/** Stored monthly snapshots (C4): saved once at the start of the next month and never changed. */
@Composable
internal fun ReportsSnapshots(state: SnapshotsUiState, locale: Locale, onRetry: () -> Unit) {
    val typography = LocalOwliTypography.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ReportsCard {
        OwliSectionHeader(stringResource(R.string.reports_snapshots_title))
        Text(stringResource(R.string.reports_snapshots_note), style = typography.caption, color = muted)
        when (state) {
            SnapshotsUiState.Hidden -> Unit
            SnapshotsUiState.Loading -> Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.reports_snapshots_loading), style = typography.body)
            }
            is SnapshotsUiState.Failure -> {
                Text(stringResource(if (state.error == ReportsError.InvalidData) R.string.reports_snapshots_invalid else R.string.reports_snapshots_error),
                    style = typography.body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reports_retry)) }
            }
            is SnapshotsUiState.Ready -> if (state.items.isEmpty()) {
                Text(stringResource(R.string.reports_snapshots_empty), style = typography.body)
            } else state.items.forEachIndexed { index, s ->
                if (index > 0) OwliGroupDivider()
                val month = reportMonthLabel(s.month, locale)
                val income = formatReportAmount(s.totalIncome, s.currency)
                val expense = formatReportAmount(s.totalExpense, s.currency)
                val net = formatSignedReportAmount(s.netCashFlow, s.currency)
                val savings = formatReportAmount(s.savingsAllocated, s.currency)
                val description = stringResource(R.string.reports_snapshot_description, month, income, expense, net, savings)
                Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(month, style = typography.bodyStrong, modifier = Modifier.weight(1f))
                        Text(net, style = typography.amountRow)
                    }
                    Text("${stringResource(R.string.reports_income)} $income · ${stringResource(R.string.reports_expenses)} $expense · " +
                        "${stringResource(R.string.reports_snapshot_savings)} $savings", style = typography.caption, color = muted)
                }
            }
        }
    }
}
