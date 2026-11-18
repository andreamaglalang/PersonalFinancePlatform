package com.elect3.mobdev.personalfinance.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFilter
import com.elect3.mobdev.personalfinance.domain.repository.TransactionListSort
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.add.dateFromPicker
import com.elect3.mobdev.personalfinance.ui.transactions.add.dateToPicker
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException

/** Date-range presets. "This month"/"Last month" are computed from [today]; Custom uses explicit dates. */
enum class TransactionDatePreset { AllTime, ThisMonth, LastMonth, Custom }

internal fun presetRange(preset: TransactionDatePreset, today: LocalDate): Pair<LocalDate?, LocalDate?> = when (preset) {
    TransactionDatePreset.AllTime, TransactionDatePreset.Custom -> null to null
    TransactionDatePreset.ThisMonth -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
    TransactionDatePreset.LastMonth -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
}

internal fun presetOf(filter: TransactionFilter, today: LocalDate): TransactionDatePreset = when {
    filter.fromDate == null && filter.toDate == null -> TransactionDatePreset.AllTime
    presetRange(TransactionDatePreset.ThisMonth, today) == (filter.fromDate to filter.toDate) -> TransactionDatePreset.ThisMonth
    presetRange(TransactionDatePreset.LastMonth, today) == (filter.fromDate to filter.toDate) -> TransactionDatePreset.LastMonth
    else -> TransactionDatePreset.Custom
}

private enum class CategoryLoad { Loading, Ready, Failed }

/**
 * Owlight filter sheet. Only options the API supports end to end: type, one category, a date range and
 * date ordering. Nothing is applied until Apply; Cancel/dismiss discards the draft; Reset restores defaults.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionFilterSheet(
    current: TransactionFilter,
    categories: CategoryRepository?,
    today: LocalDate,
    onApply: (TransactionFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val typography = LocalOwliTypography.current
    val owli = LocalOwliColors.current
    val scheme = MaterialTheme.colorScheme
    var type by remember { mutableStateOf(current.type) }
    var categoryId by remember { mutableStateOf(current.categoryId) }
    var categoryName by remember { mutableStateOf(current.categoryName) }
    var preset by remember { mutableStateOf(presetOf(current, today)) }
    var customFrom by remember { mutableStateOf(if (presetOf(current, today) == TransactionDatePreset.Custom) current.fromDate else null) }
    var customTo by remember { mutableStateOf(if (presetOf(current, today) == TransactionDatePreset.Custom) current.toDate else null) }
    var sort by remember { mutableStateOf(current.sort) }
    var picking by remember { mutableStateOf<String?>(null) }

    var load by remember { mutableStateOf(CategoryLoad.Loading) }
    var options by remember { mutableStateOf(emptyList<Category>()) }
    var truncated by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(type, categories, reloadKey) {
        if (categories == null) { load = CategoryLoad.Failed; return@LaunchedEffect }
        load = CategoryLoad.Loading
        // Include inactive categories: older records may still use them.
        val result = try { categories.list(CategoryQuery(type = type, includeInactive = true, page = 1, pageSize = 100)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (result is FinanceResult.Success) {
            options = result.value.items.sortedBy { it.name.lowercase() }
            truncated = result.value.totalCount > result.value.items.size
            load = CategoryLoad.Ready
            // A selected category of the other type no longer applies.
            if (categoryId != null && options.none { it.categoryId == categoryId }) { categoryId = null; categoryName = null }
        } else load = CategoryLoad.Failed
    }

    val (presetFrom, presetTo) = presetRange(preset, today)
    val from = if (preset == TransactionDatePreset.Custom) customFrom else presetFrom
    val to = if (preset == TransactionDatePreset.Custom) customTo else presetTo
    val rangeInvalid = from != null && to != null && from.isAfter(to)

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = scheme.primary, selectedLabelColor = scheme.onPrimary,
        selectedLeadingIconColor = scheme.onPrimary, containerColor = scheme.surface, labelColor = scheme.onSurface)
    val chipBorder: @Composable (Boolean) -> BorderStroke = { selected ->
        BorderStroke(1.dp, if (selected) scheme.primary else owli.hairline)
    }
    val section: @Composable (Int) -> Unit = { label ->
        Text(stringResource(label), style = typography.overline, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).semantics { heading() })
    }
    val chip: @Composable (Boolean, String, () -> Unit) -> Unit = { selected, label, onClick ->
        FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = typography.label) },
            colors = chipColors, border = chipBorder(selected), shape = LocalOwliShapes.current.chip,
            modifier = Modifier.heightIn(min = 48.dp))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface, shape = LocalOwliShapes.current.sheetTop) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Text(stringResource(R.string.filter_title), style = typography.screenTitle, color = scheme.onSurface,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.filter_subtitle), style = typography.caption, color = scheme.onSurfaceVariant)

            section(R.string.filter_type)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chip(type == null, stringResource(R.string.filter_type_all)) { type = null }
                chip(type == TransactionType.Expense, stringResource(R.string.transaction_expense)) { type = TransactionType.Expense }
                chip(type == TransactionType.Income, stringResource(R.string.transaction_income)) { type = TransactionType.Income }
            }

            section(R.string.filter_date)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chip(preset == TransactionDatePreset.AllTime, stringResource(R.string.filter_date_all)) { preset = TransactionDatePreset.AllTime }
                chip(preset == TransactionDatePreset.ThisMonth, stringResource(R.string.filter_date_this_month)) { preset = TransactionDatePreset.ThisMonth }
                chip(preset == TransactionDatePreset.LastMonth, stringResource(R.string.filter_date_last_month)) { preset = TransactionDatePreset.LastMonth }
                chip(preset == TransactionDatePreset.Custom, stringResource(R.string.filter_date_custom)) { preset = TransactionDatePreset.Custom }
            }
            if (preset == TransactionDatePreset.Custom) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dateButton: @Composable (Modifier, Int, LocalDate?, String) -> Unit = { m, label, value, key ->
                        OutlinedButton(onClick = { picking = key }, modifier = m.heightIn(min = 52.dp),
                            shape = LocalOwliShapes.current.field, border = BorderStroke(1.dp, if (rangeInvalid) scheme.error else owli.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = owli.field, contentColor = scheme.onSurface)) {
                            Text(stringResource(label, value?.let { formatTransactionDate(it) } ?: stringResource(R.string.filter_date_any)),
                                style = typography.label)
                        }
                    }
                    dateButton(Modifier.weight(1f), R.string.filter_date_from, customFrom, "from")
                    dateButton(Modifier.weight(1f), R.string.filter_date_to, customTo, "to")
                }
                if (rangeInvalid) Text(stringResource(R.string.filter_date_invalid), style = typography.caption, color = scheme.error,
                    modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }

            section(R.string.filter_category)
            when (load) {
                CategoryLoad.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.primary)
                    Text(stringResource(R.string.form_categories_loading), style = typography.caption)
                }
                CategoryLoad.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.filter_category_error), style = typography.caption, color = scheme.error,
                        modifier = Modifier.weight(1f))
                    if (categories != null) TextButton(onClick = { reloadKey++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.transactions_retry))
                    }
                }
                CategoryLoad.Ready -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        chip(categoryId == null, stringResource(R.string.filter_category_all)) { categoryId = null; categoryName = null }
                        options.forEach { c ->
                            chip(categoryId == c.categoryId, if (c.isActive) c.name else stringResource(R.string.filter_category_inactive, c.name)) {
                                categoryId = c.categoryId; categoryName = c.name
                            }
                        }
                    }
                    if (truncated) Text(stringResource(R.string.filter_category_truncated), style = typography.caption,
                        color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }

            section(R.string.filter_sort)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chip(sort == TransactionListSort.Newest, stringResource(R.string.filter_sort_newest)) { sort = TransactionListSort.Newest }
                chip(sort == TransactionListSort.Oldest, stringResource(R.string.filter_sort_oldest)) { sort = TransactionListSort.Oldest }
            }
            Text(stringResource(R.string.filter_unsupported_note), style = typography.caption, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.filter_reset), color = owli.accent, style = typography.label)
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = LocalOwliShapes.current.button, border = BorderStroke(1.dp, owli.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface)) {
                    Text(stringResource(R.string.filter_cancel), style = typography.bodyStrong)
                }
                Button(onClick = {
                    onApply(TransactionFilter(type = type, categoryId = categoryId, categoryName = categoryName,
                        fromDate = from, toDate = to, sort = sort))
                }, enabled = !rangeInvalid && load != CategoryLoad.Loading,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = LocalOwliShapes.current.button) {
                    Text(stringResource(R.string.filter_apply), style = typography.bodyStrong)
                }
            }
        }
    }

    picking?.let { which ->
        val initial = (if (which == "from") customFrom else customTo) ?: today
        val picker = rememberDatePickerState(initialSelectedDateMillis = dateToPicker(initial), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { picking = null },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { millis -> if (which == "from") customFrom = dateFromPicker(millis) else customTo = dateFromPicker(millis) }
                picking = null
            }) { Text(stringResource(R.string.form_done)) } },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.form_cancel)) } }) {
            DatePicker(picker)
        }
    }
}
