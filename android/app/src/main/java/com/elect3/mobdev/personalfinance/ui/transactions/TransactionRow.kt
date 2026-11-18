package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.components.OwliTransactionDirection
import com.elect3.mobdev.personalfinance.ui.components.OwliTransactionDirectionTile
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

/** Fit the category and amount to the actual card width, with a full-width fallback. */
@Composable
fun TransactionRow(transaction: Transaction, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val date = remember(transaction.transactionDate, configuration) {
        formatTransactionDate(transaction.transactionDate)
    }
    val amount = remember(transaction.amount, transaction.currencyCode) {
        formatTransactionAmount(transaction.amount, transaction.currencyCode)
    }
    val income = transaction.type == TransactionType.Income
    val label = stringResource(if (income) R.string.transaction_income else R.string.transaction_expense)
    val signedAmount = stringResource(
        if (income) R.string.transaction_income_amount else R.string.transaction_expense_amount,
        amount,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = LocalOwliTypography.current
    val categoryStyle = typography.bodyStrong
    val amountStyle = typography.amountRow
    val metadataStyle = typography.caption
    val typeStyle = typography.micro
    val owliColors = LocalOwliColors.current
    val typeColor = if (income) owliColors.income else owliColors.expense

    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OwliTransactionDirectionTile(
                direction = if (income) OwliTransactionDirection.Income else OwliTransactionDirection.Expense,
            )
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                // Measure the text area after reserving the tile and its gap.
                // Keep the full-value fit check and large-font fallback.
                val categoryWidth = textMeasurer.measure(transaction.categoryName, categoryStyle, softWrap = false).size.width
                val amountWidth = textMeasurer.measure(signedAmount, amountStyle, softWrap = false).size.width
                val gap = with(density) { 12.dp.roundToPx() }
                val availableWidth = constraints.maxWidth
                val sideBySide = configuration.fontScale <= 1.3f &&
                    categoryWidth.toLong() + amountWidth + gap + 2 <= availableWidth
                val dateWidth = textMeasurer.measure(date, metadataStyle, softWrap = false).size.width
                val typeWidth = textMeasurer.measure(label, typeStyle, softWrap = false).size.width
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sideBySide) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top) {
                            Text(transaction.categoryName, style = categoryStyle, modifier = Modifier.weight(1f))
                            Text(signedAmount, style = amountStyle, textAlign = TextAlign.End, color = typeColor)
                        }
                    } else {
                        Text(transaction.categoryName, style = categoryStyle)
                        Text(signedAmount, style = amountStyle, color = typeColor,
                            modifier = Modifier.fillMaxWidth())
                    }
                    transaction.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = typography.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (dateWidth.toLong() + typeWidth + gap <= availableWidth) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(date, style = metadataStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label, style = typeStyle, color = typeColor)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(date, style = metadataStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label, style = typeStyle, color = typeColor)
                        }
                    }
                }
            }
        }
    }
}
