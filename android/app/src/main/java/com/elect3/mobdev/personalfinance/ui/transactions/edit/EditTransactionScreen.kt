package com.elect3.mobdev.personalfinance.ui.transactions.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.currency.RateImpact
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionUpdateRepository
import com.elect3.mobdev.personalfinance.ui.auth.owliFieldColors
import com.elect3.mobdev.personalfinance.ui.components.OwliBrand
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliColors
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliShapes
import com.elect3.mobdev.personalfinance.ui.theme.LocalOwliTypography
import com.elect3.mobdev.personalfinance.ui.transactions.add.dateFromPicker
import com.elect3.mobdev.personalfinance.ui.transactions.add.dateToPicker
import com.elect3.mobdev.personalfinance.ui.transactions.currency.ConversionSummary
import com.elect3.mobdev.personalfinance.ui.transactions.currency.CurrencyPickerDialog
import com.elect3.mobdev.personalfinance.ui.transactions.currency.CurrencySelectorButton
import com.elect3.mobdev.personalfinance.ui.transactions.currency.formatRate
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionDate

/** Full-screen edit dialog. Owns its ViewModel; closes on Back, Done, invalidation or session change. */
@Composable
fun EditTransactionDialog(
    transaction: Transaction,
    baseCurrency: String?,
    updates: TransactionUpdateRepository,
    categories: CategoryRepository?,
    isCurrent: () -> Boolean,
    onExpired: () -> Unit,
    onClose: () -> Unit,
    /** Called once after a server-confirmed edit so lists and the Dashboard reload. */
    onSaved: () -> Unit,
) {
    val store = remember { ViewModelStore() }
    val model = remember {
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == EditTransactionViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return EditTransactionViewModel(transaction, baseCurrency, updates, categories, isCurrent, onExpired) as T
            }
        })[EditTransactionViewModel::class.java]
    }
    DisposableEffect(store) { onDispose { store.clear() } }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved != null) onSaved() }
    LaunchedEffect(state.invalidated) { if (state.invalidated) onClose() }
    val back = { if (!state.saving) onClose() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        BackHandler { back() }
        EditTransactionScreen(model, state, onClose = back)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(model: EditTransactionViewModel, state: EditTransactionUiState, onClose: () -> Unit) {
    var categoryOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    var currencyOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val colors = LocalOwliColors.current
    val income = state.original.type == TransactionType.Income
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.form_back)) }
                    OwliBrand()
                }
                Text(stringResource(R.string.fx_edit_title), style = typography.screenTitle, modifier = Modifier.semantics { heading() })
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = when {
                    state.locked -> stringResource(R.string.fx_edit_bill_locked)
                    state.saved != null -> stringResource(R.string.fx_edit_saved)
                    state.failure != null -> stringResource(when (state.failure) {
                        EditFailure.Offline -> R.string.fx_edit_failed_offline
                        EditFailure.Rejected -> R.string.fx_edit_failed_rejected
                        EditFailure.Conflict -> R.string.fx_edit_failed_conflict
                        EditFailure.NotFound -> R.string.fx_edit_failed_not_found
                        EditFailure.Unknown -> R.string.fx_edit_failed_unknown
                    })
                    !state.changed -> stringResource(R.string.fx_edit_no_changes)
                    else -> stringResource(R.string.fx_edit_online_only)
                }
                Text(status, style = typography.caption,
                    color = if (state.failure != null || state.locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = { focus.clearFocus(); if (state.saved != null) onClose() else model.submit() },
                    enabled = state.saved != null || state.canSave, shape = shapes.button,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Text(stringResource(if (state.saved != null) R.string.form_done else if (state.saving) R.string.fx_edit_saving else R.string.fx_edit_save))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.fx_edit_type_fixed, stringResource(if (income) R.string.transaction_income else R.string.transaction_expense)),
                color = if (income) colors.income else colors.expense, style = typography.bodyStrong)
            Surface(shape = shapes.hero, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.fx_amount_in, state.currency), style = typography.label, modifier = Modifier.weight(1f))
                        CurrencySelectorButton(state.currency, enabled = state.editable, onClick = { focus.clearFocus(); currencyOpen = true })
                    }
                    OutlinedTextField(state.amount, model::amount, enabled = state.editable, singleLine = true,
                        label = { Text(stringResource(R.string.form_amount)) },
                        prefix = { Text(SupportedCurrencies.symbol(state.currency) + " ", style = typography.amountMedium) },
                        suffix = { Text(state.currency, style = typography.label) },
                        textStyle = typography.heroAmount, shape = shapes.field, colors = owliFieldColors(),
                        modifier = Modifier.fillMaxWidth(), isError = !state.amountValid,
                        supportingText = { if (!state.amountValid) Text(stringResource(R.string.form_amount_error), color = MaterialTheme.colorScheme.error) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                    state.parsedAmount?.let { Text(formatTransactionAmount(it, state.currency), style = typography.bodyStrong) }
                }
            }
            RateImpactCard(state)
            state.saved?.let { saved ->
                Surface(Modifier.fillMaxWidth(), shape = shapes.group, color = colors.accentSoft, border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.fx_receipt_title), style = typography.label, color = colors.accent, modifier = Modifier.semantics { heading() })
                        ConversionSummary(saved, state.baseCurrency)
                    }
                }
            }
            Surface(shape = shapes.group, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, colors.hairline)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { focus.clearFocus(); categoryOpen = true }, enabled = state.editable && state.categories.isNotEmpty(),
                        shape = shapes.field, border = BorderStroke(1.dp, colors.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.field, contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.form_category) + ": " + state.categoryName)
                    }
                    if (state.categoriesLoading) Text(stringResource(R.string.form_categories_loading), style = typography.caption)
                    if (state.categoriesFailed) Text(stringResource(R.string.fx_edit_categories_error), style = typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { focus.clearFocus(); dateOpen = true }, enabled = state.editable,
                        shape = shapes.field, border = BorderStroke(1.dp, colors.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.field, contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.form_date) + ": " + formatTransactionDate(state.date))
                    }
                    OutlinedTextField(state.description, model::description, enabled = state.editable,
                        label = { Text(stringResource(R.string.form_description)) }, minLines = 2, maxLines = 5,
                        shape = shapes.field, colors = owliFieldColors(), modifier = Modifier.fillMaxWidth(), isError = !state.descriptionValid,
                        supportingText = { Text(stringResource(R.string.form_description_count, state.description.length)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                }
            }
        }
    }
    if (currencyOpen) CurrencyPickerDialog(state.currencyOptions, state.currency, state.baseCurrency,
        onPick = { model.currency(it); currencyOpen = false }, onDismiss = { currencyOpen = false })
    if (categoryOpen) AlertDialog(onDismissRequest = { categoryOpen = false }, title = { Text(stringResource(R.string.form_choose_category)) },
        confirmButton = { TextButton(onClick = { categoryOpen = false }) { Text(stringResource(R.string.form_done)) } },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(state.categories, key = { it.categoryId }) { category ->
                    TextButton(onClick = { model.category(category.categoryId); categoryOpen = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(category.name, modifier = Modifier.fillMaxWidth().semantics { selected = category.categoryId == state.categoryId })
                    }
                }
            }
        })
    if (dateOpen) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = dateToPicker(state.date), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { model.date(dateFromPicker(it)) }; dateOpen = false
            }) { Text(stringResource(R.string.form_done)) } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.form_cancel)) } }) { DatePicker(picker) }
    }
}

/** Explains, before saving, what the server will do with the stored exchange rate. */
@Composable
private fun RateImpactCard(state: EditTransactionUiState) {
    if (state.saved != null || state.locked) return
    val base = state.baseCurrency ?: return
    val colors = LocalOwliColors.current
    val typography = LocalOwliTypography.current
    val lines: List<String> = when (val impact = state.impact) {
        RateImpact.Unchanged -> if (state.original.currencyCode == base) emptyList() else listOf(
            stringResource(R.string.fx_rate, state.original.currencyCode, formatRate(state.original.fxRate), base),
            stringResource(R.string.fx_rate_date, formatTransactionDate(state.original.fxRateDate)))
        is RateImpact.KeepsStoredRate -> if (state.currency == base) emptyList() else listOf(
            stringResource(R.string.fx_edit_rate_keeps, state.currency, formatRate(impact.rate), base, formatTransactionDate(impact.rateDate)),
            stringResource(R.string.fx_edit_rate_expected, base, formatTransactionAmount(impact.expectedBase, base)))
        RateImpact.SameCurrency -> listOf(stringResource(R.string.fx_same_currency))
        RateImpact.NeedsNewRate -> listOf(stringResource(R.string.fx_edit_rate_new, formatTransactionDate(state.date)))
    }
    if (lines.isEmpty()) return
    Surface(Modifier.fillMaxWidth(), shape = LocalOwliShapes.current.group, color = colors.accentSoft,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f))) {
        Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEachIndexed { i, line -> Text(line, style = if (i == 0) typography.bodyStrong else typography.caption) }
        }
    }
}
