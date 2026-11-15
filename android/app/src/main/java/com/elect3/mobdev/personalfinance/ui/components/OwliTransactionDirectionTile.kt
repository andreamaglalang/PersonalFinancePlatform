package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes

enum class OwliTransactionDirection {
    Income,
    Expense,
}

/**
 * Decorative direction tile. The parent transaction row must communicate Income or
 * Expense through accessible text; this tile adds no separate announcement.
 */
@Composable
fun OwliTransactionDirectionTile(
    direction: OwliTransactionDirection,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOwliColors.current
    val shapes = LocalOwliShapes.current
    val (icon, containerColor, iconColor) = when (direction) {
        OwliTransactionDirection.Income -> Triple(
            R.drawable.ic_owli_income_up, colors.incomeCardFill, colors.income,
        )
        OwliTransactionDirection.Expense -> Triple(
            R.drawable.ic_owli_expense_down, colors.expenseCardFill, colors.expense,
        )
    }

    Surface(
        modifier = modifier.size(40.dp),
        shape = shapes.rowTile,
        color = containerColor,
        contentColor = iconColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconColor,
            )
        }
    }
}
