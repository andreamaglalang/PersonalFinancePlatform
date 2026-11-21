package com.elect3.mobdev.personalfinance.ui.reports

import com.elect3.mobdev.personalfinance.domain.reports.MonthTrendPoint
import com.elect3.mobdev.personalfinance.domain.reports.ReportCategoryTotal
import com.elect3.mobdev.personalfinance.ui.categories.CategoryVisuals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/** Selected month's expenses compared with the month before it, both taken from the same verified trend read. */
sealed interface SpendingChange {
    /** Previous month had no expenses, so a percentage would be meaningless. */
    data object NoBaseline : SpendingChange
    data class Up(val previous: YearMonth, val percent: String) : SpendingChange
    data class Down(val previous: YearMonth, val percent: String) : SpendingChange
    data class Same(val previous: YearMonth) : SpendingChange
}

fun spendingChange(trend: List<MonthTrendPoint>): SpendingChange {
    if (trend.size < 2) return SpendingChange.NoBaseline
    val current = trend.last()
    val previous = trend[trend.size - 2]
    if (previous.expenses.signum() <= 0) return SpendingChange.NoBaseline
    val diff = current.expenses.subtract(previous.expenses)
    val percent = diff.abs().multiply(BigDecimal(100)).divide(previous.expenses, 1, RoundingMode.HALF_UP).toPlainString() + "%"
    return when (diff.signum()) {
        1 -> SpendingChange.Up(previous.month, percent)
        -1 -> SpendingChange.Down(previous.month, percent)
        else -> SpendingChange.Same(previous.month)
    }
}

/** One spending-breakdown chart slice. [rank] is null for the grouped remainder. */
data class ReportSlice(val label: String, val total: BigDecimal, val fraction: Float, val share: String,
                       val rank: Int?, val otherCount: Int, val hex: String)

/** Neutral grey for grouped remainder, distinct from every category palette colour (same as the Dashboard). */
internal const val REPORT_OTHER_HEX = "#8A84A3"

/** Number of categories drawn individually in the breakdown chart; the full list is always shown as text. */
const val REPORT_CHART_SLICES = 5

/** Top categories individually, the rest as one slice; fractions use the month's full expense total. */
fun reportSlices(categories: List<ReportCategoryTotal>, totalExpenses: BigDecimal): List<ReportSlice> {
    if (categories.isEmpty() || totalExpenses.signum() <= 0) return emptyList()
    val used = HashSet<String>()
    fun fraction(v: BigDecimal) = v.divide(totalExpenses, 6, RoundingMode.HALF_UP).toFloat()
    val shown = categories.take(REPORT_CHART_SLICES).mapIndexed { i, c ->
        ReportSlice(c.categoryName, c.total, fraction(c.total), reportShareLabel(c.total, totalExpenses), i + 1, 0, distinctHex(c.categoryId, used))
    }
    val rest = categories.drop(REPORT_CHART_SLICES)
    if (rest.isEmpty()) return shown
    val restTotal = rest.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.total) }
    return shown + ReportSlice("", restTotal, fraction(restTotal), reportShareLabel(restTotal, totalExpenses), null, rest.size, REPORT_OTHER_HEX)
}

/** The category's palette colour (same rule as Activity), or the next unused one so adjacent slices differ. */
private fun distinctHex(categoryId: Int, used: MutableSet<String>): String {
    val palette = CategoryVisuals.palette
    val start = Math.floorMod(categoryId, palette.size)
    val hex = palette.indices.map { palette[(start + it) % palette.size].hex }.firstOrNull { it !in used } ?: palette[start].hex
    used += hex
    return hex
}
