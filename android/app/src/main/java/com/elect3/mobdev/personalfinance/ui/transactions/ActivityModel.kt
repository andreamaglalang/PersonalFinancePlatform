package com.elect3.mobdev.personalfinance.ui.transactions

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** One rendered line of the Activity list. Keys are stable across recompositions and pages. */
sealed interface ActivityLine {
    val key: String
    data class MonthHeader(val month: YearMonth) : ActivityLine { override val key = "m-$month" }
    /** [net] only when the whole day is known (complete period); never a partial-page figure. */
    data class DayHeader(val date: LocalDate, val net: BigDecimal?) : ActivityLine { override val key = "d-$date" }
    data class OrderHeader(val order: AmountOrder, val count: Int) : ActivityLine { override val key = "o-$order" }
    data class Row(val transaction: Transaction, val first: Boolean, val last: Boolean, val showDate: Boolean) : ActivityLine {
        override val key = "t-${transaction.transactionId}"
    }
}

/** Totals for a complete period of plain PHP amounts. */
data class PeriodTotals(val income: BigDecimal, val expenses: BigDecimal, val count: Int, val volumeByDay: Map<LocalDate, BigDecimal>) {
    val net: BigDecimal get() = income.subtract(expenses)
}

/** Plain PHP: no conversion involved, so base and original amounts agree (the Dashboard's rule). */
fun isPlainPhp(t: Transaction) = t.currencyCode == "PHP" && t.fxRate.compareTo(BigDecimal.ONE) == 0 && t.amountBase.compareTo(t.amount) == 0

/** Null unless every amount is plain PHP. Callers pass only a complete period. */
fun periodTotals(items: List<Transaction>): PeriodTotals? {
    if (items.any { !isPlainPhp(it) }) return null
    var income = BigDecimal.ZERO; var expenses = BigDecimal.ZERO
    val byDay = LinkedHashMap<LocalDate, BigDecimal>()
    items.forEach { t ->
        if (t.type == TransactionType.Income) income = income.add(t.amountBase) else expenses = expenses.add(t.amountBase)
        byDay[t.transactionDate] = (byDay[t.transactionDate] ?: BigDecimal.ZERO).add(t.amountBase)
    }
    return PeriodTotals(income, expenses, items.size, byDay)
}

/** Case-insensitive match on category, description, or amount digits (e.g. "185.5" or "1,250"). */
fun matchesSearch(t: Transaction, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (t.categoryName.lowercase().contains(q) || t.description?.lowercase()?.contains(q) == true) return true
    val digits = q.replace(Regex("[₱,\\s]"), "")
    return digits.matches(Regex("[0-9.]+")) && t.amount.toPlainString().contains(digits)
}

/**
 * Builds the list lines. Search and amount order are applied only when [complete] (the whole period is loaded);
 * otherwise items are shown in the server order with day (and, for all time, month) headers and no day totals.
 */
fun activityLines(items: List<Transaction>, complete: Boolean, order: AmountOrder?, query: String, monthHeaders: Boolean): List<ActivityLine> {
    val visible = if (complete) items.filter { matchesSearch(it, query) } else items
    val out = ArrayList<ActivityLine>(visible.size + 16)
    if (complete && order != null) {
        val sorted = visible.sortedWith(compareBy<Transaction> { it.amountBase }.thenBy { it.transactionId }.let {
            if (order == AmountOrder.Largest) it.reversed() else it })
        if (sorted.isNotEmpty()) out += ActivityLine.OrderHeader(order, sorted.size)
        sorted.forEachIndexed { i, t -> out += ActivityLine.Row(t, i == 0, i == sorted.lastIndex, showDate = true) }
        return out
    }
    var month: YearMonth? = null
    val days = visible.groupByTo(LinkedHashMap()) { it.transactionDate }
    days.forEach { (date, rows) ->
        val ym = YearMonth.from(date)
        if (monthHeaders && ym != month) { out += ActivityLine.MonthHeader(ym); month = ym }
        val net = if (complete && rows.all(::isPlainPhp)) rows.fold(BigDecimal.ZERO) { acc, t ->
            if (t.type == TransactionType.Income) acc.add(t.amountBase) else acc.subtract(t.amountBase) } else null
        out += ActivityLine.DayHeader(date, net)
        rows.forEachIndexed { i, t -> out += ActivityLine.Row(t, i == 0, i == rows.lastIndex, showDate = false) }
    }
    return out
}
