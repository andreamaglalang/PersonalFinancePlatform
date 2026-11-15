package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

enum class OwliSummaryRole {
    Income,
    Expense,
    Neutral,
}

/** Displays caller-provided text without changing financial values or formatting. */
@Composable
fun OwliSummaryCard(
    label: String,
    value: String,
    supportingText: String? = null,
    role: OwliSummaryRole,
    modifier: Modifier = Modifier,
) {
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val owliColors = LocalOwliColors.current
    val colors = MaterialTheme.colorScheme
    val (containerColor, labelColor, valueColor) = when (role) {
        OwliSummaryRole.Income -> Triple(
            owliColors.incomeCardFill, owliColors.incomeCardLabel, owliColors.income,
        )
        OwliSummaryRole.Expense -> Triple(
            owliColors.expenseCardFill, owliColors.expenseCardLabel, owliColors.expense,
        )
        OwliSummaryRole.Neutral -> Triple(
            colors.surface, colors.onSurfaceVariant, colors.onSurface,
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 88.dp)
            .semantics(mergeDescendants = true) {},
        shape = shapes.summaryCard,
        color = containerColor,
        contentColor = valueColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = label, style = typography.label, color = labelColor)
            Spacer(modifier = Modifier.height(6.dp))
            // Default wrapping and unlimited lines retain the complete supplied value.
            Text(text = value, style = typography.amountMedium, color = valueColor)
            supportingText?.let { text ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = text, style = typography.caption, color = labelColor)
            }
        }
    }
}
