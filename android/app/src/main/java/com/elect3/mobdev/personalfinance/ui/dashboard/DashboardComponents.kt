package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.DashboardSummary
import com.elect3.mobdev.personalfinance.ui.components.OwliHeroCard
import com.elect3.mobdev.personalfinance.ui.components.OwliSummaryItem
import com.elect3.mobdev.personalfinance.ui.components.OwliSummaryRole
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun DashboardHeading(text: String, style: TextStyle = LocalOwliTypography.current.sectionTitle) {
    Text(text, style = style, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
}

/** Complete currency text may wrap at narrow widths; never ellipsize or constrain its height. */
@Composable
internal fun DashboardAmount(
    amount: BigDecimal,
    signed: Boolean = false,
    style: TextStyle = LocalOwliTypography.current.amountMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val value = formatPhpAmount(if (signed) amount.abs() else amount)
    val text = when {
        !signed || amount.signum() == 0 -> value
        amount.signum() > 0 -> stringResource(R.string.transaction_income_amount, value)
        else -> stringResource(R.string.transaction_expense_amount, value)
    }
    Text(text, color = color, style = style, modifier = Modifier.fillMaxWidth())
}

/** Which data the Dashboard shows. Selects honest wording and the data label only; layout is identical. */
enum class DashboardSource { Demo, Account }

/** String resource IDs (R.string) for one data source. */
internal class DashboardCopy(
    val dataLabel: Int, val loading: Int, val refreshing: Int,
    val emptyTitle: Int, val emptyMessage: Int, val unavailable: Int,
    val invalid: Int, val sessionTitle: Int, val sessionMessage: Int,
    val netExplanation: Int, val allTimeDetail: Int, val monthExplanation: Int,
)

private val DemoCopy = DashboardCopy(
    R.string.dashboard_demo_data, R.string.dashboard_loading, R.string.dashboard_refreshing,
    R.string.dashboard_empty_title, R.string.dashboard_empty_message, R.string.dashboard_unavailable_message,
    R.string.dashboard_invalid_data, R.string.dashboard_session_title, R.string.dashboard_session_message,
    R.string.dashboard_net_explanation, R.string.dashboard_period_all_time_detail, R.string.dashboard_net_month_explanation,
)

private val AccountCopy = DashboardCopy(
    R.string.dashboard_account_data, R.string.dashboard_account_loading, R.string.dashboard_account_refreshing,
    R.string.dashboard_account_empty_title, R.string.dashboard_account_empty_message, R.string.dashboard_account_unavailable_message,
    R.string.dashboard_account_invalid_data, R.string.transactions_account_session_title, R.string.auth_session_expired,
    R.string.dashboard_account_net_explanation, R.string.dashboard_account_period_all_time_detail,
    R.string.dashboard_account_net_month_explanation,
)

internal fun dashboardCopy(source: DashboardSource): DashboardCopy = when (source) {
    DashboardSource.Demo -> DemoCopy
    DashboardSource.Account -> AccountCopy
}

/** Month as shown everywhere on the Dashboard, e.g. "November 2026". */
@Composable
internal fun reportingMonthText(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    return month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
}

/** Net flow (income minus expenses) for the selected period. Never an account balance. */
@Composable
internal fun DashboardNetFlowHero(
    summary: DashboardSummary,
    allTime: Boolean,
    copy: DashboardCopy = DemoCopy,
    onPeriodChange: (Boolean) -> Unit,
) {
    val typography = LocalOwliTypography.current
    val owliColors = LocalOwliColors.current
    val month = reportingMonthText(summary.reportingMonth)
    val net = if (allTime) summary.netTransactionFlow else summary.monthlyNetTransactionFlow
    val amountColor = when (net.signum()) {
        1 -> owliColors.income
        -1 -> owliColors.expense
        else -> MaterialTheme.colorScheme.onSurface
    }
    OwliHeroCard {
        Text(stringResource(R.string.dashboard_net_flow), style = typography.label,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(if (allTime) stringResource(copy.allTimeDetail) else month,
            style = typography.bodyStrong)
        DashboardAmount(net, signed = true, style = typography.heroAmount, color = amountColor)
        Text(
            if (allTime) stringResource(copy.netExplanation)
            else stringResource(copy.monthExplanation, month),
            style = typography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DashboardPeriodToggle(allTime, onPeriodChange)
    }
}

/** Two mutually exclusive options exposed as a radio group. */
@Composable
private fun DashboardPeriodToggle(allTime: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shapes = LocalOwliShapes.current
    val periodLabel = stringResource(R.string.dashboard_period_label)
    Surface(shape = shapes.chip, color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(4.dp).selectableGroup().semantics { contentDescription = periodLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(false to R.string.dashboard_period_month, true to R.string.dashboard_period_all_time).forEach { (option, label) ->
                val selected = option == allTime
                Surface(
                    shape = shapes.chip,
                    color = if (selected) colors.primary else Color.Transparent,
                    contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(stringResource(label), style = LocalOwliTypography.current.bodyStrong, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Card values for the selected period; the top category always describes the reporting month. */
@Composable
internal fun dashboardSummaryItems(summary: DashboardSummary, allTime: Boolean): List<OwliSummaryItem> {
    val month = reportingMonthText(summary.reportingMonth)
    val locale = LocalConfiguration.current.locales[0]
    val entries = if (allTime) summary.recordCount else summary.monthlyRecordCount
    val top = summary.topExpenseCategories
    val topValue = if (top.isEmpty()) stringResource(R.string.dashboard_top_category_none)
        else top.joinToString(", ") { it.categoryName }
    val topDetail = when {
        top.isEmpty() -> stringResource(R.string.dashboard_top_category_none_detail, month)
        top.size == 1 -> stringResource(R.string.dashboard_top_category_amount, formatPhpAmount(top.first().total), month)
        else -> stringResource(R.string.dashboard_top_category_tie, formatPhpAmount(top.first().total), month)
    }
    return listOf(
        OwliSummaryItem(stringResource(R.string.dashboard_income),
            formatPhpAmount(if (allTime) summary.totalIncome else summary.monthlyIncome), OwliSummaryRole.Income),
        OwliSummaryItem(stringResource(R.string.dashboard_expenses),
            formatPhpAmount(if (allTime) summary.totalExpenses else summary.monthlyExpenses), OwliSummaryRole.Expense),
        OwliSummaryItem(stringResource(R.string.dashboard_entries), NumberFormat.getIntegerInstance(locale).format(entries),
            OwliSummaryRole.Entries,
            if (allTime) stringResource(R.string.dashboard_entries_all_time) else stringResource(R.string.dashboard_entries_month, month)),
        OwliSummaryItem(stringResource(R.string.dashboard_top_category), topValue, OwliSummaryRole.TopCategory, topDetail),
    )
}

@Composable
internal fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = LocalOwliShapes.current.summaryCard,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun DashboardAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = LocalOwliShapes.current.button,
        modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, style = LocalOwliTypography.current.bodyStrong)
    }
}

@Composable
internal fun DashboardLoading(label: String) {
    DashboardCard {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, strokeWidth = 3.dp)
            Text(label, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.weight(1f))
        }
    }
}

/** Status card; the owl appears only for calm, non-error moments such as an empty dashboard. */
@Composable
internal fun DashboardMessage(title: String, message: String, showOwl: Boolean = false, action: @Composable () -> Unit = {}) {
    DashboardCard {
        if (showOwl) {
            Surface(shape = LocalOwliShapes.current.brandTile, color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_owli_mark), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
        }
        DashboardHeading(title)
        Text(message, style = LocalOwliTypography.current.body, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        action()
    }
}

@Composable
internal fun dashboardErrorMessage(error: DashboardError, copy: DashboardCopy = DemoCopy): String = stringResource(
    when (error) {
        DashboardError.InvalidSnapshot -> copy.invalid
        DashboardError.UnsupportedCurrency -> R.string.dashboard_unsupported_currency
        is DashboardError.Repository -> copy.unavailable
    },
)
