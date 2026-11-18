package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.DashboardSummary
import com.elect3.mobdev.personalfinance.ui.components.OwliEyeRing
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupCard
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupDivider
import com.elect3.mobdev.personalfinance.ui.components.OwliIconTile
import com.elect3.mobdev.personalfinance.ui.components.OwliMidnightPanel
import com.elect3.mobdev.personalfinance.ui.components.OwliPanelFooter
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal
import java.math.RoundingMode
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
    signColor: Color? = null,
) {
    val value = formatPhpAmount(if (signed) amount.abs() else amount)
    val text = when {
        !signed || amount.signum() == 0 -> value
        amount.signum() > 0 -> stringResource(R.string.transaction_income_amount, value)
        else -> stringResource(R.string.transaction_expense_amount, value)
    }
    // The leading sign may be tinted; the accessible text is unchanged.
    val shown: AnnotatedString = if (signColor != null && signed && amount.signum() != 0 && text.isNotEmpty() && !text[0].isLetterOrDigit()) {
        buildAnnotatedString { withStyle(SpanStyle(color = signColor)) { append(text[0]) }; append(text.substring(1)) }
    } else AnnotatedString(text)
    Text(shown, color = color, style = style, modifier = Modifier.fillMaxWidth())
}

/** Which data the Dashboard shows. Selects honest wording and the data label only; layout is identical. */
enum class DashboardSource { Demo, Account }

/** String resource IDs (R.string) for one data source. */
internal class DashboardCopy(
    val dataLabel: Int, val loading: Int, val refreshing: Int,
    val emptyTitle: Int, val emptyMessage: Int, val unavailable: Int,
    val invalid: Int, val sessionTitle: Int, val sessionMessage: Int,
    val netExplanation: Int, val allTimeDetail: Int, val monthExplanation: Int,
    val ribbonDetail: Int,
)

private val DemoCopy = DashboardCopy(
    R.string.dashboard_demo_data, R.string.dashboard_loading, R.string.dashboard_refreshing,
    R.string.dashboard_empty_title, R.string.dashboard_empty_message, R.string.dashboard_unavailable_message,
    R.string.dashboard_invalid_data, R.string.dashboard_session_title, R.string.dashboard_session_message,
    R.string.dashboard_net_explanation, R.string.dashboard_period_all_time_detail, R.string.dashboard_net_month_explanation,
    R.string.dashboard_ribbon_demo_detail,
)

private val AccountCopy = DashboardCopy(
    R.string.dashboard_account_data, R.string.dashboard_account_loading, R.string.dashboard_account_refreshing,
    R.string.dashboard_account_empty_title, R.string.dashboard_account_empty_message, R.string.dashboard_account_unavailable_message,
    R.string.dashboard_account_invalid_data, R.string.transactions_account_session_title, R.string.auth_session_expired,
    R.string.dashboard_account_net_explanation, R.string.dashboard_account_period_all_time_detail,
    R.string.dashboard_account_net_month_explanation, R.string.dashboard_ribbon_account_detail,
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

/**
 * Share of total flow (income + expenses) as a display fraction and a whole-percent label.
 * Exact BigDecimal arithmetic; rounding affects display only. Zero total yields 0 for both.
 */
internal data class FlowShare(val fraction: Float, val label: String)

internal fun flowShares(income: BigDecimal, expenses: BigDecimal): Pair<FlowShare, FlowShare> {
    val total = income.add(expenses)
    if (total.signum() <= 0) return FlowShare(0f, "0%") to FlowShare(0f, "0%")
    fun share(part: BigDecimal): FlowShare {
        val ratio = part.divide(total, 6, RoundingMode.HALF_UP)
        val pct = ratio.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        return FlowShare(ratio.toFloat(), "${pct.toPlainString()}%")
    }
    return share(income) to share(expenses)
}

/** Midnight Panel: net transaction flow for the selected period, with owl-eye share rings. */
@Composable
internal fun DashboardNetFlowPanel(
    summary: DashboardSummary,
    allTime: Boolean,
    copy: DashboardCopy,
    onPeriodChange: (Boolean) -> Unit,
) {
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    val month = reportingMonthText(summary.reportingMonth)
    val net = if (allTime) summary.netTransactionFlow else summary.monthlyNetTransactionFlow
    val income = if (allTime) summary.totalIncome else summary.monthlyIncome
    val expenses = if (allTime) summary.totalExpenses else summary.monthlyExpenses
    val (incomeShare, expenseShare) = flowShares(income, expenses)
    val stacked = LocalConfiguration.current.fontScale > 1.3f
    OwliMidnightPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_net_flow), style = typography.overline, color = owli.onPanelMuted,
                modifier = Modifier.weight(1f))
        }
        Box(Modifier.padding(top = 10.dp)) { DashboardPeriodTabs(allTime, onPeriodChange) }
        Box(Modifier.padding(top = 12.dp)) {
            DashboardAmount(net, signed = true, style = typography.heroAmount, color = owli.onPanel,
                signColor = if (net.signum() > 0) owli.panelIncome else owli.panelExpense)
        }
        Text(if (allTime) stringResource(copy.allTimeDetail) else month,
            style = typography.bodyStrong, color = owli.onPanel, modifier = Modifier.padding(top = 4.dp))
        Text(if (allTime) stringResource(copy.netExplanation) else stringResource(copy.monthExplanation, month),
            style = typography.caption, color = owli.onPanelMuted)
        val ringsDescription = stringResource(R.string.dashboard_flow_share_description,
            formatPhpAmount(income), incomeShare.label, formatPhpAmount(expenses), expenseShare.label)
        val eye: @Composable (Modifier, Boolean) -> Unit = { m, isIncome ->
            DashboardEye(m, isIncome, if (isIncome) income else expenses, if (isIncome) incomeShare else expenseShare, stacked)
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = ringsDescription },
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                eye(Modifier.fillMaxWidth(), true)
                eye(Modifier.fillMaxWidth(), false)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = ringsDescription },
                verticalAlignment = Alignment.Top) {
                eye(Modifier.weight(1f), true)
                Icon(painterResource(R.drawable.ic_owli_arrow_down), contentDescription = null, tint = owli.onPanelMuted.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 24.dp).size(16.dp))
                eye(Modifier.weight(1f), false)
            }
        }
        OwliPanelFooter { DashboardPanelFooter(summary, allTime, month) }
    }
}

@Composable
private fun DashboardEye(modifier: Modifier, isIncome: Boolean, amount: BigDecimal, share: FlowShare, stacked: Boolean) {
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    val color = if (isIncome) owli.panelIncome else owli.panelExpense
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(if (isIncome) R.drawable.ic_owli_arrow_up else R.drawable.ic_owli_arrow_down), contentDescription = null,
                tint = color, modifier = Modifier.size(12.dp))
            Text(stringResource(if (isIncome) R.string.dashboard_income else R.string.dashboard_expenses),
                style = typography.micro, color = owli.onPanelMuted)
        }
    }
    val value: @Composable () -> Unit = {
        Text(formatPhpAmount(amount), style = typography.amountMedium, color = owli.onPanel,
            textAlign = if (stacked) TextAlign.Start else TextAlign.Center)
    }
    if (stacked) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OwliEyeRing(share.fraction, color, share.label)
            Column(Modifier.weight(1f)) { label(); value() }
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            OwliEyeRing(share.fraction, color, share.label)
            Box(Modifier.padding(top = 7.dp)) { label() }
            value()
        }
    }
}

/** Two mutually exclusive periods exposed as a radio group, styled as Owlight panel tabs. */
@Composable
private fun DashboardPeriodTabs(allTime: Boolean, onSelect: (Boolean) -> Unit) {
    val owli = LocalOwliColors.current
    val shapes = LocalOwliShapes.current
    val periodLabel = stringResource(R.string.dashboard_period_label)
    Row(
        Modifier.clip(shapes.chip).background(owli.panelChip).padding(3.dp).selectableGroup()
            .semantics { contentDescription = periodLabel },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to R.string.dashboard_period_month, true to R.string.dashboard_period_all_time).forEach { (option, label) ->
            val selected = option == allTime
            Box(
                Modifier.clip(shapes.chip).background(if (selected) owli.onPanel else Color.Transparent)
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = LocalOwliTypography.current.label,
                    color = if (selected) Color(0xFF2A1659) else owli.onPanelMuted)
            }
        }
    }
}

/** Quick actions under the panel; each is shown only when its action exists. */
@Composable
internal fun DashboardQuickActions(onAddExpense: (() -> Unit)?, onAddIncome: (() -> Unit)?, onOpenFilter: (() -> Unit)?) {
    val actions = listOfNotNull(
        onAddExpense?.let { Triple(R.drawable.ic_owli_minus, R.string.dashboard_quick_expense to R.string.dashboard_quick_expense_action, it) },
        onAddIncome?.let { Triple(R.drawable.ic_owli_plus, R.string.dashboard_quick_income to R.string.dashboard_quick_income_action, it) },
        onOpenFilter?.let { Triple(R.drawable.ic_owli_filter, R.string.dashboard_quick_filter to R.string.dashboard_quick_filter_action, it) },
    )
    if (actions.isEmpty()) return
    val stacked = LocalConfiguration.current.fontScale > 1.3f
    val button: @Composable (Modifier, Triple<Int, Pair<Int, Int>, () -> Unit>) -> Unit = { m, (icon, labels, onClick) ->
        val description = stringResource(labels.second)
        Surface(
            onClick = onClick,
            // One accessible name per action ("Add income"); the short visual label is not exposed separately.
            modifier = m.heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = description; role = Role.Button },
            shape = LocalOwliShapes.current.quickAction,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, LocalOwliColors.current.hairline),
        ) {
            // Side by side when the complete label fits; otherwise icon above label. Never truncated.
            val label = stringResource(labels.first)
            val labelStyle = LocalOwliTypography.current.label.copy(fontSize = 13.sp)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                val needed = measurer.measure(label, labelStyle, softWrap = false).size.width + with(density) { (22 + 6).dp.roundToPx() }
                if (needed <= constraints.maxWidth) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OwliIconTile(icon, size = 22.dp, iconSize = 14.dp)
                        Text(label, style = labelStyle)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OwliIconTile(icon, size = 22.dp, iconSize = 14.dp)
                        Text(label, style = labelStyle, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions.forEach { button(Modifier.fillMaxWidth(), it) } }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions.forEach { button(Modifier.weight(1f), it) } }
}

/** Overview group: entries for the selected period and the reporting-month top category (unchanged rules). */
@Composable
internal fun DashboardOverview(summary: DashboardSummary, allTime: Boolean) {
    val typography = LocalOwliTypography.current
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
    OwliGroupCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OwliIconTile(R.drawable.ic_owli_transactions_list)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dashboard_entries), style = typography.bodyStrong)
                Text(if (allTime) stringResource(R.string.dashboard_entries_all_time) else stringResource(R.string.dashboard_entries_month, month),
                    style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(NumberFormat.getIntegerInstance(locale).format(entries), style = typography.amountMedium)
        }
        OwliGroupDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OwliIconTile(R.drawable.ic_owli_eye_glyph, tint = LocalOwliColors.current.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.dashboard_top_category), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(topValue, style = typography.bodyStrong)
                Text(topDetail, style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Share of a period's total expenses as a one-decimal percent label (half-up), e.g. "42.9%".
 * The denominator is always the full period total, never a sum of the displayed prefix.
 */
internal fun categoryShareLabel(categoryTotal: BigDecimal, periodExpenses: BigDecimal): String {
    if (periodExpenses.signum() <= 0) return "0.0%"
    val pct = categoryTotal.multiply(BigDecimal(100)).divide(periodExpenses, 1, RoundingMode.HALF_UP)
    return "${pct.toPlainString()}%"
}

/** Number of categories shown in "Where it went". */
internal const val WHERE_IT_WENT_LIMIT = 5

/**
 * Panel footer: entry count, completeness wording (only "all recorded" for the verified complete snapshot),
 * the rounding note for the rings, and the Insights shortcut, which stays unavailable until Insights exists.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DashboardPanelFooter(summary: DashboardSummary, allTime: Boolean, month: String) {
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    val locale = LocalConfiguration.current.locales[0]
    val count = NumberFormat.getIntegerInstance(locale).format(if (allTime) summary.recordCount else summary.monthlyRecordCount)
    val text = if (allTime) stringResource(R.string.dashboard_footer_all_time, count)
        else stringResource(R.string.dashboard_footer_month, count, month)
    val insightsDescription = stringResource(R.string.dashboard_footer_insights_unavailable)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = typography.caption, color = owli.onPanelMuted, modifier = Modifier.padding(end = 8.dp))
        // Visibly unavailable: dimmed, labelled "Soon", announced as disabled, no click action.
        Row(
            Modifier.clearAndSetSemantics { contentDescription = insightsDescription; disabled() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.navigation_insights), style = typography.label, color = owli.onPanel.copy(alpha = 0.5f))
            Icon(painterResource(R.drawable.ic_owli_chevron_right), contentDescription = null,
                tint = owli.onPanel.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.navigation_soon), style = typography.micro, color = owli.onPanelMuted)
        }
    }
}

/**
 * "Where it went": up to five expense categories for the selected period, ranked by exact total,
 * from the same verified complete snapshot as the rest of the Dashboard. Percentages use the period's
 * total expenses as the denominator; every category tied at the highest total gets the TOP badge.
 */
@Composable
internal fun DashboardWhereItWent(summary: DashboardSummary, allTime: Boolean, source: DashboardSource) {
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
    val month = reportingMonthText(summary.reportingMonth)
    val ranked = if (allTime) summary.expenseCategoryTotals else summary.monthlyExpenseCategoryTotals
    val periodExpenses = if (allTime) summary.totalExpenses else summary.monthlyExpenses
    val periodLabel = if (allTime) stringResource(R.string.dashboard_where_period_all_time)
        else stringResource(R.string.dashboard_where_period_month, month)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.elect3.mobdev.personalfinance.ui.components.OwliSectionHeader(
            stringResource(R.string.dashboard_where_title), trailing = periodLabel)
        OwliGroupCard {
            if (ranked.isEmpty() || periodExpenses.signum() <= 0) {
                Text(if (allTime) stringResource(R.string.dashboard_where_empty_all_time)
                    else stringResource(R.string.dashboard_where_empty_month, month),
                    style = typography.body, color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp))
                return@OwliGroupCard
            }
            val shown = ranked.take(WHERE_IT_WENT_LIMIT)
            val highest = shown.first().total
            shown.forEachIndexed { index, category ->
                if (index > 0) OwliGroupDivider()
                val isTop = category.total.compareTo(highest) == 0
                val share = categoryShareLabel(category.total, periodExpenses)
                val barFraction = category.total.divide(highest, 4, RoundingMode.HALF_UP).toFloat()
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(String.format(java.util.Locale.ROOT, "%02d", index + 1),
                            style = typography.micro.copy(fontFeatureSettings = "tnum"), color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(category.categoryName, style = typography.bodyStrong, modifier = Modifier.weight(1f, fill = false))
                            if (isTop) Text(stringResource(R.string.dashboard_where_top_badge),
                                style = typography.overline.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                color = owli.accent,
                                modifier = Modifier.clip(LocalOwliShapes.current.rowTile).background(owli.accentSoft)
                                    .padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatPhpAmount(category.total), style = typography.amountRow, textAlign = TextAlign.End)
                            Text(share, style = typography.caption.copy(fontFeatureSettings = "tnum"), color = scheme.onSurfaceVariant)
                        }
                    }
                    // Proportional bar (relative to the largest shown category); decorative, values are in text.
                    Box(Modifier.padding(start = 26.dp).fillMaxWidth().height(3.dp)
                        .clip(LocalOwliShapes.current.chip).background(owli.hairline)) {
                        Box(Modifier.fillMaxWidth(barFraction).height(3.dp)
                            .background(if (isTop) owli.accent else owli.accent.copy(alpha = 0.35f)))
                    }
                }
            }
            val leaders = shown.filter { it.total.compareTo(highest) == 0 }
            val leaderShare = categoryShareLabel(highest, periodExpenses)
            val scope = if (allTime) stringResource(R.string.dashboard_where_scope_all_time)
                else stringResource(R.string.dashboard_where_scope_month, month)
            val fact = if (leaders.size == 1) stringResource(R.string.dashboard_where_fact_single, leaders.first().categoryName, leaderShare, scope)
                else stringResource(R.string.dashboard_where_fact_tie, leaders.joinToString(", ") { it.categoryName }, leaderShare, scope)
            val sourceNote = stringResource(if (source == DashboardSource.Demo) R.string.dashboard_where_source_demo
                else R.string.dashboard_where_source_account)
            OwliGroupDivider()
            Row(Modifier.fillMaxWidth().background(owli.accentSoft.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_owli_eye_glyph), contentDescription = null, tint = owli.accent,
                    modifier = Modifier.padding(top = 1.dp).size(18.dp))
                Column {
                    Text(fact, style = typography.caption, color = scheme.onSurface)
                    val more = ranked.size - shown.size
                    Text(if (more > 0) stringResource(R.string.dashboard_where_more, sourceNote, more) else sourceNote,
                        style = typography.caption, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    OwliGroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
internal fun DashboardAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = LocalOwliShapes.current.button,
        modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, style = LocalOwliTypography.current.bodyStrong)
    }
}

/** Owlight pill: hairline-bordered, used for Refresh in the header. */
@Composable
internal fun DashboardPillAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick, enabled = enabled,
        shape = LocalOwliShapes.current.chip,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalOwliColors.current.hairline),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_owli_refresh), contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, style = LocalOwliTypography.current.label)
        }
    }
}

@Composable
internal fun DashboardLoading(label: String) {
    DashboardCard {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            Text(label, style = LocalOwliTypography.current.bodyStrong, modifier = Modifier.weight(1f))
        }
    }
}

/** Status card; the owl appears only for calm, non-error moments such as an empty dashboard. */
@Composable
internal fun DashboardMessage(title: String, message: String, showOwl: Boolean = false, action: @Composable () -> Unit = {}) {
    DashboardCard {
        if (showOwl) OwliIconTile(R.drawable.ic_owli_mark, size = 48.dp, iconSize = 28.dp)
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
