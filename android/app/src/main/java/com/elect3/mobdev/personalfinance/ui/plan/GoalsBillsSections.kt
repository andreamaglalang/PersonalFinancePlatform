package com.elect3.mobdev.personalfinance.ui.plan

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.data.mock.*
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun money(amount: BigDecimal) = formatTransactionAmount(amount, "PHP")

@Composable
private fun PlanCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalOwliColors.current.hairline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun PlanNotice(title: String, message: String) {
    PlanCard {
        Text(title, style = LocalOwliTypography.current.bodyStrong)
        Text(message, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GoalsSection(demo: Boolean, goals: List<DemoGoal>, onContribute: (Int) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!demo) {
            PlanNotice("Your savings goals", "Account goals are not available here yet.")
        } else {
            PlanNotice("Build your next milestone", "Mock Demo · PHP · Resets on exit")
            if (goals.isEmpty()) PlanNotice("No savings goals", "There are no goals in this demo session.")
            goals.forEach { goal ->
                PlanCard {
                    Text(goal.name, style = LocalOwliTypography.current.bodyStrong)
                    Text("${money(goal.saved)} saved of ${money(goal.target)}", style = LocalOwliTypography.current.body)
                    LinearProgressIndicator(progress = { goal.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(goal.progress * 100).toInt()}% complete • ${money(goal.remaining)} remaining", style = LocalOwliTypography.current.caption)
                    TextButton(onClick = { selected = goal.id }) { Text("${goal.name} details") }
                }
            }
        }
    }
    val goal = if (demo) goals.firstOrNull { it.id == selected } else null
    if (goal != null) AlertDialog(onDismissRequest = { selected = null }, title = { Text(goal.name) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Demo only · No money transferred")
            Text("Target: ${money(goal.target)}")
            Text("Saved: ${money(goal.saved)}")
            Text("Remaining: ${money(goal.remaining)}")
            Text("Progress: ${(goal.progress * 100).toInt()}%")
            if (goal.remaining.signum() == 0) Text("Goal reached!")
        } },
        confirmButton = { TextButton(enabled = goal.remaining.signum() > 0, onClick = { onContribute(goal.id) }) {
            Text("Add ${money(BigDecimal("500").min(goal.remaining))} (demo)")
        } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
}

@Composable
fun BillsSection(demo: Boolean, bills: List<DemoBill>, onMarkPaid: (Int) -> Unit, today: LocalDate = LocalDate.now()) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!demo) {
            PlanNotice("Your recurring bills", "Account bills are not available here yet.")
        } else {
            PlanNotice("Stay ahead of your bills", "Mock Demo · PHP · No real payments")
            if (bills.isEmpty()) PlanNotice("No recurring bills", "There are no bills in this demo session.")
            bills.sortedBy { it.nextDue }.forEach { bill ->
                val status = bill.status(today)
                PlanCard {
                    Text(bill.name, style = LocalOwliTypography.current.bodyStrong)
                    Text(money(bill.amount), style = LocalOwliTypography.current.screenTitle)
                    Text("Monthly • Next due: ${bill.nextDue.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}", style = LocalOwliTypography.current.caption)
                    Text(status.label, style = LocalOwliTypography.current.bodyStrong,
                        color = if (status == DemoBillStatus.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (!bill.paid) TextButton(onClick = { selected = bill.id }) { Text("Mark ${bill.name} as paid (demo)") }
                    else Text("Demo payment · Due date unchanged", style = LocalOwliTypography.current.caption)
                }
            }
        }
    }
    val bill = if (demo) bills.firstOrNull { it.id == selected && !it.paid } else null
    if (bill != null) AlertDialog(onDismissRequest = { selected = null }, title = { Text("Mark ${bill.name} as paid?") },
        text = { Text("${money(bill.amount)} · Demo only. No money is paid.") },
        confirmButton = { TextButton(onClick = { onMarkPaid(bill.id); selected = null }) { Text("Mark paid (demo)") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
}
