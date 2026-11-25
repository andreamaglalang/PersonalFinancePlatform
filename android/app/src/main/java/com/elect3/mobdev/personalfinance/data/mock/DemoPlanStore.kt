package com.elect3.mobdev.personalfinance.data.mock

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DemoGoal(val id: Int, val name: String, val target: BigDecimal, val saved: BigDecimal) {
    init { require(target.signum() > 0 && saved.signum() >= 0) }
    val remaining: BigDecimal get() = (target - saved).max(BigDecimal.ZERO)
    val progress: Float get() = saved.divide(target, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    fun contribute(amount: BigDecimal): DemoGoal {
        require(amount.signum() > 0)
        return copy(saved = (saved + amount).min(target))
    }
}
enum class DemoBillStatus(val label: String) { Upcoming("Upcoming"), DueSoon("Due soon"), Overdue("Overdue"), Paid("Paid") }
data class DemoBill(val id: Int, val name: String, val amount: BigDecimal, val nextDue: LocalDate, val paid: Boolean = false) {
    fun status(today: LocalDate): DemoBillStatus = when {
        paid -> DemoBillStatus.Paid
        nextDue < today -> DemoBillStatus.Overdue
        nextDue <= today.plusDays(3) -> DemoBillStatus.DueSoon
        else -> DemoBillStatus.Upcoming
    }
}
/** Memory only. No API, database or transaction writes; reset when Mock Demo ends. */
class DemoPlanStore(private val today: () -> LocalDate = { LocalDate.now() }) {
    private val goalState = MutableStateFlow<List<DemoGoal>>(emptyList())
    private val billState = MutableStateFlow<List<DemoBill>>(emptyList())
    val goals = goalState.asStateFlow()
    val bills = billState.asStateFlow()
    init { reset() }
    fun reset() {
        goalState.value = listOf(
            DemoGoal(1, "Emergency Fund", BigDecimal("60000.00"), BigDecimal("24000.00")),
            DemoGoal(2, "New Laptop", BigDecimal("45000.00"), BigDecimal("31500.00")))
        val day = today()
        billState.value = listOf(
            DemoBill(1, "Internet", BigDecimal("1699.00"), day.plusDays(2)),
            DemoBill(2, "Electricity", BigDecimal("2450.00"), day.minusDays(2)),
            DemoBill(3, "Water", BigDecimal("380.00"), day.plusDays(10)))
    }
    fun contribute(id: Int) { goalState.value = goalState.value.map { if (it.id == id) it.contribute(BigDecimal("500.00")) else it } }
    fun markPaid(id: Int) { billState.value = billState.value.map { if (it.id == id) it.copy(paid = true) else it } }
}
