package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect3.mobdev.personalfinance.R
import com.elect3.mobdev.personalfinance.data.local.CategoryPresentationStore
import com.elect3.mobdev.personalfinance.data.mock.DemoLedger
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.Category
import com.elect3.mobdev.personalfinance.domain.repository.CategoryRepository
import com.elect3.mobdev.personalfinance.domain.repository.CategoryWriteRepository
import com.elect3.mobdev.personalfinance.domain.repository.TransactionCreationRepository
import com.elect3.mobdev.personalfinance.ui.categories.CategoryCatalogViewModel
import com.elect3.mobdev.personalfinance.ui.categories.CategoryChooserScreen
import com.elect3.mobdev.personalfinance.ui.categories.CategoryEditorScreen
import com.elect3.mobdev.personalfinance.ui.categories.CategoryEditorViewModel
import com.elect3.mobdev.personalfinance.ui.categories.CategorySettingsScreen
import com.elect3.mobdev.personalfinance.ui.categories.TransactionConfirmationScreen
import com.elect3.mobdev.personalfinance.ui.categories.TransactionReceipt
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionAmount
import com.elect3.mobdev.personalfinance.ui.transactions.formatTransactionDate
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

enum class FlowStart { AddTransaction, CategorySettings }

private sealed interface FlowStep {
    data class Choose(val type: TransactionType) : FlowStep
    data class Form(val category: Category, val key: Int) : FlowStep
    data class Confirm(val receipt: TransactionReceipt) : FlowStep
    data class Settings(val type: TransactionType) : FlowStep
    data class Editor(val category: Category?, val type: TransactionType, val key: Int) : FlowStep
}

/** Creates a view model owned by [store] (cleared when the composable leaves). */
@Composable
private inline fun <reified T : ViewModel> rememberOwned(store: ViewModelStore, crossinline create: () -> T): T = remember(store) {
    ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <V : ViewModel> create(modelClass: Class<V>): V {
            require(modelClass == T::class.java)
            @Suppress("UNCHECKED_CAST") return create() as V
        }
    })[T::class.java]
}

/**
 * Category-first Add Transaction and Category settings, as one full-screen flow:
 * Choose category → Form → Confirmation, and Choose/Dashboard → Category settings → Add/Edit category.
 * Mock Demo and signed-in accounts receive separate repositories from the owner; nothing falls back to mock data.
 */
@Composable
fun AddTransactionFlow(
    start: FlowStart,
    initialType: TransactionType?,
    categories: CategoryRepository,
    categoryWrites: CategoryWriteRepository?,
    presentation: CategoryPresentationStore,
    workflow: FormWorkflow,
    currency: String?,
    sessionKey: String?,
    sessionKeys: Flow<String?>,
    isCurrent: () -> Boolean,
    onExpired: () -> Unit,
    onClose: () -> Unit,
    /** Confirmed server save (authenticated) or a demo-session addition: reload the affected lists. */
    onSaved: () -> Unit,
    onShowDashboard: () -> Unit,
    accountCreation: TransactionCreationRepository? = null,
    /** Mock Demo only: in-memory session ledger that receives demo saves. */
    demoLedger: DemoLedger? = null,
    /** Optional session-bound active-category reader for chooser/form only; management stays online. */
    selectionCategories: CategoryRepository? = null,
    defaultCurrency: String? = null,
    reviewRateBeforeSave: Boolean = false,
    historicalQuotes: com.elect3.mobdev.personalfinance.domain.currency.HistoricalExchangeRateQuoteRepository? = null,
) {
    require(workflow == FormWorkflow.Authenticated || (accountCreation == null)) { "Mock Demo cannot save to an account" }
    val demo = workflow == FormWorkflow.MockDemo
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val catalog = rememberOwned(store) {
        CategoryCatalogViewModel(categories, categoryWrites, presentation, demo, isCurrent, onExpired)
    }
    val pickerStore = remember(selectionCategories) { ViewModelStore() }
    DisposableEffect(pickerStore) { onDispose { pickerStore.clear() } }
    val picker = if (selectionCategories == null) catalog else rememberOwned(pickerStore) {
        CategoryCatalogViewModel(selectionCategories, null, presentation, demo, isCurrent, onExpired, includeInactive = false)
    }
    val pickerState by picker.state.collectAsStateWithLifecycle()
    LaunchedEffect(catalog, picker, sessionKeys) {
        if (!demo) sessionKeys.collect { if (it != sessionKey) { catalog.invalidate(); if (picker !== catalog) picker.invalidate(); onClose() } }
    }
    val catalogState by catalog.state.collectAsStateWithLifecycle()
    var nextKey by remember { mutableIntStateOf(0) }
    val stack = remember {
        mutableStateListOf<FlowStep>(if (start == FlowStart.CategorySettings) FlowStep.Settings(initialType ?: TransactionType.Expense)
            else FlowStep.Choose(initialType ?: TransactionType.Expense))
    }
    fun pop() {
        val leavingSettings = stack.last() is FlowStep.Settings
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose()
        if (leavingSettings && picker !== catalog) picker.refresh()
    }
    fun replaceTop(step: FlowStep) { stack[stack.lastIndex] = step }
    var formBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var editorBack by remember { mutableStateOf<(() -> Unit)?>(null) }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val top = stack.last()
            BackHandler {
                when (top) {
                    is FlowStep.Form -> formBack?.invoke() ?: pop()
                    is FlowStep.Editor -> editorBack?.invoke() ?: pop()
                    is FlowStep.Confirm -> onClose()
                    else -> pop()
                }
            }
            when (top) {
                is FlowStep.Choose -> CategoryChooserScreen(picker, pickerState, top.type,
                    selectedId = null,
                    onTab = { replaceTop(FlowStep.Choose(it)) },
                    onPick = { if (it.isActive) stack.add(FlowStep.Form(it, ++nextKey)) },
                    onManage = { stack.add(FlowStep.Settings(top.type)) },
                    onCancel = { pop() })
                is FlowStep.Form -> key(top.key) {
                    FormStep(top.category, picker.iconKey(top.category), workflow, selectionCategories ?: categories, currency, isCurrent, onExpired,
                        accountCreation, demoLedger, defaultCurrency, reviewRateBeforeSave, historicalQuotes, registerBack = { formBack = it },
                        onLeave = { pop() }, onClose = onClose,
                        onCompleted = { receipt -> onSaved(); stack.clear(); stack.add(FlowStep.Confirm(receipt)) })
                }
                is FlowStep.Confirm -> TransactionConfirmationScreen(top.receipt,
                    onDashboard = { onShowDashboard(); onClose() },
                    onAnother = { stack.clear(); stack.add(FlowStep.Choose(top.receipt.type)) })
                is FlowStep.Settings -> CategorySettingsScreen(catalog, catalogState, top.type,
                    onTab = { replaceTop(FlowStep.Settings(it)) },
                    onBack = { pop() },
                    onAdd = { type -> if (categoryWrites != null) stack.add(FlowStep.Editor(null, type, ++nextKey)) },
                    onEdit = { c -> if (categoryWrites != null && !c.isSystemDefault) stack.add(FlowStep.Editor(c, c.type, ++nextKey)) })
                is FlowStep.Editor -> key(top.key) {
                    EditorStep(top, checkNotNull(categoryWrites), catalog, presentation, demo, isCurrent, onExpired,
                        registerBack = { editorBack = it },
                        onDone = { saved ->
                            if (saved != null) {
                                catalog.applySaved(saved, created = top.category == null)
                                // Return to the settings tab of the saved category's type.
                                stack.removeAt(stack.lastIndex)
                                (stack.lastOrNull() as? FlowStep.Settings)?.let { replaceTop(FlowStep.Settings(saved.type)) }
                            } else pop()
                        })
                }
            }
        }
    }
}

@Composable
private fun FormStep(
    category: Category, iconKey: String, workflow: FormWorkflow, categories: CategoryRepository, currency: String?,
    isCurrent: () -> Boolean, onExpired: () -> Unit, accountCreation: TransactionCreationRepository?, demoLedger: DemoLedger?, defaultCurrency: String?,
    reviewRateBeforeSave: Boolean, historicalQuotes: com.elect3.mobdev.personalfinance.domain.currency.HistoricalExchangeRateQuoteRepository?,
    registerBack: ((() -> Unit)?) -> Unit, onLeave: () -> Unit, onClose: () -> Unit, onCompleted: (TransactionReceipt) -> Unit,
) {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val model = rememberOwned(store) {
        AddTransactionViewModel(categories, workflow, currency, isCurrent, onExpired,
            accountCreation = if (workflow == FormWorkflow.Authenticated) accountCreation else null, defaultCurrency = defaultCurrency,
            reviewRateBeforeSave = workflow == FormWorkflow.Authenticated && reviewRateBeforeSave, historicalQuotes = historicalQuotes)
    }
    LaunchedEffect(model) { model.preselect(category) }
    val state by model.state.collectAsStateWithLifecycle()
    RateReviewDialog(state, model)
    var discardAsk by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        when {
            state.submitting || finished -> Unit
            // An unconfirmed server outcome keeps the existing warning and its discard path.
            state.hasUnresolvedOutcome -> model.back()
            state.hasUserInput -> discardAsk = true
            else -> onLeave()
        }
    }
    val currentLeave by rememberUpdatedState(leave)
    DisposableEffect(model) { registerBack { currentLeave() }; onDispose { registerBack(null) } }
    fun receipt(replayed: Boolean, queued: Boolean = false) = TransactionReceipt(state.type, category.name,
        formatTransactionAmount(BigDecimal(state.amount.replace(',', '.')), checkNotNull(state.currency)),
        formatTransactionDate(state.date), demo = workflow == FormWorkflow.MockDemo, replayed = replayed, queuedOffline = queued,
        converted = if (queued) null else state.savedTransaction, baseCurrency = state.baseCurrency,
        pendingConversion = queued && state.foreignCurrency)
    // Mock Demo: the preview completed locally; record it in the in-memory demo ledger only.
    LaunchedEffect(state.completed) {
        if (state.completed && !finished && workflow == FormWorkflow.MockDemo && isCurrent()) {
            finished = true
            demoLedger?.add(state.type, category, BigDecimal(state.amount.replace(',', '.')), state.date,
                state.description.trim().takeIf { it.isNotEmpty() })
            onCompleted(receipt(false))
        }
    }
    // Authenticated: only a server-confirmed creation shows the confirmation.
    LaunchedEffect(state.closeRequested) {
        if (!state.closeRequested || finished) return@LaunchedEffect
        val succeeded = state.saveState as? FormSaveState.Succeeded
        val queued = state.saveState == FormSaveState.QueuedOffline
        if ((succeeded != null || queued) && model.consumeSaveCompletion()) { finished = true; onCompleted(receipt(succeeded?.replayed ?: false, queued)) }
        else if (state.invalidated) onClose()
    }
    AddTransactionScreen(model, state, FixedCategory(category, iconKey, onChange = leave, onBack = leave))
    if (discardAsk) AlertDialog(onDismissRequest = { discardAsk = false },
        title = { Text(stringResource(R.string.tx_discard_title)) },
        text = { Text(stringResource(R.string.tx_discard_message)) },
        confirmButton = { TextButton(onClick = { discardAsk = false; onLeave() }) { Text(stringResource(R.string.form_discard)) } },
        dismissButton = { TextButton(onClick = { discardAsk = false }) { Text(stringResource(R.string.form_keep_editing)) } })
}

@Composable
private fun EditorStep(
    step: FlowStep.Editor, writes: CategoryWriteRepository, catalog: CategoryCatalogViewModel, presentation: CategoryPresentationStore,
    demo: Boolean, isCurrent: () -> Boolean, onExpired: () -> Unit,
    registerBack: ((() -> Unit)?) -> Unit, onDone: (Category?) -> Unit,
) {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val editor = rememberOwned(store) {
        CategoryEditorViewModel(writes, presentation, step.category, step.type,
            step.category?.let { presentation.icon(it.categoryId) ?: catalog.iconKey(it) }, catalog.state.value.all, isCurrent, onExpired)
    }
    DisposableEffect(editor) { registerBack(editor::back); onDispose { registerBack(null) } }
    val state by editor.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved, state.closeRequested) {
        if (state.saved != null) onDone(state.saved) else if (state.closeRequested) onDone(null)
    }
    CategoryEditorScreen(editor, state, demo)
}
