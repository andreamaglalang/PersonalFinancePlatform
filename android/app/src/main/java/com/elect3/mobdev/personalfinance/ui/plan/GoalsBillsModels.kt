package com.elect3.mobdev.personalfinance.ui.plan

import androidx.annotation.StringRes
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.google.gson.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/* Typed views of the savings-goal and recurring-bill API records. Parsing is strict: a missing or
 * malformed field throws, and callers show the "invalid response" state instead of a partial record. */

enum class GoalStatus(@StringRes val label: Int) { Active(R.string.goal_status_active), Completed(R.string.goal_status_completed), Cancelled(R.string.goal_status_cancelled) }
enum class BillFrequency(@StringRes val label: Int) { Weekly(R.string.bill_frequency_weekly), Monthly(R.string.bill_frequency_monthly), Quarterly(R.string.bill_frequency_quarterly), Yearly(R.string.bill_frequency_yearly) }
enum class BillStatus(@StringRes val label: Int) { Inactive(R.string.bill_status_inactive), Overdue(R.string.bill_status_overdue), DueSoon(R.string.bill_status_due_soon), Upcoming(R.string.bill_status_upcoming) }

sealed interface PlanRecord { val id: Int; val name: String }

data class SavingsGoal(
    override val id: Int, override val name: String, val targetAmount: BigDecimal, val savedAmount: BigDecimal,
    val remainingAmount: BigDecimal, val progressPercentage: BigDecimal, val baseCurrency: String,
    val status: GoalStatus, val targetDate: LocalDate?, val version: String,
) : PlanRecord

data class RecurringBill(
    override val id: Int, override val name: String, val amount: BigDecimal, val currencyCode: String,
    val categoryId: Int, val categoryName: String, val frequency: BillFrequency, val firstDueDate: LocalDate,
    val nextDueDate: LocalDate, val reminderDaysBefore: Int, val isActive: Boolean,
) : PlanRecord

data class Contribution(val id: Int, val amount: BigDecimal, val contributedOn: LocalDate, val note: String?, val baseCurrency: String, val version: String)
data class PaymentReceipt(val clientId: UUID, val billId: Int, val dueDate: LocalDate, val amount: BigDecimal, val currencyCode: String)
data class ContributionReceipt(val clientId: UUID, val goalId: Int, val originalAmount: BigDecimal, val originalContributedOn: LocalDate, val originalNote: String?, val deleted: Boolean)
data class PlanPage<T>(val items: List<T>, val totalCount: Long)

private fun JsonObject.str(key: String): String = get(key).also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
private fun JsonObject.optStr(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let { str(key) }
private fun JsonObject.id(key: String): Int = get(key).also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber) }.asInt.also { require(it > 0) }
private fun JsonObject.money(key: String): BigDecimal = get(key).also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber) }.asBigDecimal.also { require(it.signum() >= 0) }
private fun JsonObject.date(key: String): LocalDate = LocalDate.parse(str(key)).also { require(it.year in 1..9999) }
private fun JsonObject.currency(key: String): String = str(key).also { require(it.matches(Regex("[A-Z]{3}"))) }
private fun JsonObject.bool(key: String): Boolean = get(key).also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }.asBoolean

fun parseSavingsGoal(o: JsonObject) = SavingsGoal(o.id("goalId"), o.str("name").also { require(it.isNotBlank()) }, o.money("targetAmount"), o.money("savedAmount"),
    o.money("remainingAmount"), o.money("progressPercentage"), o.currency("baseCurrency"), GoalStatus.valueOf(o.str("status")),
    if (o.get("targetDate")?.isJsonNull == false) o.date("targetDate") else null, o.str("version"))

fun parseRecurringBill(o: JsonObject) = RecurringBill(o.id("billId"), o.str("name").also { require(it.isNotBlank()) }, o.money("amount"), o.currency("currencyCode"),
    o.id("categoryId"), o.str("categoryName"), BillFrequency.valueOf(o.str("frequency")), o.date("firstDueDate"), o.date("nextDueDate"),
    o.get("reminderDaysBefore").asInt.also { require(it in 0..30) }, o.bool("isActive"))

fun parseContribution(o: JsonObject) = Contribution(o.id("contributionId"), o.money("amount"), o.date("contributedOn"), o.optStr("note"), o.currency("baseCurrency"), o.str("version"))

fun parsePaymentReceipt(root: JsonObject): PaymentReceipt = root.getAsJsonObject("receipt").let {
    PaymentReceipt(UUID.fromString(it.str("clientId")), it.id("billId"), it.date("dueDate"), it.money("amount"), it.currency("currencyCode"))
}

fun parseContributionReceipt(root: JsonObject): ContributionReceipt = root.getAsJsonObject("receipt").let {
    ContributionReceipt(UUID.fromString(it.str("clientId")), it.id("goalId"), it.money("originalAmount"), it.date("originalContributedOn"), it.optStr("originalNote"), root.bool("deleted"))
}

/** One explicitly requested page of 50 records, as the repository asks for. */
fun <T> parsePlanPage(root: JsonObject, page: Int, row: (JsonObject) -> T): PlanPage<T> {
    require(root.get("page").asInt == page && root.get("pageSize").asInt == 50)
    val items = root.getAsJsonArray("items").map { row(it.asJsonObject) }
    require(items.size <= 50)
    return PlanPage(items, root.get("totalCount").asLong.also { require(it >= 0) })
}

/** True only when the server receipt matches the exact persisted payment request. */
fun PaymentReceipt.matches(request: JsonObject, billId: Int) =
    clientId.toString().equals(request.get("clientId").asString, true) && this.billId == billId && dueDate.toString() == request.get("dueDate").asString

/** True only when the server receipt matches the exact persisted contribution request. */
fun ContributionReceipt.matches(request: JsonObject, goalId: Int) =
    clientId.toString().equals(request.get("clientId").asString, true) && this.goalId == goalId &&
        originalAmount.compareTo(request.get("amount").asBigDecimal) == 0 && originalContributedOn.toString() == request.get("contributedOn").asString &&
        originalNote == request.get("note")?.takeUnless { it.isJsonNull }?.asString

fun billPaymentPath(billId: Int) = "recurring-bills/$billId/payments"
fun goalContributionPath(goalId: Int) = "savings-goals/$goalId/contributions"

fun billStatus(bill: RecurringBill, today: LocalDate): BillStatus = when {
    !bill.isActive -> BillStatus.Inactive
    bill.nextDueDate < today -> BillStatus.Overdue
    bill.nextDueDate <= today.plusDays(bill.reminderDaysBefore.toLong()) -> BillStatus.DueSoon
    else -> BillStatus.Upcoming
}

/** A persisted uncertain payment keeps the pay action available so the same identity can be checked. */
data class BillRowState(val status: BillStatus, val paymentPending: Boolean, val canPay: Boolean)
fun billRowState(bill: RecurringBill, today: LocalDate, pendingPaths: Set<String>): BillRowState {
    val pending = billPaymentPath(bill.id) in pendingPaths
    return BillRowState(billStatus(bill, today), pending, (bill.isActive && bill.nextDueDate <= today) || pending)
}

data class GoalRowState(val contributionPending: Boolean, val canContribute: Boolean, val canEdit: Boolean, val canCancel: Boolean)
fun goalRowState(goal: SavingsGoal, pendingPaths: Set<String>): GoalRowState {
    val pending = goalContributionPath(goal.id) in pendingPaths
    val open = goal.status != GoalStatus.Cancelled
    return GoalRowState(pending, open || pending, open, open)
}

enum class PlanListState { Loading, Error, Empty, Content }
fun planListState(loading: Boolean, error: Int?, available: Boolean, count: Int): PlanListState = when {
    loading -> PlanListState.Loading
    error != null || !available -> PlanListState.Error
    count == 0 -> PlanListState.Empty
    else -> PlanListState.Content
}

/** Progress as MVC shows it: clamped to 0–100 with at most one decimal. */
fun goalProgressText(percentage: BigDecimal, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; roundingMode = RoundingMode.HALF_UP }
        .format(percentage.max(BigDecimal.ZERO).min(BigDecimal(100)))

@StringRes fun planFailureMessage(reason: FinanceFailure, writing: Boolean = false): Int = when (reason) {
    FinanceFailure.MissingSession, FinanceFailure.SessionExpired -> R.string.plan_failure_session
    FinanceFailure.InvalidInput -> R.string.plan_failure_invalid
    FinanceFailure.Conflict -> R.string.plan_failure_conflict
    FinanceFailure.NotFound -> R.string.plan_failure_not_found
    FinanceFailure.AccessDenied -> R.string.plan_failure_denied
    FinanceFailure.RateLimited -> R.string.plan_failure_rate_limited
    FinanceFailure.NotConfigured -> R.string.plan_failure_not_configured
    else -> if (writing) R.string.plan_failure_unknown_outcome else R.string.plan_failure_load
}
