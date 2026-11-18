package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.ui.components.OwliBrand
import com.elect3.mobdev.personalfinance.ui.theme.*
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionDate
import kotlinx.coroutines.flow.Flow
import java.time.*

fun dateFromPicker(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
fun dateToPicker(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@Composable
fun AddTransactionDialog(
    repository: CategoryRepository,
    workflow: FormWorkflow,
    currency: String?,
    sessionKey: String?,
    sessionKeys: Flow<String?>,
    isCurrent: () -> Boolean,
    onExpired: () -> Unit,
    onClose: () -> Unit,
    onSaved: () -> Unit = {},
    /** Authenticated API creator; must be null for Mock Demo (the view model enforces this). */
    accountCreation: TransactionCreationRepository? = null,
) {
    val store = remember { ViewModelStore() }
    val model = remember {
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AddTransactionViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return AddTransactionViewModel(repository, workflow, currency, isCurrent, onExpired,
                    accountCreation = accountCreation) as T
            }
        }).get(AddTransactionViewModel::class.java)
    }
    DisposableEffect(store) { onDispose { store.clear() } }
    LaunchedEffect(model, sessionKeys) {
        if (workflow == FormWorkflow.Authenticated) sessionKeys.collect { if (it != sessionKey) model.invalidate() }
    }
    val state by model.state.collectAsStateWithLifecycle()
    AddTransactionCompletionEffects(model, state, onClose, onSaved)
    Dialog(onDismissRequest = model::back, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        BackHandler { model.back() }
        AddTransactionScreen(model, state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(model: AddTransactionViewModel, state: AddTransactionUiState) {
    var categoryOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val typography = LocalOwliTypography.current
    val shapes = LocalOwliShapes.current
    val colors = LocalOwliColors.current
    val editable = !state.inputLocked
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = model::back, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.form_back)) }
                    OwliBrand()
                }
                Text(stringResource(R.string.form_title), style = typography.screenTitle,
                    modifier = Modifier.semantics { heading() })
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val demo = model.workflow == FormWorkflow.MockDemo
                val offline = state.offlineSaveEnabled
                val account = state.accountSaveEnabled
                val uncertainRetry = state.saveState is FormSaveState.OutcomeUnknown && !state.unknownEditing
                Text(stringResource(if (state.completed) R.string.form_demo_complete else if (demo) R.string.form_demo_notice
                    else if (account) R.string.form_account_notice
                    else if (offline) R.string.form_offline_notice else R.string.form_save_unavailable),
                    style = typography.caption, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                when (state.saveState) {
                    is FormSaveState.Failed -> ErrorText(R.string.form_save_failed)
                    is FormSaveState.OutcomeUnknown -> {
                        ErrorText(R.string.form_outcome_unknown)
                        if (!state.unknownEditing) TextButton(onClick = model::requestUnknownEdit) { Text(stringResource(R.string.form_edit_unknown)) }
                    }
                    is FormSaveState.Succeeded -> Text(stringResource(if (account) R.string.form_account_success else R.string.form_offline_success))
                    else -> Unit
                }
                Button(onClick = { focus.clearFocus(); if (state.completed) model.back() else if (uncertainRetry) model.retryUnknown() else model.submit() },
                    enabled = state.completed || ((demo || offline || account) && !state.submitting && !state.invalidated &&
                        (uncertainRetry || (state.valid && editable))),
                    shape = shapes.button, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (state.completed) R.string.form_done else if (state.submitting && account) R.string.form_account_saving
                        else if (state.submitting && offline) R.string.form_offline_saving
                        else if (state.submitting) R.string.form_previewing else if (uncertainRetry) R.string.form_retry_unchanged
                        else if (demo) R.string.form_preview
                        else if (account) (if (state.type == TransactionType.Income) R.string.form_save_income else R.string.form_save_expense)
                        else if (offline) R.string.form_offline_save else R.string.form_save_unavailable_short))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (model.workflow == FormWorkflow.MockDemo) Text(stringResource(R.string.auth_demo_title), style = typography.caption)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransactionType.entries.forEachIndexed { index, type ->
                    SegmentedButton(selected = state.type == type, enabled = editable,
                        onClick = { focus.clearFocus(); model.type(type) }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                        Text(stringResource(if (type == TransactionType.Income) R.string.transaction_income else R.string.transaction_expense))
                    }
                }
            }
            Surface(shape = shapes.hero, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.form_amount), style = typography.label)
                    OutlinedTextField(state.amount, model::amount, enabled = editable, singleLine = true,
                        label = { Text(stringResource(R.string.form_amount)) },
                        textStyle = typography.heroAmount,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && state.amount.isNotEmpty()) model.touch(FormField.Amount) },
                        isError = FormField.Amount in state.touched && !state.amountValid,
                        supportingText = { if (FormField.Amount in state.touched && !state.amountValid) ErrorText(R.string.form_amount_error) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); model.touch(FormField.Amount) }))
                    Text(state.currency?.let { stringResource(if (model.workflow == FormWorkflow.MockDemo) R.string.form_demo_currency else R.string.form_currency, it) } ?: stringResource(R.string.form_currency_unavailable),
                        style = typography.caption)
                    if (state.amountValid && state.currency != null) Text(
                        formatTransactionAmount(java.math.BigDecimal(state.amount.replace(',', '.')), state.currency),
                        style = typography.bodyStrong)
                    Text(stringResource(if (state.type == TransactionType.Income) R.string.transaction_income else R.string.transaction_expense),
                        color = if (state.type == TransactionType.Income) colors.income else colors.expense, style = typography.bodyStrong)
                }
            }
            Surface(shape = shapes.summaryCard, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { focus.clearFocus(); model.touch(FormField.Category); categoryOpen = true }, enabled = editable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.form_category) + ": " +
                            (state.categories.firstOrNull { it.categoryId == state.selectedCategoryId }?.name ?: stringResource(R.string.form_choose_category)))
                    }
                    if (FormField.Category in state.touched && !state.categoryValid) ErrorText(R.string.form_category_error)
                    if (state.loading) Text(stringResource(R.string.form_categories_loading), style = typography.caption)
                    if (state.categoryError != null) Text(stringResource(R.string.form_categories_error), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { focus.clearFocus(); dateOpen = true }, enabled = editable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.form_date) + ": " + formatTransactionDate(state.date))
                    }
                    if (!state.dateValid) ErrorText(R.string.form_date_error)
                    OutlinedTextField(state.description, model::description, enabled = editable,
                        label = { Text(stringResource(R.string.form_description)) }, minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth(), isError = state.description.length > 200,
                        supportingText = {
                            Column {
                                Text(stringResource(R.string.form_description_count, state.description.length))
                                if (state.description.length > 200) ErrorText(R.string.form_description_error)
                            }
                        }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                }
            }
        }
    }
    if (categoryOpen) {
        AlertDialog(onDismissRequest = { categoryOpen = false }, title = { Text(stringResource(R.string.form_choose_category)) },
            confirmButton = { TextButton(onClick = { categoryOpen = false }) { Text(stringResource(R.string.form_done)) } },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.search, model::search, label = { Text(stringResource(R.string.form_search)) }, singleLine = true)
                    Text(stringResource(R.string.form_search_hint), style = typography.caption)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(state.visibleCategories, key = { it.categoryId }) { category ->
                            TextButton(onClick = { model.category(category.categoryId); categoryOpen = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(category.name, modifier = Modifier.fillMaxWidth().semantics { selected = category.categoryId == state.selectedCategoryId })
                            }
                        }
                        if (state.loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                        if (!state.loading && state.visibleCategories.isEmpty()) item { Text(stringResource(R.string.form_categories_empty)) }
                        if (state.categoryError != null) item {
                            Text(stringResource(R.string.form_categories_error), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = model::retryCategories) { Text(stringResource(R.string.transactions_retry)) }
                        } else if (state.canLoadMore) item { TextButton(onClick = model::loadCategories) { Text(stringResource(R.string.transactions_load_more)) } }
                    }
                }
            })
    }
    if (dateOpen) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = dateToPicker(state.date), yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { model.date(dateFromPicker(it)) }; dateOpen = false
            }) { Text(stringResource(R.string.form_done)) } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.form_cancel)) } }) {
            DatePicker(picker)
        }
    }
    if (state.discardConfirmation) AlertDialog(onDismissRequest = model::keepEditing,
        title = { Text(stringResource(R.string.form_discard_title)) }, text = { Text(stringResource(
            if (state.hasUnresolvedOutcome) R.string.form_discard_unknown else R.string.form_discard_message)) },
        confirmButton = { TextButton(onClick = model::discard) { Text(stringResource(R.string.form_discard)) } },
        dismissButton = { TextButton(onClick = model::keepEditing) { Text(stringResource(R.string.form_keep_editing)) } })
    if (state.unknownEditConfirmation) AlertDialog(onDismissRequest = model::cancelUnknownEdit,
        title = { Text(stringResource(R.string.form_edit_unknown)) },
        text = { Text(stringResource(R.string.form_edit_unknown_warning)) },
        confirmButton = { TextButton(onClick = model::acknowledgeUnknownEdit) { Text(stringResource(R.string.form_acknowledge_edit)) } },
        dismissButton = { TextButton(onClick = model::cancelUnknownEdit) { Text(stringResource(R.string.form_cancel)) } })
}
@Composable private fun ErrorText(id: Int) {
    Text(stringResource(id), color = MaterialTheme.colorScheme.error, style = LocalOwliTypography.current.caption,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
