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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.elect3.mobdev.personalfinance.R
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
internal fun PlanCard(content: @Composable ColumnScope.() -> Unit) {
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
            PlanNotice(stringResource(R.string.demo_goals_account_title), stringResource(R.string.demo_goals_account_message))
        } else {
            PlanNotice(stringResource(R.string.demo_goals_title), stringResource(R.string.demo_goals_subtitle))
            if (goals.isEmpty()) PlanNotice(stringResource(R.string.demo_goals_empty_title), stringResource(R.string.demo_goals_empty_message))
            goals.forEach { goal ->
                PlanCard {
                    Text(goal.name, style = LocalOwliTypography.current.bodyStrong)
                    val percent = (goal.progress * 100).toInt()
                    val progressLabel = stringResource(R.string.goal_progress_description, money(goal.saved), money(goal.target), percent.toString())
                    Text(stringResource(R.string.goal_saved_of, money(goal.saved), money(goal.target)), style = LocalOwliTypography.current.body)
                    LinearProgressIndicator(progress = { goal.progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel })
                    Text(stringResource(R.string.demo_goal_progress, percent, money(goal.remaining)), style = LocalOwliTypography.current.caption)
                    TextButton(onClick = { selected = goal.id }) { Text(stringResource(R.string.demo_goal_details, goal.name)) }
                }
            }
        }
    }
    val goal = if (demo) goals.firstOrNull { it.id == selected } else null
    if (goal != null) AlertDialog(onDismissRequest = { selected = null }, title = { Text(goal.name) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.demo_goal_notice))
            Text(stringResource(R.string.demo_goal_target, money(goal.target)))
            Text(stringResource(R.string.demo_goal_saved, money(goal.saved)))
            Text(stringResource(R.string.demo_goal_remaining, money(goal.remaining)))
            Text(stringResource(R.string.demo_goal_progress_value, (goal.progress * 100).toInt()))
            if (goal.remaining.signum() == 0) Text(stringResource(R.string.demo_goal_reached))
        } },
        confirmButton = { TextButton(enabled = goal.remaining.signum() > 0, onClick = { onContribute(goal.id) }) {
            Text(stringResource(R.string.demo_goal_add, money(BigDecimal("500").min(goal.remaining))))
        } }, dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.plan_close)) } })
}

@Composable
fun BillsSection(demo: Boolean, bills: List<DemoBill>, onMarkPaid: (Int) -> Unit, today: LocalDate = LocalDate.now()) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!demo) {
            PlanNotice(stringResource(R.string.demo_bills_account_title), stringResource(R.string.demo_bills_account_message))
        } else {
            PlanNotice(stringResource(R.string.demo_bills_title), stringResource(R.string.demo_bills_subtitle))
            if (bills.isEmpty()) PlanNotice(stringResource(R.string.demo_bills_empty_title), stringResource(R.string.demo_bills_empty_message))
            bills.sortedBy { it.nextDue }.forEach { bill ->
                val status = bill.status(today)
                PlanCard {
                    Text(bill.name, style = LocalOwliTypography.current.bodyStrong)
                    Text(money(bill.amount), style = LocalOwliTypography.current.screenTitle)
                    Text(stringResource(R.string.demo_bill_due, bill.nextDue.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))), style = LocalOwliTypography.current.caption)
                    Text(stringResource(when (status) { DemoBillStatus.Upcoming -> R.string.bill_status_upcoming; DemoBillStatus.DueSoon -> R.string.bill_status_due_soon; DemoBillStatus.Overdue -> R.string.bill_status_overdue; DemoBillStatus.Paid -> R.string.demo_bill_status_paid }), style = LocalOwliTypography.current.bodyStrong,
                        color = if (status == DemoBillStatus.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (!bill.paid) TextButton(onClick = { selected = bill.id }) { Text(stringResource(R.string.demo_bill_mark, bill.name)) }
                    else Text(stringResource(R.string.demo_bill_paid_note), style = LocalOwliTypography.current.caption)
                }
            }
        }
    }
    val bill = if (demo) bills.firstOrNull { it.id == selected && !it.paid } else null
    if (bill != null) AlertDialog(onDismissRequest = { selected = null }, title = { Text(stringResource(R.string.demo_bill_mark_title, bill.name)) },
        text = { Text(stringResource(R.string.demo_bill_mark_message, money(bill.amount))) },
        confirmButton = { TextButton(onClick = { onMarkPaid(bill.id); selected = null }) { Text(stringResource(R.string.demo_bill_mark_confirm)) } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.demo_cancel)) } })
}
