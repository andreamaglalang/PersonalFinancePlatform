package com.elect3.mobdev.personalfinance.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.repository.Budget
import com.elect3.mobdev.personalfinance.domain.repository.BudgetStatus
import com.elect3.mobdev.personalfinance.ui.auth.owliFieldColors
import com.elect3.mobdev.personalfinance.ui.categories.CategoryVisuals
import com.elect3.mobdev.personalfinance.ui.categories.categoryColor
import com.elect3.mobdev.personalfinance.ui.categories.isDarkSurface
import com.elect3.mobdev.personalfinance.ui.components.OwliEyeRing
import com.elect3.mobdev.personalfinance.ui.components.OwliMidnightPanel
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private fun money(v: BigDecimal, currency: String) = formatTransactionAmount(v, currency)
private val ALERT_CHOICES = listOf(50, 80, 100)

/**
 * Budgets tab content (month stepper + summary panel + cards + sheets), driven by [BudgetsViewModel].
 * [pendingLocalCount]: signed-in transactions saved on this device and not yet synced; the server's spent
 * figures cannot include them, so the section says so instead of guessing their effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsSection(state: BudgetsState, vm: BudgetsViewModel, demo: Boolean, today: LocalDate = LocalDate.now(), pendingLocalCount: Int = 0) {
    var openId by rememberSaveable { mutableStateOf<Int?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // Close sheets only on a NEW confirmed write or a month change; values are saved so rotation doesn't close them.
    var seenSaves by rememberSaveable { mutableIntStateOf(state.savedCount) }
    var seenMonth by rememberSaveable { mutableStateOf(state.month.toString()) }
    if (seenSaves != state.savedCount || seenMonth != state.month.toString()) {
        seenSaves = state.savedCount; seenMonth = state.month.toString(); openId = null; creating = false
    }
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val monthName = state.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val openCreate = { vm.clearFormError(); vm.consumeNotice(); creating = true }

    MonthStepper(state, vm)

    // Offline / failed refresh: keep the last figures, say when they were loaded, and pause editing.
    if (state.stale && state.loadedAt != null) StaleBanner(state, vm::refresh)

    // Confirmation at the top of the section, where it is seen (and announced) right after a change.
    state.notice?.let { notice ->
        // Stays until the next action (no timer), so the list never jumps under the user's finger.
        Text(stringResource(when (notice) {
            com.elect3.mobdev.personalfinance.ui.plan.BudgetNotice.Created -> R.string.budget_notice_created
            com.elect3.mobdev.personalfinance.ui.plan.BudgetNotice.Updated -> R.string.budget_notice_updated
            com.elect3.mobdev.personalfinance.ui.plan.BudgetNotice.Deleted -> R.string.budget_notice_deleted
            com.elect3.mobdev.personalfinance.ui.plan.BudgetNotice.DeleteBlocked -> R.string.budget_notice_delete_blocked
        }) + if (demo) " " + stringResource(R.string.budget_demo_suffix) else "",
            style = typography.label, color = scheme.onPrimary,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp).clip(RoundedCornerShape(14.dp))
                .background(scheme.primary).padding(horizontal = 14.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }

    when {
        state.loading -> Row(Modifier.fillMaxWidth().padding(24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.budget_loading), style = typography.caption, color = scheme.onSurfaceVariant)
        }
        state.error != null -> PlanEmpty(R.drawable.ic_cat_info, R.string.budget_error_title, R.string.budget_error_message) {
            OutlinedButton(onClick = vm::refresh, modifier = Modifier.padding(top = 10.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.transactions_retry)) }
        }
        state.budgets.isEmpty() -> PlanEmpty(R.drawable.ic_cat_wallet, R.string.budget_empty_title, R.string.budget_empty_message) {
            Button(onClick = openCreate, enabled = state.canWrite, shape = LocalOwliShapes.current.button,
                modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.budget_create)) }
        }
        else -> {
            val summary = budgetSummary(state.budgets, state.month, today)
            if (summary != null) SummaryPanel(summary, monthName)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.budget_list_title, monthName).uppercase(), style = typography.overline, color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = openCreate, enabled = state.canWrite, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_owli_plus), null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.budget_new), style = typography.label,
                        color = if (state.canWrite) LocalOwliColors.current.accent else scheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.budgets.forEach { b -> BudgetCard(b) { vm.clearFormError(); vm.consumeNotice(); openId = b.budgetId } }
            }
            Text(stringResource(if (demo) R.string.budget_note_demo else R.string.budget_note), style = typography.caption,
                color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
        }
    }
    if (!demo && pendingLocalCount > 0 && state.loaded) Text(
        pluralStringResource(R.plurals.budget_pending_local, pendingLocalCount, pendingLocalCount),
        style = typography.caption, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
    val open = openId?.let { id -> state.budgets.firstOrNull { it.budgetId == id } }
    if (open != null) BudgetDetailSheet(open, state, vm) { openId = null }
    if (creating) CreateBudgetSheet(state, vm) { creating = false }
}

@Composable
private fun SummaryPanel(s: BudgetSummary, monthName: String) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
        OwliMidnightPanel {
            Text(stringResource(R.string.budget_left_title).uppercase(), style = typography.overline, color = owli.onPanelMuted)
            Text((if (s.left.signum() < 0) "−" else "") + money(s.left.abs(), s.currency),
                style = typography.heroAmount.copy(fontSize = typography.heroAmount.fontSize * 0.72f), color = owli.onPanel, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.budget_used_of, money(s.totalSpent, s.currency), money(s.totalLimit, s.currency)) +
                (s.daysLeft?.let { " · " + stringResource(R.string.budget_days_left, it, monthName) } ?: ""),
                style = typography.caption, color = owli.onPanelMuted)
            val pct = (s.usedFraction * 100).toInt()
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                .semantics { contentDescription = "$pct%" }) {
                Box(Modifier.fillMaxWidth(s.usedFraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(owli.panelIncome))
            }
            s.perDay?.let { Text(stringResource(R.string.budget_per_day, money(it, s.currency)), style = typography.caption,
                color = owli.onPanelMuted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
        }
    }
}

@Composable
private fun statusText(b: Budget): String = when (b.status) {
    BudgetStatus.Exceeded -> stringResource(R.string.budget_status_over, money(b.remainingAmount.abs(), b.baseCurrency))
    BudgetStatus.Warning -> stringResource(R.string.budget_status_near, money(b.remainingAmount.max(BigDecimal.ZERO), b.baseCurrency))
    BudgetStatus.OK -> stringResource(R.string.budget_status_ok, money(b.remainingAmount, b.baseCurrency))
}

@Composable
private fun statusColor(b: Budget): Color = when (b.status) {
    BudgetStatus.Exceeded -> MaterialTheme.colorScheme.error
    BudgetStatus.Warning -> LocalOwliColors.current.accent
    BudgetStatus.OK -> LocalOwliColors.current.income
}

@Composable
private fun CategoryTile(b: Budget) {
    val hex = CategoryVisuals.palette[Math.floorMod(b.categoryId, CategoryVisuals.palette.size)].hex
    val base = categoryColor(hex)
    val dark = isDarkSurface()
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
        .background(androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, base, if (dark) 0.24f else 0.15f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(CategoryVisuals.icon(CategoryVisuals.defaultIconKey(b.categoryName, com.elect3.mobdev.personalfinance.domain.model.TransactionType.Expense)).drawable),
            null, tint = if (dark) androidx.compose.ui.graphics.lerp(base, Color.White, 0.52f) else androidx.compose.ui.graphics.lerp(base, MaterialTheme.colorScheme.onSurface, 0.22f),
            modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun BudgetCard(b: Budget, onOpen: () -> Unit) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val pct = percentUsed(b)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, owli.hairline)) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryTile(b)
                Column(Modifier.weight(1f)) {
                    Text(b.categoryName, style = typography.bodyStrong)
                    Text(stringResource(R.string.budget_spent_of, money(b.spentAmount, b.baseCurrency), money(b.limitAmount, b.baseCurrency)),
                        style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$pct%", style = typography.amountRow)
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape).background(owli.hairline)) {
                Box(Modifier.fillMaxWidth((pct / 100f).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape)
                    .background(if (b.status == BudgetStatus.OK) owli.accent.copy(alpha = 0.45f) else statusColor(b)))
            }
            Text(statusText(b), style = typography.label, color = statusColor(b), modifier = Modifier.padding(top = 8.dp))
            if (!b.isCategoryActive) Text(stringResource(R.string.budget_inactive_category), style = typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDetailSheet(b: Budget, state: BudgetsState, vm: BudgetsViewModel, onClose: () -> Unit) {
    var limit by rememberSaveable(b.budgetId) { mutableStateOf(b.limitAmount.setScale(2).toPlainString()) }
    var alert by rememberSaveable(b.budgetId) { mutableIntStateOf(b.alertThresholdPct) }
    var confirmDelete by remember { mutableStateOf(false) }
    val typography = LocalOwliTypography.current
    ModalBottomSheet(onDismissRequest = { if (!state.saving) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.budget_detail_title, b.categoryName), style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // On the light/dark sheet surface (the ring's defaults are for the midnight panel).
                OwliEyeRing((percentUsed(b) / 100f).coerceIn(0f, 1f), statusColor(b), "${percentUsed(b)}%", size = 96.dp,
                    labelColor = MaterialTheme.colorScheme.onSurface, trackColor = LocalOwliColors.current.hairline)
            }
            Rows(listOf(
                stringResource(R.string.budget_row_spent) to money(b.spentAmount, b.baseCurrency),
                stringResource(R.string.budget_row_limit) to money(b.limitAmount, b.baseCurrency),
                stringResource(if (b.remainingAmount.signum() < 0) R.string.budget_row_over else R.string.budget_row_left) to money(b.remainingAmount.abs(), b.baseCurrency),
                stringResource(R.string.budget_row_alert) to stringResource(R.string.budget_alert_value, b.alertThresholdPct)))
            Text(statusText(b) + ". " + stringResource(R.string.budget_alert_note), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LimitField(limit, { limit = it; vm.clearFormError() }, R.string.budget_change_limit, b.baseCurrency)
            AlertChoices(alert) { alert = it }
            FormError(state.formError)
            if (state.stale) Text(stringResource(R.string.budget_stale_readonly), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.update(b.budgetId, limit, alert) }, enabled = state.canWrite, shape = LocalOwliShapes.current.button,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (state.saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }
                Text(stringResource(R.string.budget_save), style = typography.button)
            }
            TextButton(onClick = { confirmDelete = true }, enabled = state.canWrite, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.budget_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.budget_delete_title, b.categoryName)) },
        text = { Text(stringResource(R.string.budget_delete_message)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(b.budgetId) }) { Text(stringResource(R.string.budget_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.account_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateBudgetSheet(state: BudgetsState, vm: BudgetsViewModel, onClose: () -> Unit) {
    var categoryId by rememberSaveable { mutableStateOf<Int?>(null) }
    var limit by rememberSaveable { mutableStateOf("") }
    var alert by rememberSaveable { mutableIntStateOf(80) }
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    ModalBottomSheet(onDismissRequest = { if (!state.saving) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.budget_new_title), style = typography.sectionTitle, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.budget_category).uppercase(), style = typography.overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.categoriesFailed) Text(stringResource(R.string.budget_categories_failed), style = typography.caption, color = MaterialTheme.colorScheme.error)
            else if (state.availableCategories.isEmpty()) Text(stringResource(R.string.budget_no_categories), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.availableCategories.forEach { c ->
                    val on = categoryId == c.categoryId
                    Text(c.name, style = typography.label, modifier = Modifier.clip(CircleShape)
                        .background(if (on) owli.accentSoft else owli.field)
                        .border(if (on) 1.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else owli.hairline, CircleShape)
                        .clickable(role = Role.RadioButton) { categoryId = c.categoryId; vm.clearFormError() }
                        .semantics { selected = on }.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            LimitField(limit, { limit = it; vm.clearFormError() }, R.string.budget_limit, state.formCurrency)
            AlertChoices(alert) { alert = it }
            FormError(state.formError)
            if (state.stale) Text(stringResource(R.string.budget_stale_readonly), style = typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.create(categoryId, limit, alert) }, enabled = state.canWrite && state.availableCategories.isNotEmpty(),
                shape = LocalOwliShapes.current.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (state.saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }
                Text(stringResource(R.string.budget_create), style = typography.button)
            }
            OutlinedButton(onClick = onClose, enabled = !state.saving, shape = LocalOwliShapes.current.button, border = BorderStroke(1.dp, owli.hairline),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.account_cancel)) }
        }
    }
}

@Composable
private fun LimitField(value: String, onValue: (String) -> Unit, label: Int, currency: String?) {
    var refusal by remember { mutableStateOf<Int?>(null) }
    OutlinedTextField(value, amountEdit({ refusal = it }, onValue), singleLine = true, label = { Text(stringResource(label)) },
        supportingText = refusal?.let { { InputRefusal(it) } },
        prefix = currency?.let { c -> { Text("$c ") } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = LocalOwliShapes.current.field, colors = owliFieldColors(), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AlertChoices(selected: Int, onSelect: (Int) -> Unit) {
    val owli = LocalOwliColors.current
    Text(stringResource(R.string.budget_alert_label).uppercase(), style = LocalOwliTypography.current.overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(owli.field).border(1.dp, owli.hairline, CircleShape).padding(3.dp)) {
        // A server value other than 50/80/100 is shown as its own choice so it is never silently changed.
        (ALERT_CHOICES + listOf(selected).filter { it !in ALERT_CHOICES }).sorted().forEach { v ->
            val on = selected == v
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(role = Role.RadioButton) { onSelect(v) }.semantics { this.selected = on }, contentAlignment = Alignment.Center) {
                Text("$v%", style = LocalOwliTypography.current.label, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FormError(error: BudgetFormError?) {
    error ?: return
    Text(stringResource(when (error) {
        BudgetFormError.Amount -> R.string.budget_error_amount
        BudgetFormError.Category -> R.string.budget_error_category
        BudgetFormError.Duplicate -> R.string.budget_error_duplicate
        BudgetFormError.Rejected -> R.string.budget_error_rejected
        BudgetFormError.Unknown -> R.string.budget_error_unknown
        BudgetFormError.NotFound -> R.string.budget_error_not_found
        BudgetFormError.Failed -> R.string.budget_error_failed
        BudgetFormError.Changed -> R.string.budget_error_changed
        BudgetFormError.Stale -> R.string.budget_stale_readonly
    }), style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    Surface(shape = LocalOwliShapes.current.group, border = BorderStroke(1.dp, LocalOwliColors.current.hairline), color = MaterialTheme.colorScheme.surface) {
        Column {
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = LocalOwliColors.current.hairline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}) {
                    Text(k, style = LocalOwliTypography.current.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = LocalOwliTypography.current.amountRow, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Previous / next month, within the ViewModel's range; "This month" returns to the current month. */
@Composable
private fun MonthStepper(state: BudgetsState, vm: BudgetsViewModel, current: YearMonth = YearMonth.now()) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val scheme = MaterialTheme.colorScheme
    val label = state.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + state.month.year
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp).clip(RoundedCornerShape(16.dp))
        .background(owli.field).border(1.dp, owli.hairline, RoundedCornerShape(16.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.selectMonth(state.month.minusMonths(1)) }, enabled = state.canGoBack) {
            Icon(painterResource(R.drawable.ic_cat_chevl), stringResource(R.string.budget_month_previous), modifier = Modifier.size(20.dp),
                tint = if (state.canGoBack) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = typography.bodyStrong, textAlign = TextAlign.Center)
            if (state.month != current) Text(stringResource(if (state.month < current) R.string.budget_month_past else R.string.budget_month_future),
                style = typography.caption, color = scheme.onSurfaceVariant)
        }
        if (state.month != current && current in state.minMonth..state.maxMonth) TextButton(onClick = { vm.selectMonth(current) },
            enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.budget_month_current), style = typography.label, color = owli.accent)
        }
        IconButton(onClick = { vm.selectMonth(state.month.plusMonths(1)) }, enabled = state.canGoForward) {
            Icon(painterResource(R.drawable.ic_cat_chevr), stringResource(R.string.budget_month_next), modifier = Modifier.size(20.dp),
                tint = if (state.canGoForward) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

/** Shown when a reload failed but earlier figures for this month are still on screen. */
@Composable
private fun StaleBanner(state: BudgetsState, onRetry: () -> Unit) {
    val owli = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val time = state.loadedAt!!.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    val offline = state.staleReason == com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.Unavailable ||
        state.staleReason == com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure.Timeout
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp).clip(RoundedCornerShape(14.dp))
        .background(owli.accentSoft).border(1.dp, owli.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (offline) R.drawable.ic_cat_wifi else R.drawable.ic_cat_info), null, tint = owli.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Text(stringResource(if (offline) R.string.budget_stale_offline else R.string.budget_stale_failed, time),
            style = typography.caption, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.transactions_retry), style = typography.label, color = owli.accent)
        }
    }
}
