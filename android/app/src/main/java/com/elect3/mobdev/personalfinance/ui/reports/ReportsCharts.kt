package com.elect3.mobdev.personalfinance.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.reports.FinancialReport
import com.elect3.mobdev.personalfinance.domain.reports.MonthlyReport
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupCard
import com.elect3.mobdev.personalfinance.ui.components.OwliGroupDivider
import com.elect3.mobdev.personalfinance.ui.components.OwliSectionHeader
import com.elect3.mobdev.personalfinance.ui.dashboard.sliceColor
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.Locale

/**
 * Spending by category for the selected month. The donut is a visual summary of the top categories; the ranked
 * list below is the complete, text-based breakdown (rank numbers, names, amounts and percentages), so no
 * information depends on colour alone.
 */
@Composable
internal fun ReportsBreakdown(report: MonthlyReport, locale: Locale) {
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val owli = LocalOwliColors.current
    val month = reportMonthLabel(report.month, locale)
    val currency = report.currency
    val categories = report.expenseCategories
    val total = report.totalExpenses
    val slices = remember(categories, total) { reportSlices(categories, total) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OwliSectionHeader(stringResource(R.string.reports_breakdown_title), trailing = month)
        OwliGroupCard {
            if (slices.isEmpty()) {
                Text(stringResource(R.string.reports_breakdown_empty, month), style = typography.body, color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(14.dp))
                return@OwliGroupCard
            }
            val otherLabel = slices.lastOrNull()?.takeIf { it.rank == null }?.let { stringResource(R.string.reports_breakdown_other, it.otherCount) }
            val chartSummary = stringResource(R.string.reports_breakdown_chart_description, month, formatReportAmount(total, currency),
                slices.joinToString("; ") { "${if (it.rank == null) otherLabel else it.label} ${formatReportAmount(it.total, currency)}, ${it.share}" })
            val colors = slices.map { sliceColor(it.hex) }
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val track = owli.hairline
                Canvas(Modifier.size(176.dp).clearAndSetSemantics { contentDescription = chartSummary }) {
                    val stroke = 22.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2f
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    val arc = Size(radius * 2, radius * 2)
                    drawCircle(track, radius = radius, style = Stroke(stroke))
                    val gap = if (slices.size > 1) 1.6f else 0f
                    var start = -90f
                    slices.forEachIndexed { i, s ->
                        val sweep = s.fraction * 360f
                        drawArc(colors[i], start + gap / 2f, (sweep - gap).coerceAtLeast(1.4f), useCenter = false,
                            topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                        start += sweep
                    }
                }
                Column(Modifier.width(112.dp).clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.reports_breakdown_center).uppercase(locale), style = typography.overline, color = scheme.onSurfaceVariant)
                    Text(formatCompactReportAmount(total, currency), style = typography.amountMedium, textAlign = TextAlign.Center)
                    Text(pluralStringResource(R.plurals.reports_category_count, categories.size, categories.size),
                        style = typography.caption, color = scheme.onSurfaceVariant)
                }
            }
            val highest = categories.first().total
            categories.forEachIndexed { index, category ->
                OwliGroupDivider()
                val share = reportShareLabel(category.total, total)
                val count = pluralStringResource(R.plurals.reports_category_transactions, category.transactionCount, category.transactionCount)
                val amount = formatReportAmount(category.total, currency)
                val description = stringResource(R.string.reports_breakdown_row_description, index + 1, category.categoryName, amount, share, count)
                val tint = if (index < slices.size && slices[index].rank != null) colors[index] else sliceColor(REPORT_OTHER_HEX)
                val isTop = category.total.compareTo(highest) == 0
                Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(String.format(Locale.ROOT, "%02d", index + 1), style = typography.micro.copy(fontFeatureSettings = "tnum"),
                            color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
                                Text(category.categoryName, style = typography.bodyStrong, modifier = Modifier.weight(1f, fill = false))
                                if (isTop) Text(stringResource(R.string.reports_top_badge), style = typography.overline.copy(fontSize = 9.sp),
                                    color = owli.accent, modifier = Modifier.clip(LocalOwliShapes.current.rowTile).background(owli.accentSoft)
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Text(count, style = typography.caption, color = scheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(amount, style = typography.amountRow, textAlign = TextAlign.End)
                            Text(share, style = typography.caption.copy(fontFeatureSettings = "tnum"), color = scheme.onSurfaceVariant)
                        }
                    }
                    // Bar length = share of the month's total spending (decorative; the percentage is in text).
                    val fraction = category.total.divide(total, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.padding(start = 26.dp).fillMaxWidth().height(4.dp).clip(LocalOwliShapes.current.chip).background(owli.hairline)) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(tint))
                    }
                }
            }
        }
    }
}

/**
 * Six-month income vs. expense comparison ending at the selected month. Bars are always paired in the same order
 * (income left, expenses right) and labelled in the legend, and every value is repeated in the table below.
 * Tapping a month (axis label or table row) selects that month's report.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportsTrend(report: FinancialReport, locale: Locale, onSelectMonth: (YearMonth) -> Unit) {
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val owli = LocalOwliColors.current
    val trend = report.trend
    val currency = report.currency
    val selected = report.selected.month
    val incomeColor = owli.income
    val expenseColor = owli.expense
    val range = stringResource(R.string.reports_trend_period, reportShortMonthLabel(trend.first().month, locale) +
        (if (trend.first().month.year != trend.last().month.year) " " + trend.first().month.year else ""), reportMonthLabel(trend.last().month, locale))
    val monthDescriptions = trend.map {
        stringResource(R.string.reports_trend_month_description, reportMonthLabel(it.month, locale), formatReportAmount(it.income, currency),
            formatReportAmount(it.expenses, currency), formatSignedReportAmount(it.net, currency))
    }
    val chartDescription = stringResource(R.string.reports_trend_chart_description, monthDescriptions.joinToString(". "))
    val change = remember(trend) { spendingChange(trend) }
    val previousMonthLabel = reportMonthLabel(selected.minusMonths(1), locale)
    val changeText = when (change) {
        is SpendingChange.Up -> stringResource(R.string.reports_change_up, change.percent, previousMonthLabel)
        is SpendingChange.Down -> stringResource(R.string.reports_change_down, change.percent, previousMonthLabel)
        is SpendingChange.Same -> stringResource(R.string.reports_change_same, previousMonthLabel)
        SpendingChange.NoBaseline -> stringResource(R.string.reports_change_none, previousMonthLabel)
    }
    val maxValue = trend.maxOf { maxOf(it.income, it.expenses) }
    val axisWidth = 48.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OwliSectionHeader(stringResource(R.string.reports_trend_title), trailing = range)
        OwliGroupCard {
            Row(Modifier.fillMaxWidth().background(owli.accentSoft.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_owli_eye_glyph), contentDescription = null, tint = owli.accent,
                    modifier = Modifier.padding(top = 1.dp).size(18.dp))
                Text(changeText, style = typography.caption, color = scheme.onSurface)
            }
            OwliGroupDivider()
            FlowRow(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendItem(incomeColor, R.drawable.ic_owli_arrow_up, stringResource(R.string.reports_trend_legend_income))
                LegendItem(expenseColor, R.drawable.ic_owli_arrow_down, stringResource(R.string.reports_trend_legend_expenses))
            }
            val measurer = rememberTextMeasurer()
            val axisStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurfaceVariant)
            val grid = owli.hairline
            val highlight = owli.accentSoft
            val topLabel = formatCompactReportAmount(maxValue, currency)
            val midLabel = formatCompactReportAmount(maxValue.divide(BigDecimal(2), 2, RoundingMode.HALF_UP), currency)
            val zeroLabel = formatCompactReportAmount(BigDecimal.ZERO, currency)
            val selectedIndex = trend.indexOfFirst { it.month == selected }
            Canvas(Modifier.fillMaxWidth().height(168.dp).padding(start = 8.dp, end = 14.dp, top = 12.dp)
                .clearAndSetSemantics { contentDescription = chartDescription }) {
                val axis = axisWidth.toPx() - 8.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = size.height - 2.dp.toPx()
                val plotHeight = bottom - top
                val plotWidth = size.width - axis
                val column = plotWidth / trend.size
                if (selectedIndex >= 0) drawRoundRect(highlight, Offset(axis + column * selectedIndex + 2.dp.toPx(), 0f),
                    Size(column - 4.dp.toPx(), size.height), CornerRadius(10.dp.toPx()))
                listOf(0f to zeroLabel, 0.5f to midLabel, 1f to topLabel).forEach { (f, label) ->
                    val y = bottom - plotHeight * f
                    drawLine(grid, Offset(axis, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                        pathEffect = if (f == 0f) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    val layout = measurer.measure(label, axisStyle)
                    drawText(layout, topLeft = Offset((axis - layout.size.width - 6.dp.toPx()).coerceAtLeast(0f), y - layout.size.height / 2f))
                }
                val barWidth = (column * 0.26f).coerceAtMost(14.dp.toPx())
                val max = maxValue.toFloat()
                trend.forEachIndexed { i, point ->
                    val centerX = axis + column * i + column / 2f
                    listOf(point.income to incomeColor, point.expenses to expenseColor).forEachIndexed { j, (value, color) ->
                        val h = if (max <= 0f) 0f else plotHeight * (value.toFloat() / max)
                        val x = if (j == 0) centerX - barWidth - 1.5.dp.toPx() else centerX + 1.5.dp.toPx()
                        // A non-zero amount always shows at least a sliver.
                        val drawn = if (value.signum() > 0) h.coerceAtLeast(2.dp.toPx()) else 0f
                        if (drawn > 0f) drawRoundRect(color, Offset(x, bottom - drawn), Size(barWidth, drawn), CornerRadius(3.dp.toPx()))
                    }
                }
            }
            // Month axis: real Text (scales with the font size), each a 48 dp target that selects the month.
            Row(Modifier.fillMaxWidth().padding(end = 14.dp)) {
                Spacer(Modifier.width(axisWidth))
                trend.forEach { point ->
                    val on = point.month == selected
                    val action = stringResource(R.string.reports_trend_select_month, reportMonthLabel(point.month, locale))
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = action, role = Role.Tab) { onSelectMonth(point.month) }
                        .semantics { this.selected = on; contentDescription = reportMonthLabel(point.month, locale) },
                        contentAlignment = Alignment.Center) {
                        Text(reportShortMonthLabel(point.month, locale), style = typography.micro.copy(
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium),
                            color = if (on) owli.accent else scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            OwliGroupDivider()
            TrendTable(report, locale, monthDescriptions, onSelectMonth)
        }
    }
}

@Composable
private fun LegendItem(color: Color, icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(label, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The chart's data as text; newest month first. Large font sizes switch to a stacked layout per month. */
@Composable
private fun TrendTable(report: FinancialReport, locale: Locale, descriptions: List<String>, onSelectMonth: (YearMonth) -> Unit) {
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val owli = LocalOwliColors.current
    val currency = report.currency
    val stacked = LocalConfiguration.current.fontScale > 1.3f
    val numberStyle = typography.caption.copy(fontFeatureSettings = "tnum")
    if (!stacked) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).clearAndSetSemantics { }) {
            Text(stringResource(R.string.reports_trend_month), style = typography.micro, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            listOf(R.string.reports_income, R.string.reports_expenses, R.string.reports_trend_net).forEach {
                Text(stringResource(it), style = typography.micro, color = scheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
            }
        }
    }
    val selectedLabel = stringResource(R.string.reports_trend_selected)
    report.trend.indices.reversed().forEach { i ->
        val point = report.trend[i]
        val on = point.month == report.selected.month
        val action = stringResource(R.string.reports_trend_select_month, reportMonthLabel(point.month, locale))
        val description = if (on) "${descriptions[i]}, $selectedLabel" else descriptions[i]
        val rowModifier = Modifier.fillMaxWidth().background(if (on) owli.accentSoft.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClickLabel = action, role = Role.Button) { onSelectMonth(point.month) }
            .clearAndSetSemantics { contentDescription = description; this.selected = on }
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp)
        val netColor = when (point.net.signum()) { 1 -> owli.income; -1 -> owli.expense; else -> scheme.onSurface }
        if (stacked) Column(rowModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(reportMonthLabel(point.month, locale), style = typography.bodyStrong)
            Text("${stringResource(R.string.reports_income)} ${formatReportAmount(point.income, currency)}", style = numberStyle)
            Text("${stringResource(R.string.reports_expenses)} ${formatReportAmount(point.expenses, currency)}", style = numberStyle)
            Text("${stringResource(R.string.reports_trend_net)} ${formatSignedReportAmount(point.net, currency)}", style = numberStyle, color = netColor)
        } else Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(reportShortMonthLabel(point.month, locale) + " " + point.month.year, style = typography.caption.copy(
                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium), modifier = Modifier.weight(1f))
            Text(formatCompactReportAmount(point.income, currency), style = numberStyle, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
            Text(formatCompactReportAmount(point.expenses, currency), style = numberStyle, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
            Text(formatCompactSigned(point.net, currency), style = numberStyle, color = netColor, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
        }
    }
}

/** Compact net with explicit "+" for gains (compact formatter already adds "−" for losses). */
private fun formatCompactSigned(amount: BigDecimal, currency: String): String =
    (if (amount.signum() > 0) "+" else "") + formatCompactReportAmount(amount, currency)
