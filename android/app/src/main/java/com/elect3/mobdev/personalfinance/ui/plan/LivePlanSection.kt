package com.elect3.mobdev.personalfinance.ui.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.data.remote.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

internal fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.amount(key: String): BigDecimal = get(key).asBigDecimal
internal fun positiveAmount(value: String): BigDecimal? = value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 && it.precision() <= 18 }
internal fun validPlanDate(value: String): Boolean = runCatching { LocalDate.parse(value).year in 1..9999 }.getOrDefault(false)

@Composable
fun LivePlanSection(live: LiveAccountPlanRepository, bills: Boolean, categories: CategoryRepository?, onPaid: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val path = if (bills) "recurring-bills" else "savings-goals"
    var records by remember(live, bills) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableLongStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var create by remember { mutableStateOf(false) }
    var contribution by remember { mutableStateOf<JsonObject?>(null) }
    var action by remember { mutableStateOf<Pair<JsonObject, String>?>(null) }
    // Persisted uncertain contributions/payments (survive app restart); shown as pending, never as confirmed.
    var pending by remember(live, bills) { mutableStateOf(emptySet<String>()) }
    suspend fun reload() {
        loading = true; error = null; records = emptyList()
        pending = runCatching { live.pendingPaths() }.getOrDefault(emptySet())
        when(val result = live.page(path, page)) {
            is FinanceResult.Success -> runCatching {
                val root = result.value
                require(root.get("page").asInt == page && root.get("pageSize").asInt == 50)
                val items = root.getAsJsonArray("items").map { it.asJsonObject }
                require(items.size <= 50)
                records = items; total = root.get("totalCount").asLong; available = true
            }.onFailure { available = false; error = "The API returned an invalid record list." }
            is FinanceResult.Failure -> { available = false; error = liveFailure(result.reason) }
        }
        loading = false
    }
    LaunchedEffect(live, bills, page) { reload() }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = available && !loading, onClick = { create = true }) { Text(if (bills) "Add bill" else "Add goal") }
            TextButton(enabled = !loading, onClick = { scope.launch { reload() } }) { Text("Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (pending.any { it.startsWith("$path/") }) Text(if (bills) "A payment's outcome is unknown. It is pending, not confirmed. Open Record payment to check the same payment."
            else "A contribution's outcome is unknown. It is pending, not confirmed. Open Contributions to check the same contribution.", color = MaterialTheme.colorScheme.error)
        if (available && !loading && records.isEmpty()) Text(if (bills) "No recurring bills yet." else "No savings goals yet.")
        records.forEach { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.text("name"), style = MaterialTheme.typography.titleMedium)
                    if (bills) {
                        Text(formatTransactionAmount(record.amount("amount"), record.text("currencyCode")))
                        val due = LocalDate.parse(record.text("nextDueDate"))
                        val active = record.get("isActive").asBoolean
                        val today = LocalDate.now()
                        val status = when { !active -> "Inactive"; due < today -> "Overdue"; due <= today.plusDays(record.get("reminderDaysBefore").asLong) -> "Due soon"; else -> "Upcoming" }
                        Text("${record.text("frequency")} · Due $due · $status")
                        Text(record.text("categoryName"))
                        val paymentPending = "$path/${record.text("billId")}/payments" in pending
                        if (paymentPending) Text("Payment pending · outcome unknown")
                        if ((active && due <= today) || paymentPending) TextButton(onClick = { action = record to "pay" }) { Text(if (paymentPending) "Check pending payment" else "Record payment") }
                    } else {
                        val currency = record.text("baseCurrency")
                        Text("${formatTransactionAmount(record.amount("savedAmount"), currency)} saved of ${formatTransactionAmount(record.amount("targetAmount"), currency)}")
                        LinearProgressIndicator(progress = { record.amount("progressPercentage").divide(BigDecimal(100)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${record.text("progressPercentage")}% · ${formatTransactionAmount(record.amount("remainingAmount"), currency)} remaining")
                        Text(record.text("status") + record.text("targetDate").takeIf { it.isNotEmpty() }?.let { " · Target $it" }.orEmpty())
                        val contributionPending = "$path/${record.text("goalId")}/contributions" in pending
                        if (contributionPending) Text("Contribution pending · outcome unknown")
                        if (record.text("status") != "Cancelled" || contributionPending) TextButton(onClick = { contribution = record }) { Text("Contributions") }
                    }
                    Row {
                        if (bills || record.text("status") != "Cancelled") TextButton(onClick = { editing = record }) { Text("Edit") }
                        if (!bills && record.text("status") != "Cancelled") TextButton(onClick = { action = record to "cancel" }) { Text("Cancel goal") }
                        TextButton(onClick = { action = record to "delete" }) { Text("Delete") }
                    }
                }
            }
        }
        if (available && (page > 1 || page * 50L < total)) Row {
            TextButton(enabled = page > 1 && !loading, onClick = { page-- }) { Text("Previous") }
            Text("Page $page")
            TextButton(enabled = page * 50L < total && !loading, onClick = { page++ }) { Text("Next") }
        }
    }
    if (create || editing != null) LivePlanEditor(live, bills, editing, categories,
        onClose = { create = false; editing = null }, onSaved = { create = false; editing = null; scope.launch { reload() } })
    contribution?.let { goal -> ContributionDialog(live, goal, onClose = { contribution = null; scope.launch { reload() } }) }
    action?.let { (record, operation) ->
        val id = record.text(if (bills) "billId" else "goalId")
        val paymentPath = "$path/$id/payments"
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var unknown by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(record, operation) {
            if (operation == "pay") live.pendingRequest(paymentPath)?.let {
                unknown = true
                message = "A previous attempt for the ${it.text("dueDate")} payment has an unknown outcome. It is pending, not confirmed. Check the same payment to find out."
            }
        }
        AlertDialog(onDismissRequest = { if (!busy) action = null }, title = { Text(when(operation) { "pay" -> "Record payment?"; "cancel" -> "Cancel goal?"; else -> "Delete ${record.text("name")}?" }) },
            text = { Column {
                Text(if (operation == "pay") "Records a real expense for ${record.text("nextDueDate")} using the bill's current amount and advances its due date. No bank transfer is made." else "Records with financial history may require cancellation or deactivation instead.")
                message?.let { Text(it) }
            } },
            dismissButton = { TextButton(enabled = !busy, onClick = { action = null; scope.launch { reload() } }) { Text("Close") } },
            confirmButton = { if (!done) TextButton(enabled = !busy && (!unknown || operation == "pay"), onClick = { scope.launch {
                busy = true
                val requestPath = "$path/$id" + when(operation) { "pay" -> "/payments"; "cancel" -> "/cancel"; else -> "" }
                // Reuse the persisted identity and payload if one exists (even from before an app restart).
                val body = if (operation == "pay") live.pendingRequest(paymentPath) ?: fields("clientId" to UUID.randomUUID().toString(), "dueDate" to record.text("nextDueDate")) else null
                when(val result = live.save(requestPath, body, delete = operation == "delete", version = if (bills) null else record.text("version"))) {
                    is FinanceResult.Success -> {
                        done = true
                        message = if (operation == "pay") {
                            val receipt = result.value.getAsJsonObject("receipt")
                            if (receipt.text("clientId").equals(body!!.text("clientId"), true) && receipt.text("billId") == id && receipt.text("dueDate") == body!!.text("dueDate")) {
                                onPaid(); "Payment recorded: ${formatTransactionAmount(receipt.amount("amount"), receipt.text("currencyCode"))}."
                            } else "Payment response could not be verified. Refresh your records before any further payment."
                        } else "Saved."
                        reload()
                    }
                    is FinanceResult.Failure -> { unknown = if (operation == "pay") result.reason !in definitiveRejections else result.reason in uncertainFailures; message = liveFailure(result.reason, true) }
                }
                busy = false
            } }) { Text(if (unknown) "Check same payment" else "Confirm") } })
    }
}

@Composable
private fun LivePlanEditor(live: LiveAccountPlanRepository, bills: Boolean, record: JsonObject?, categories: CategoryRepository?, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(record?.text("name").orEmpty()) }
    var amount by remember { mutableStateOf(record?.text(if (bills) "amount" else "targetAmount").orEmpty()) }
    var date by remember { mutableStateOf(record?.text(if (bills) "firstDueDate" else "targetDate").orEmpty()) }
    var currency by remember { mutableStateOf(record?.text("currencyCode") ?: "PHP") }
    var frequency by remember { mutableStateOf("Monthly") }
    var reminder by remember { mutableStateOf(record?.text("reminderDaysBefore") ?: "3") }
    var active by remember { mutableStateOf(record?.get("isActive")?.asBoolean ?: true) }
    var options by remember { mutableStateOf<List<Category>>(emptyList()) }
    var categoryPage by remember { mutableIntStateOf(1) }
    var categoryTotal by remember { mutableLongStateOf(0) }
    var categoryId by remember { mutableStateOf(record?.get("categoryId")?.asInt) }
    var busy by remember { mutableStateOf(false) }
    var unknown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(categories, categoryPage) {
        if (bills && categories != null) {
            busy = true
            val result = categories.list(CategoryQuery(type = TransactionType.Expense, page = categoryPage, pageSize = 100))
            if (result is FinanceResult.Success) { options = result.value.items; categoryTotal = result.value.totalCount }
            else error = "Cannot load expense categories."
            busy = false
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(if (record == null) if (bills) "Add bill" else "Add goal" else "Edit ${record.text("name")}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { if (it.length <= 100) name = it }, enabled = !busy && !unknown, label = { Text("Name") })
            OutlinedTextField(amount, { amount = it }, enabled = !busy && !unknown, label = { Text(if (bills) "Amount" else "Target amount") })
            if (!bills || record == null) OutlinedTextField(date, { date = it }, enabled = !busy && !unknown, label = { Text(if (bills) "First due · YYYY-MM-DD" else "Target date · optional YYYY-MM-DD") })
            if (bills) {
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, enabled = !busy && !unknown, label = { Text("Currency code") })
                Text("Expense category")
                options.forEach { category -> TextButton(enabled = !busy && !unknown, onClick = { categoryId = category.categoryId }) { Text((if (categoryId == category.categoryId) "âœ“ " else "") + category.name) } }
                if (categoryPage > 1 || categoryPage * 100L < categoryTotal) Row {
                    TextButton(enabled = !busy && !unknown && categoryPage > 1, onClick = { categoryPage-- }) { Text("Previous categories") }
                    TextButton(enabled = !busy && !unknown && categoryPage * 100L < categoryTotal, onClick = { categoryPage++ }) { Text("More categories") }
                }
                if (record == null) listOf("Weekly", "Monthly", "Quarterly", "Yearly").forEach { value -> TextButton(enabled = !busy && !unknown, onClick = { frequency = value }) { Text((if (frequency == value) "âœ“ " else "") + value) } }
                else Text("${record.text("frequency")} · Schedule is fixed")
                OutlinedTextField(reminder, { reminder = it }, enabled = !busy && !unknown, label = { Text("Remind days before · 0–30") })
                Row { Checkbox(active, { active = it }, enabled = !busy && !unknown); Text("Active") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Close") } },
        confirmButton = { Button(enabled = !busy && !unknown, onClick = {
            val decimal = positiveAmount(amount)
            if (name.trim().isEmpty() || name.any(Char::isISOControl) || decimal == null || ((!bills || record == null) && date.isNotEmpty() && !validPlanDate(date)) ||
                (bills && (categoryId == null || !currency.matches(Regex("[A-Z]{3}")) || reminder.toIntOrNull() !in 0..30 || (record == null && date.isEmpty())))) error = "Check the name, amount, date and bill settings."
            else scope.launch {
                busy = true
                val body = if (bills) fields("name" to name.trim(), "amount" to decimal, "categoryId" to categoryId, "currencyCode" to currency, "reminderDaysBefore" to reminder.toInt(), "isActive" to active).apply {
                    if (record == null) { addProperty("frequency", frequency); addProperty("firstDueDate", date) }
                } else fields("name" to name.trim(), "targetAmount" to decimal, "targetDate" to date.takeIf { it.isNotEmpty() })
                val path = (if (bills) "recurring-bills" else "savings-goals") + (record?.let { "/" + it.text(if (bills) "billId" else "goalId") } ?: "")
                when(val result = live.save(path, body, update = record != null, version = if (!bills) record?.text("version") else null)) {
                    is FinanceResult.Success -> onSaved()
                    is FinanceResult.Failure -> { error = liveFailure(result.reason, true); unknown = result.reason in uncertainFailures || result.reason == FinanceFailure.Conflict }
                }
                busy = false
            }
        }) { Text("Save") } })
}

@Composable
private fun ContributionDialog(live: LiveAccountPlanRepository, goal: JsonObject, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val path = "savings-goals/${goal.text("goalId")}/contributions"
    var entries by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
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
            error = "A previous contribution has an unknown outcome. It is pending, not confirmed. Check the same contribution to find out."
        }
    }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var ledgerPage by remember { mutableIntStateOf(1) }
    var total by remember { mutableLongStateOf(0) }
    suspend fun load() {
        when(val result = live.page(path, ledgerPage)) {
            is FinanceResult.Success -> { entries = result.value.getAsJsonArray("items").map { it.asJsonObject }; total = result.value.get("totalCount").asLong }
            is FinanceResult.Failure -> error = liveFailure(result.reason)
        }
    }
    LaunchedEffect(live, ledgerPage) { busy = true; load(); busy = false }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(goal.text("name")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Contributions track savings. They do not transfer money or create expenses.")
            entries.forEach { entry -> TextButton(enabled = !busy && request == null, onClick = { selected = entry; confirmDelete = false; amount = entry.text("amount"); date = entry.text("contributedOn"); note = entry.text("note") }) {
                Text("${entry.text("contributedOn")} · ${formatTransactionAmount(entry.amount("amount"), entry.text("baseCurrency"))} · Edit")
            } }
            if (ledgerPage > 1 || ledgerPage * 50L < total) Row {
                TextButton(enabled = !busy && ledgerPage > 1, onClick = { ledgerPage-- }) { Text("Previous") }
                TextButton(enabled = !busy && ledgerPage * 50L < total, onClick = { ledgerPage++ }) { Text("Next") }
            }
            Text(if (selected == null) "Add contribution" else "Edit contribution")
            OutlinedTextField(amount, { amount = it }, enabled = !busy && request == null, label = { Text("Amount") })
            OutlinedTextField(date, { date = it }, enabled = !busy && request == null, label = { Text("Date · YYYY-MM-DD") })
            OutlinedTextField(note, { if (it.length <= 200) note = it }, enabled = !busy && request == null, label = { Text("Note · optional") })
            error?.let { Text(it) }
            if (selected != null && request == null) TextButton(enabled = !busy, onClick = { if (!confirmDelete) confirmDelete = true else scope.launch {
                busy = true
                val item = selected!!
                when(val result = live.save("$path/${item.text("contributionId")}", null, delete = true, version = item.text("version"))) {
                    is FinanceResult.Success -> { selected = null; amount = ""; load() }
                    is FinanceResult.Failure -> error = liveFailure(result.reason, true)
                }
                busy = false
            } }) { Text(if (confirmDelete) "Confirm delete" else "Delete contribution") }
        }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Close") } }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val decimal = positiveAmount(amount)
            if (decimal == null || !validPlanDate(date) || LocalDate.parse(date) > LocalDate.now()) error = "Enter a positive amount and a date no later than today."
            else scope.launch {
                busy = true
                val item = selected
                val body = request ?: (if (item == null) live.pendingRequest(path) else null) ?: fields("amount" to decimal, "contributedOn" to date, "note" to note.takeIf { it.isNotEmpty() }).apply { if (item == null) addProperty("clientId", UUID.randomUUID().toString()) }
                if (item == null) request = body // Retain exactly this key and payload across an explicit retry.
                when(val result = live.save(path + (item?.let { "/${it.text("contributionId")}" } ?: ""), body, update = item != null, version = item?.text("version"))) {
                    is FinanceResult.Success -> {
                        val receipt = result.value.getAsJsonObject("receipt")
                        val valid = item != null || (receipt != null && receipt.text("clientId").equals(body.text("clientId"), true) && receipt.text("goalId") == goal.text("goalId") && receipt.amount("originalAmount").compareTo(body.amount("amount")) == 0 && receipt.text("originalContributedOn") == body.text("contributedOn") && receipt.text("originalNote") == body.text("note"))
                        error = if (!valid) "The response could not be verified. Check your ledger." else if (result.value.get("deleted")?.asBoolean == true) "This contribution was previously deleted. Nothing was added." else "Contribution saved."
                        request = null; selected = null; amount = ""; note = ""; load()
                    }
                    is FinanceResult.Failure -> { error = liveFailure(result.reason, true); if (result.reason in definitiveRejections) request = null }
                }
                busy = false
            }
        }) { Text(if (request != null) "Check same contribution" else "Save") }
    })
}
