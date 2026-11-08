package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.transactions.formatPhpAmount
import java.math.BigDecimal

@Composable
internal fun DashboardHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

/** Complete currency text may wrap at narrow widths; never ellipsize or constrain its height. */
@Composable
internal fun DashboardAmount(amount: BigDecimal, signed: Boolean = false, prominent: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
    val value = formatPhpAmount(if (signed) amount.abs() else amount)
    val text = when {
        !signed || amount.signum() == 0 -> value
        amount.signum() > 0 -> stringResource(R.string.transaction_income_amount, value)
        else -> stringResource(R.string.transaction_expense_amount, value)
    }
    Text(text, color = color, style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun DashboardMetric(label: String, amount: BigDecimal, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        DashboardAmount(amount, color = color)
    }
}

@Composable
internal fun DashboardIncomeExpenses(income: BigDecimal, expenses: BigDecimal) {
    val incomeLabel = stringResource(R.string.dashboard_income)
    val expenseLabel = stringResource(R.string.dashboard_expenses)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val amountStyle = MaterialTheme.typography.titleLarge
    val labelStyle = MaterialTheme.typography.bodyMedium
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        fun width(text: String, style: TextStyle) = measurer.measure(text, style, softWrap = false).size.width
        val incomeWidth = maxOf(width(formatPhpAmount(income), amountStyle), width(incomeLabel, labelStyle))
        val expenseWidth = maxOf(width(formatPhpAmount(expenses), amountStyle), width(expenseLabel, labelStyle))
        val gap = with(density) { 16.dp.roundToPx() }
        // Equal columns each need enough room for their full label and amount.
        if (maxOf(incomeWidth, expenseWidth).toLong() * 2 + gap <= constraints.maxWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DashboardMetric(incomeLabel, income, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                DashboardMetric(expenseLabel, expenses, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DashboardMetric(incomeLabel, income, MaterialTheme.colorScheme.secondary)
            DashboardMetric(expenseLabel, expenses, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun DashboardAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
internal fun DashboardLoading(label: String) {
    Column(Modifier.padding(vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
internal fun DashboardMessage(title: String, message: String, action: @Composable () -> Unit = {}) {
    DashboardCard {
        DashboardHeading(title)
        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        action()
    }
}

@Composable
internal fun dashboardErrorMessage(error: DashboardError): String = stringResource(
    if (error == DashboardError.InvalidSnapshot) R.string.dashboard_invalid_data else R.string.dashboard_unavailable_message,
)
