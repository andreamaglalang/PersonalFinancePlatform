package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType

/** Fit the category and amount to the actual card width, with a full-width fallback. */
@Composable
fun TransactionRow(transaction: Transaction, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val date = remember(transaction.transactionDate, configuration) {
        formatTransactionDate(transaction.transactionDate)
    }
    val amount = remember(transaction.amount) { formatPhpAmount(transaction.amount) }
    val income = transaction.type == TransactionType.Income
    val label = stringResource(if (income) R.string.transaction_income else R.string.transaction_expense)
    val signedAmount = stringResource(
        if (income) R.string.transaction_income_amount else R.string.transaction_expense_amount,
        amount,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val categoryStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val amountStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val metadataStyle = MaterialTheme.typography.bodySmall
    val typeColor = if (income) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Unconstrained text measurement includes the current density, font scale,
            // and font resolver. Reserve space only when both complete values fit.
            val categoryWidth = textMeasurer.measure(transaction.categoryName, categoryStyle, softWrap = false).size.width
            val amountWidth = textMeasurer.measure(signedAmount, amountStyle, softWrap = false).size.width
            val gap = with(density) { 16.dp.roundToPx() }
            val availableWidth = constraints.maxWidth
            val sideBySide = configuration.fontScale <= 1.3f &&
                categoryWidth.toLong() + amountWidth + gap + 2 <= availableWidth
            val dateWidth = textMeasurer.measure(date, metadataStyle, softWrap = false).size.width
            val typeWidth = textMeasurer.measure(label, metadataStyle, softWrap = false).size.width
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (dateWidth.toLong() + typeWidth + gap <= availableWidth) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(date, style = metadataStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = metadataStyle, color = typeColor)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(date, style = metadataStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = metadataStyle, color = typeColor)
                    }
                }
            }
        }
    }
}
