@file:OptIn(ExperimentalLayoutApi::class)

package com.elect3.mobdev.personalfinance.ui.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** Reads a field of a persisted request body (never a server record; those are parsed into typed models). */
internal fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun positiveAmount(value: String): BigDecimal? = value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 && it.precision() <= 18 }
/** Which amount rule a typed value breaks (the server's wording), or null when [positiveAmount] accepts it. */
@androidx.annotation.StringRes internal fun amountProblem(value: String): Int? {
    val text = value.trim()
    if (text.isEmpty()) return R.string.amount_error_empty
    val amount = text.toBigDecimalOrNull() ?: return R.string.amount_error_number
    return when {
        amount.signum() <= 0 -> R.string.amount_error_positive
        amount.scale() > 2 -> R.string.amount_error_decimals
        amount.precision() > 18 || amount > BigDecimal("9999999999999999.99") -> R.string.amount_error_large
        else -> null
    }
}
internal fun validPlanDate(value: String): Boolean = runCatching { LocalDate.parse(value).year in 1..9999 }.getOrDefault(false)
private fun displayDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
private fun displayDate(iso: String): String = runCatching { displayDate(LocalDate.parse(iso)) }.getOrDefault(iso)
private fun money(amount: BigDecimal, currency: String) = formatTransactionAmount(amount, currency)

private enum class PlanAction { Pay, Cancel, Delete }

@Composable
private fun PlanMessage(text: String, error: Boolean = false) {
    Text(text, style = LocalOwliTypography.current.caption, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun SelectableOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick, modifier = Modifier.semantics { this.selected = selected }) {
        Text(if (selected) stringResource(R.string.plan_option_selected, label) else label)
    }
}

@Composable
fun LivePlanSection(live: LiveAccountPlanRepository, bills: Boolean, categories: CategoryRepository?, onPaid: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val path = if (bills) "recurring-bills" else "savings-goals"
    val typography = LocalOwliTypography.current
    var records by remember(live, bills) { mutableStateOf<List<PlanRecord>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableLongStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<PlanRecord?>(null) }
    var create by remember { mutableStateOf(false) }
    var contribution by remember { mutableStateOf<SavingsGoal?>(null) }
    var action by remember { mutableStateOf<Pair<PlanRecord, PlanAction>?>(null) }
    // Persisted uncertain contributions/payments (survive app restart); shown as pending, never as confirmed.
    var pending by remember(live, bills) { mutableStateOf(emptySet<String>()) }
    suspend fun reload() {
        loading = true; error = null; records = emptyList()
        pending = runCatching { live.pendingPaths() }.getOrDefault(emptySet())
        when(val result = live.page(path, page)) {
            is FinanceResult.Success -> runCatching { parsePlanPage(result.value, page, if (bills) ::parseRecurringBill else ::parseSavingsGoal) }
                .onSuccess { records = it.items; total = it.totalCount; available = true }
                .onFailure { available = false; error = R.string.plan_invalid_list }
            is FinanceResult.Failure -> { available = false; error = planFailureMessage(result.reason) }
        }
        loading = false
    }
    LaunchedEffect(live, bills, page) { reload() }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = available && !loading, onClick = { create = true }) { Text(stringResource(if (bills) R.string.plan_add_bill else R.string.plan_add_goal)) }
            TextButton(enabled = !loading, onClick = { scope.launch { reload() } }) { Text(stringResource(R.string.plan_refresh)) }
        }
        if (pending.any { it.startsWith("$path/") }) PlanMessage(stringResource(if (bills) R.string.plan_pending_payment_banner else R.string.plan_pending_contribution_banner), error = true)
        when (planListState(loading, error, available, records.size)) {
            PlanListState.Loading -> Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (bills) R.string.plan_loading_bills else R.string.plan_loading_goals), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlanListState.Error -> PlanEmpty(R.drawable.ic_cat_info, if (bills) R.string.plan_bills_error_title else R.string.plan_goals_error_title, error ?: R.string.plan_failure_load) {
                OutlinedButton(onClick = { scope.launch { reload() } }, modifier = Modifier.padding(top = 10.dp)) { Text(stringResource(R.string.transactions_retry)) }
            }
            PlanListState.Empty -> PlanEmpty(R.drawable.ic_cat_wallet, if (bills) R.string.plan_bills_empty_title else R.string.plan_goals_empty_title,
                if (bills) R.string.plan_bills_empty_message else R.string.plan_goals_empty_message)
            PlanListState.Content -> Unit
        }
        val today = LocalDate.now()
        records.forEach { record ->
            val goalRow = (record as? SavingsGoal)?.let { goalRowState(it, pending) }
            PlanCard {
                Text(record.name, style = typography.bodyStrong, modifier = Modifier.semantics { heading() })
                when (record) {
                    is RecurringBill -> {
                        val row = billRowState(record, today, pending)
                        Text(money(record.amount, record.currencyCode), style = typography.body)
                        Text(stringResource(R.string.bill_schedule, stringResource(record.frequency.label), displayDate(record.nextDueDate), stringResource(row.status.label)),
                            style = typography.caption, color = if (row.status == BillStatus.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(record.categoryName, style = typography.caption)
                        Text(if (record.reminderDaysBefore == 0) stringResource(R.string.bill_reminder_due_date)
                            else pluralStringResource(R.plurals.bill_reminder_days, record.reminderDaysBefore, record.reminderDaysBefore), style = typography.caption)
                        if (row.paymentPending) PlanMessage(stringResource(R.string.bill_payment_pending), error = true)
                        if (row.canPay) TextButton(onClick = { action = record to PlanAction.Pay }) {
                            Text(stringResource(if (row.paymentPending) R.string.bill_check_pending_payment else R.string.bill_record_payment))
                        }
                    }
                    is SavingsGoal -> {
                        val saved = money(record.savedAmount, record.baseCurrency)
                        val target = money(record.targetAmount, record.baseCurrency)
                        val percent = goalProgressText(record.progressPercentage)
                        val progressLabel = stringResource(R.string.goal_progress_description, saved, target, percent)
                        Text(stringResource(R.string.goal_saved_of, saved, target), style = typography.body, modifier = Modifier.semantics { contentDescription = progressLabel })
                        LinearProgressIndicator(progress = { goalBarFraction(record.progressPercentage) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel })
                        val (captionRes, captionAmount) = goalProgressCaption(record)
                        Text(stringResource(captionRes, percent, money(captionAmount, record.baseCurrency)), style = typography.caption)
                        val status = stringResource(record.status.label)
                        Text(record.targetDate?.let { stringResource(R.string.plan_pair, status, stringResource(R.string.goal_target_date, displayDate(it))) } ?: status, style = typography.caption)
                        if (goalRow!!.contributionPending) PlanMessage(stringResource(R.string.goal_contribution_pending), error = true)
                        if (goalRow.canContribute) TextButton(onClick = { contribution = record }) { Text(stringResource(R.string.goal_contributions)) }
                    }
                }
                FlowRow {
                    if (goalRow?.canEdit != false) TextButton(onClick = { editing = record }) { Text(stringResource(R.string.plan_edit)) }
                    if (goalRow?.canCancel == true) TextButton(onClick = { action = record to PlanAction.Cancel }) { Text(stringResource(R.string.goal_cancel)) }
                    TextButton(onClick = { action = record to PlanAction.Delete }) { Text(stringResource(R.string.plan_delete)) }
                }
            }
        }
        if (available && (page > 1 || page * 50L < total)) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = page > 1 && !loading, onClick = { page-- }) { Text(stringResource(R.string.plan_previous)) }
            Text(stringResource(R.string.plan_page, page), style = typography.caption)
            TextButton(enabled = page * 50L < total && !loading, onClick = { page++ }) { Text(stringResource(R.string.plan_next)) }
        }
    }
    if (create || editing != null) LivePlanEditor(live, bills, editing, categories,
        onClose = { create = false; editing = null }, onSaved = { create = false; editing = null; scope.launch { reload() } })
    contribution?.let { goal -> ContributionDialog(live, goal, onClose = { contribution = null; scope.launch { reload() } }) }
    action?.let { (record, operation) ->
        val res = LocalContext.current.resources
        val paymentPath = billPaymentPath(record.id)
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var unknown by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(record, operation) {
            if (operation == PlanAction.Pay) live.pendingRequest(paymentPath)?.let {
                unknown = true
                message = res.getString(R.string.bill_pay_pending_message, displayDate(it.text("dueDate")))
            }
        }
        AlertDialog(onDismissRequest = { if (!busy) action = null }, title = { Text(when(operation) {
                PlanAction.Pay -> stringResource(R.string.bill_pay_title)
                PlanAction.Cancel -> stringResource(R.string.goal_cancel_title)
                PlanAction.Delete -> stringResource(R.string.plan_delete_title, record.name) }) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (record is RecurringBill && operation == PlanAction.Pay) stringResource(R.string.bill_pay_message, displayDate(record.nextDueDate)) else stringResource(R.string.plan_delete_message))
                message?.let { PlanMessage(it) }
            } },
            dismissButton = { TextButton(enabled = !busy, onClick = { action = null; scope.launch { reload() } }) { Text(stringResource(R.string.plan_close)) } },
            confirmButton = { if (!done) TextButton(enabled = !busy && (!unknown || operation == PlanAction.Pay), onClick = { scope.launch {
                busy = true
                val requestPath = "$path/${record.id}" + when(operation) { PlanAction.Pay -> "/payments"; PlanAction.Cancel -> "/cancel"; PlanAction.Delete -> "" }
                // Reuse the persisted identity and payload if one exists (even from before an app restart).
                val body = if (operation == PlanAction.Pay) live.pendingRequest(paymentPath) ?: fields("clientId" to UUID.randomUUID().toString(), "dueDate" to (record as RecurringBill).nextDueDate.toString()) else null
                when(val result = live.save(requestPath, body, delete = operation == PlanAction.Delete, version = (record as? SavingsGoal)?.version)) {
                    is FinanceResult.Success -> {
                        done = true
                        message = if (operation == PlanAction.Pay) {
                            val receipt = runCatching { parsePaymentReceipt(result.value) }.getOrNull()
                            if (receipt != null && receipt.matches(body!!, record.id)) {
                                onPaid(); res.getString(R.string.bill_payment_recorded, money(receipt.amount, receipt.currencyCode))
                            } else res.getString(R.string.bill_payment_unverified)
                        } else res.getString(R.string.plan_saved)
                        reload()
                    }
                    is FinanceResult.Failure -> { unknown = if (operation == PlanAction.Pay) result.reason !in definitiveRejections else result.reason in uncertainFailures; message = planFailureText(res, result, true) }
                }
                busy = false
            } }) { Text(stringResource(if (unknown) R.string.bill_check_same_payment else R.string.plan_confirm)) } })
    }
}

@Composable
private fun LivePlanEditor(live: LiveAccountPlanRepository, bills: Boolean, record: PlanRecord?, categories: CategoryRepository?, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bill = record as? RecurringBill
    val goal = record as? SavingsGoal
    var name by remember { mutableStateOf(record?.name.orEmpty()) }
    var amount by remember { mutableStateOf((bill?.amount ?: goal?.targetAmount)?.toPlainString().orEmpty()) }
    var date by remember { mutableStateOf((bill?.firstDueDate ?: goal?.targetDate)?.toString().orEmpty()) }
    var currency by remember { mutableStateOf(bill?.currencyCode ?: "PHP") }
    var frequency by remember { mutableStateOf(BillFrequency.Monthly) }
    var reminder by remember { mutableStateOf(bill?.reminderDaysBefore?.toString() ?: "3") }
    var active by remember { mutableStateOf(bill?.isActive ?: true) }
    var options by remember { mutableStateOf<List<Category>>(emptyList()) }
    var categoryPage by remember { mutableIntStateOf(1) }
    var categoryTotal by remember { mutableLongStateOf(0) }
    var categoryId by remember { mutableStateOf(bill?.categoryId) }
    var busy by remember { mutableStateOf(false) }
    var unknown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var detail by remember { mutableStateOf<String?>(null) } // Specific reasons; shown instead of [error].
    val res = LocalContext.current.resources
    LaunchedEffect(categories, categoryPage) {
        if (bills && categories != null) {
            busy = true
            val result = categories.list(CategoryQuery(type = TransactionType.Expense, page = categoryPage, pageSize = 100))
            if (result is FinanceResult.Success) { options = result.value.items; categoryTotal = result.value.totalCount }
            else error = R.string.bill_categories_failed
            busy = false
        }
    }
    val editable = !busy && !unknown
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (record == null) stringResource(if (bills) R.string.plan_add_bill else R.string.plan_add_goal) else stringResource(R.string.plan_edit_title, record.name)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { if (it.length <= 100) name = it }, enabled = editable, label = { Text(stringResource(R.string.plan_field_name)) })
            OutlinedTextField(amount, { amount = it }, enabled = editable, label = { Text(stringResource(if (bills) R.string.plan_field_amount else R.string.goal_field_target)) })
            if (!bills || record == null) OutlinedTextField(date, { date = it }, enabled = editable, label = { Text(stringResource(if (bills) R.string.bill_field_first_due else R.string.goal_field_target_date)) })
            if (bills) {
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, enabled = editable, label = { Text(stringResource(R.string.bill_field_currency)) })
                Text(stringResource(R.string.bill_field_category))
                options.forEach { category -> SelectableOption(category.name, categoryId == category.categoryId, editable) { categoryId = category.categoryId } }
                if (categoryPage > 1 || categoryPage * 100L < categoryTotal) FlowRow {
                    TextButton(enabled = editable && categoryPage > 1, onClick = { categoryPage-- }) { Text(stringResource(R.string.bill_categories_previous)) }
                    TextButton(enabled = editable && categoryPage * 100L < categoryTotal, onClick = { categoryPage++ }) { Text(stringResource(R.string.bill_categories_more)) }
                }
                if (bill == null) BillFrequency.entries.forEach { value -> SelectableOption(stringResource(value.label), frequency == value, editable) { frequency = value } }
                else Text(stringResource(R.string.bill_schedule_fixed, stringResource(bill.frequency.label)))
                OutlinedTextField(reminder, { reminder = it }, enabled = editable, label = { Text(stringResource(R.string.bill_field_reminder)) })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }, enabled = editable); Text(stringResource(R.string.bill_field_active)) }
            }
            (detail ?: error?.let { stringResource(it) })?.let { PlanMessage(it, error = true) }
        } }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.plan_close)) } },
        confirmButton = { Button(enabled = editable, onClick = {
            val decimal = positiveAmount(amount)
            // Every problem at once, each named specifically.
            val problems = buildList {
                if (name.trim().isEmpty() || name.any(Char::isISOControl)) add(R.string.plan_error_name)
                amountProblem(amount)?.let(::add)
                if ((!bills || record == null) && date.isNotEmpty() && !validPlanDate(date)) add(R.string.plan_error_date)
                if (bills && categoryId == null) add(R.string.bill_error_category)
                if (bills && !currency.matches(Regex("[A-Z]{3}"))) add(R.string.bill_error_currency)
                if (bills && reminder.toIntOrNull() !in 0..30) add(R.string.bill_error_reminder)
                if (bills && record == null && date.isEmpty()) add(R.string.plan_error_first_due)
            }
            error = null; detail = null
            if (problems.isNotEmpty() || decimal == null) detail = problems.joinToString(" ") { res.getString(it) }.ifEmpty { res.getString(R.string.plan_editor_invalid) }
            else scope.launch {
                busy = true
                val body = if (bills) fields("name" to name.trim(), "amount" to decimal, "categoryId" to categoryId, "currencyCode" to currency, "reminderDaysBefore" to reminder.toInt(), "isActive" to active).apply {
                    if (record == null) { addProperty("frequency", frequency.name); addProperty("firstDueDate", date) }
                } else fields("name" to name.trim(), "targetAmount" to decimal, "targetDate" to date.takeIf { it.isNotEmpty() })
                val path = (if (bills) "recurring-bills" else "savings-goals") + (record?.let { "/${it.id}" } ?: "")
                when(val result = live.save(path, body, update = record != null, version = goal?.version)) {
                    is FinanceResult.Success -> onSaved()
                    is FinanceResult.Failure -> { detail = planFailureText(res, result, true); unknown = result.reason in uncertainFailures || result.reason == FinanceFailure.Conflict }
                }
                busy = false
            }
        }) { Text(stringResource(R.string.plan_save)) } })
}

@Composable
private fun ContributionDialog(live: LiveAccountPlanRepository, goal: SavingsGoal, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val res = LocalContext.current.resources
    val caption = LocalOwliTypography.current.caption
    val path = goalContributionPath(goal.id)
    var entries by remember { mutableStateOf<List<Contribution>?>(null) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(live, path) {
        // A persisted uncertain contribution is restored exactly (same ClientId and payload), even after a restart.
        live.pendingRequest(path)?.let {
            request = it; amount = it.text("amount"); date = it.text("contributedOn"); note = it.text("note")
            error = res.getString(R.string.contribution_pending_message)
        }
    }
    var selected by remember { mutableStateOf<Contribution?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var ledgerPage by remember { mutableIntStateOf(1) }
    var total by remember { mutableLongStateOf(0) }
    suspend fun load() {
        when(val result = live.page(path, ledgerPage)) {
            is FinanceResult.Success -> runCatching { parsePlanPage(result.value, ledgerPage, ::parseContribution) }
                .onSuccess { entries = it.items; total = it.totalCount }
                .onFailure { error = res.getString(R.string.plan_invalid_list) }
            is FinanceResult.Failure -> error = planFailureText(res, result)
        }
    }
    LaunchedEffect(live, ledgerPage) { busy = true; load(); busy = false }
    val editable = !busy && request == null
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(goal.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.contribution_info), style = caption)
            if (entries == null && busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (entries?.isEmpty() == true) Text(stringResource(R.string.contributions_empty), style = caption)
            entries.orEmpty().forEach { entry ->
                TextButton(enabled = editable, onClick = { selected = entry; confirmDelete = false; amount = entry.amount.toPlainString(); date = entry.contributedOn.toString(); note = entry.note.orEmpty() }) {
                    Text(stringResource(R.string.contribution_row, displayDate(entry.contributedOn), money(entry.amount, entry.baseCurrency)))
                }
            }
            if (ledgerPage > 1 || ledgerPage * 50L < total) FlowRow {
                TextButton(enabled = !busy && ledgerPage > 1, onClick = { ledgerPage-- }) { Text(stringResource(R.string.plan_previous)) }
                TextButton(enabled = !busy && ledgerPage * 50L < total, onClick = { ledgerPage++ }) { Text(stringResource(R.string.plan_next)) }
            }
            Text(stringResource(if (selected == null) R.string.contribution_add else R.string.contribution_edit), style = LocalOwliTypography.current.bodyStrong)
            OutlinedTextField(amount, { amount = it }, enabled = editable, label = { Text(stringResource(R.string.plan_field_amount)) })
            OutlinedTextField(date, { date = it }, enabled = editable, label = { Text(stringResource(R.string.contribution_field_date)) })
            OutlinedTextField(note, { if (it.length <= 200) note = it }, enabled = editable, label = { Text(stringResource(R.string.contribution_field_note)) })
            error?.let { PlanMessage(it) }
            if (selected != null && request == null) TextButton(enabled = !busy, onClick = { if (!confirmDelete) confirmDelete = true else scope.launch {
                busy = true
                val item = selected!!
                when(val result = live.save("$path/${item.id}", null, delete = true, version = item.version)) {
                    is FinanceResult.Success -> { selected = null; amount = ""; load() }
                    is FinanceResult.Failure -> error = planFailureText(res, result, true)
                }
                busy = false
            } }) { Text(stringResource(if (confirmDelete) R.string.contribution_confirm_delete else R.string.contribution_delete)) }
        }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.plan_close)) } }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val decimal = positiveAmount(amount)
            if (decimal == null || !validPlanDate(date) || LocalDate.parse(date) > LocalDate.now()) error = res.getString(R.string.contribution_invalid)
            else scope.launch {
                busy = true
                val item = selected
                val body = request ?: (if (item == null) live.pendingRequest(path) else null) ?: fields("amount" to decimal, "contributedOn" to date, "note" to note.takeIf { it.isNotEmpty() }).apply { if (item == null) addProperty("clientId", UUID.randomUUID().toString()) }
                if (item == null) request = body // Retain exactly this key and payload across an explicit retry.
                when(val result = live.save(path + (item?.let { "/${it.id}" } ?: ""), body, update = item != null, version = item?.version)) {
                    is FinanceResult.Success -> {
                        val receipt = if (item == null) runCatching { parseContributionReceipt(result.value) }.getOrNull() else null
                        val valid = item != null || (receipt != null && receipt.matches(body, goal.id))
                        error = res.getString(if (!valid) R.string.contribution_unverified else if (receipt?.deleted == true) R.string.contribution_previously_deleted else R.string.contribution_saved)
                        request = null; selected = null; amount = ""; note = ""; load()
                    }
                    is FinanceResult.Failure -> { error = planFailureText(res, result, true); if (result.reason in definitiveRejections) request = null }
                }
                busy = false
            }
        }) { Text(stringResource(if (request != null) R.string.contribution_check_same else R.string.plan_save)) }
    })
}

/** An over-funded goal reports its surplus, never a negative remaining amount. */
internal fun goalProgressCaption(goal: SavingsGoal): Pair<Int, java.math.BigDecimal> =
    if (goal.remainingAmount.signum() < 0) R.string.goal_progress_over_target to goal.remainingAmount.negate()
    else R.string.goal_progress_remaining to goal.remainingAmount

/** The progress bar is capped at a full bar; the caption shows the real percentage. */
internal fun goalBarFraction(percentage: java.math.BigDecimal): Float = percentage.divide(java.math.BigDecimal(100)).toFloat().coerceIn(0f, 1f)
