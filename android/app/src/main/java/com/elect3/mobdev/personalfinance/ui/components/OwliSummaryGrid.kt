package com.elect3.mobdev.personalfinance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography

@Immutable
data class OwliSummaryItem(
    val label: String,
    val value: String,
    val role: OwliSummaryRole,
    val supportingText: String? = null,
)

/**
 * Two columns only when every card's complete label and value fit on one line at half width;
 * otherwise (including font scale above 1.3) one full-width column. Values are never truncated.
 */
@Composable
fun OwliSummaryGrid(items: List<OwliSummaryItem>, modifier: Modifier = Modifier) {
    val typography = LocalOwliTypography.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontScale = LocalConfiguration.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = with(density) { GridGap.roundToPx() }
        val cardPadding = with(density) { (CardPadding * 2).roundToPx() }
        val columnContent = (constraints.maxWidth - gap) / 2 - cardPadding
        fun width(text: String, style: TextStyle) =
            measurer.measure(text, style, softWrap = false).size.width
        val twoColumns = fontScale <= 1.3f && columnContent > 0 && items.all {
            maxOf(width(it.label, typography.label), width(it.value, typography.amountMedium)) <= columnContent
        }
        Column(verticalArrangement = Arrangement.spacedBy(GridGap)) {
            if (twoColumns) {
                items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                        pair.forEach { SummaryCell(it, Modifier.weight(1f).fillMaxHeight()) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items.forEach { SummaryCell(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun SummaryCell(item: OwliSummaryItem, modifier: Modifier) {
    OwliSummaryCard(label = item.label, value = item.value, supportingText = item.supportingText,
        role = item.role, modifier = modifier)
}

private val GridGap = 12.dp
// Matches OwliSummaryCard's internal padding.
private val CardPadding = 14.dp
