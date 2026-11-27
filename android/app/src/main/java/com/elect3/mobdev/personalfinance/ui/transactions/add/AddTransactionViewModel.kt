package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.*
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.elect3.mobdev.personalfinance.data.remote.TransactionCreationCoordinator

enum class FormWorkflow { Authenticated, MockDemo }
enum class FormField { Amount, Category, Date, Description }

/** Draft lives in memory only, and never contains authentication or account identifiers. */
data class AddTransactionUiState(
    val type: TransactionType = TransactionType.Expense,
    val amount: String = "",
    val currency: String? = null,
    val date: LocalDate,
    val initialDate: LocalDate,
    val description: String = "",
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Int? = null,
    val search: String = "",
    val page: Int = 0,
    val total: Long = 0,
    val loading: Boolean = false,
    val categoryError: FinanceFailure? = null,
    val touched: Set<FormField> = emptySet(),
    val submitting: Boolean = false,
    val completed: Boolean = false,
    val discardConfirmation: Boolean = false,
    val closeRequested: Boolean = false,
    val invalidated: Boolean = false,
    val offlineSaveEnabled: Boolean = false,
    /** True only when an authenticated account creator was injected; saves go to the server. */
    val accountSaveEnabled: Boolean = false,
    val saveState: FormSaveState = FormSaveState.Idle,
    val unknownEditing: Boolean = false,
    val unknownEditConfirmation: Boolean = false,
    val hasUnresolvedOutcome: Boolean = false,
    /** The signed-in account's base currency (the form's default); amounts in other currencies are converted to it by the API. */
    val baseCurrency: String? = null,
    val initialCurrency: String? = baseCurrency,
    /** True only for signed-in forms; Mock Demo data is PHP only. */
    val currencySelectable: Boolean = false,
    /** The server-confirmed row after a successful save, carrying the API's authoritative rate, rate date and base amount. */
    val rateReview: com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuote? = null,
    val rateLoading: Boolean = false,
    val rateError: Boolean = false,
    val rateFailure: FinanceFailure? = null,
    val savedTransaction: com.elect3.mobdev.personalfinance.domain.model.Transaction? = null,
) {
    val amountValid get() = validAmount(amount)
    val dateValid get() = date.year in 1..9999
    val categoryValid get() = categories.any { it.categoryId == selectedCategoryId && it.type == type && it.isActive }
    val currencyValid get() = currency != null && (currency == baseCurrency ||
        (currencySelectable && com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies.isSupported(currency)))
    /** A currency other than the account's base currency: the API converts it when the transaction is saved. */
    val foreignCurrency get() = currency != null && baseCurrency != null && currency != baseCurrency
    val valid get() = amountValid && dateValid && categoryValid && description.length <= 200 && currencyValid && !invalidated
    val dirty get() = amount.isNotEmpty() || description.isNotEmpty() || selectedCategoryId != null || date != initialDate ||
        type != TransactionType.Expense || currency != initialCurrency
    val canLoadMore get() = page > 0 && categories.size.toLong() < total && !loading
    val visibleCategories get() = categories.filter { it.name.contains(search, ignoreCase = true) }
    val inputLocked get() = submitting || completed || invalidated || saveState is FormSaveState.Succeeded || saveState == FormSaveState.QueuedOffline ||
        (saveState is FormSaveState.OutcomeUnknown && !unknownEditing)
}
/** The same amount rules as every other amount field on the device (see [com.elect3.mobdev.personalfinance.ui.plan.typedAmount]). */
fun validAmount(raw: String): Boolean = com.elect3.mobdev.personalfinance.ui.plan.positiveAmount(raw) != null

/**
 * The specific amount problem to show, or null. Save stays disabled while the amount is invalid, so the reason is shown
 * as soon as it cannot become valid by typing more; "greater than zero" waits until the field is left (typing "0.5").
 */
fun addAmountProblem(raw: String, touched: Boolean): Int? {
    if (validAmount(raw) || (raw.isEmpty() && !touched)) return null
    val problem = com.elect3.mobdev.personalfinance.ui.plan.amountProblem(raw) ?: com.elect3.mobdev.personalfinance.R.string.form_amount_error
    return problem.takeIf { touched || it != com.elect3.mobdev.personalfinance.R.string.amount_error_positive }
}

/** True when the user typed something beyond the chosen category (amount, note or a different date). */
val AddTransactionUiState.hasUserInput get() = amount.isNotEmpty() || description.isNotEmpty() || date != initialDate || currency != initialCurrency

class AddTransactionViewModel(
    private val repository: CategoryRepository,
    val workflow: FormWorkflow,
    currency: String?,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
    clock: Clock = Clock.systemDefaultZone(),
    /** Authenticated API creator. Null keeps saving unavailable; Mock Demo may never receive one. */
    accountCreation: TransactionCreationRepository? = null,
    defaultCurrency: String? = null,
    private val reviewRateBeforeSave: Boolean = false,
    private val historicalQuotes: com.elect3.mobdev.personalfinance.domain.currency.HistoricalExchangeRateQuoteRepository? = null,
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val base = currency?.takeIf { it.matches(Regex("[A-Z]{3}")) }
    private val initialCurrency = defaultCurrency?.takeIf { workflow == FormWorkflow.Authenticated && com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies.isSupported(it) } ?: base
    private val mutable = MutableStateFlow(AddTransactionUiState(currency = initialCurrency, initialCurrency = initialCurrency, baseCurrency = base,
        currencySelectable = workflow == FormWorkflow.Authenticated && base != null, date = today, initialDate = today))
    val state = mutable.asStateFlow()
    private var quoteJob: Job? = null
    private var reviewedDraft: TransactionDraft? = null
    private var reviewedAt = 0L
    private var quoteGeneration = 0L
    private var generation = 0L
    private var request: Job? = null
    private var preview: Job? = null
    private var creation: TransactionCreationCoordinator? = null
    private var lastSubmitted: TransactionDraft? = null
    private var acknowledgedUnknown = false
    private var savedNavigationHandled = false
    init {
        require(accountCreation == null || workflow == FormWorkflow.Authenticated) { "Mock Demo cannot save to an account" }
        if (accountCreation != null) {
            // Same coordinator, idempotency and expiry handling as the verified harness path.
            creation = TransactionCreationCoordinator(accountCreation, isSessionCurrent, onSessionExpired = {
                if (isSessionCurrent()) { invalidate(); onSessionExpired() }
            })
            mutable.value = mutable.value.copy(accountSaveEnabled = true)
        }
        loadCategories()
    }

    private fun editable() = !mutable.value.inputLocked && isSessionCurrent()

    /** Only test/preview hosts call this; runtime injects only the account creator through the constructor. */
    internal fun enableOfflineSave(workflow: OfflineSaveWorkflow) {
        require(this.workflow == FormWorkflow.Authenticated && creation == null && !mutable.value.invalidated)
        creation = workflow.coordinator(isSessionCurrent) {
            if (isSessionCurrent()) { invalidate(); onSessionExpired() }
        }
        mutable.value = mutable.value.copy(offlineSaveEnabled = true)
    }

    private fun mappedDraft(): TransactionDraft? {
        val s = mutable.value
        if (!s.valid) return null
        return TransactionDraft(s.type, checkNotNull(s.selectedCategoryId), checkNotNull(com.elect3.mobdev.personalfinance.ui.plan.typedAmount(s.amount)),
            checkNotNull(s.currency), s.date, s.description.takeIf { it.isNotBlank() })
    }

    fun requestUnknownEdit() {
        if (mutable.value.saveState is FormSaveState.OutcomeUnknown && !mutable.value.submitting)
            mutable.value = mutable.value.copy(unknownEditConfirmation = true)
    }
    fun cancelUnknownEdit() { mutable.value = mutable.value.copy(unknownEditConfirmation = false) }
    fun acknowledgeUnknownEdit() {
        if (mutable.value.unknownEditConfirmation) {
            acknowledgedUnknown = true
            mutable.value = mutable.value.copy(unknownEditConfirmation = false, unknownEditing = true)
        }
    }
    fun retryUnknown() {
        if (mutable.value.saveState !is FormSaveState.OutcomeUnknown || mutable.value.unknownEditing || mutable.value.submitting) return
        lastSubmitted?.let { submitWithCreator(it) }
    }
    /** Shared by the authenticated account creator and the test-only offline harness. */
    private fun submitWithCreator(draft: TransactionDraft) {
        val coordinator = creation ?: return
        if (mutable.value.submitting || mutable.value.invalidated || !isSessionCurrent()) return
        lastSubmitted = draft
        mutable.value = mutable.value.copy(submitting = true, saveState = FormSaveState.Submitting, discardConfirmation = false)
        preview = viewModelScope.launch {
            try {
                val result = coordinator.submit(draft, acknowledgedUnknown)
                ensureActive()
                if (!isSessionCurrent() || mutable.value.invalidated) { invalidate(); return@launch }
                val unresolved = coordinator.unresolvedClientIds.isNotEmpty()
                mutable.value = when (result) {
                    is CreationResult.Success -> mutable.value.copy(submitting = false, savedTransaction = result.transaction,
                        saveState = FormSaveState.Succeeded(result.replayed), closeRequested = true, hasUnresolvedOutcome = unresolved)
                    is CreationResult.UnknownOutcome -> {
                        if (result.sessionInvalidated) { invalidate(); return@launch }
                        acknowledgedUnknown = false
                        mutable.value.copy(submitting = false, saveState = FormSaveState.OutcomeUnknown(result.reason),
                            unknownEditing = false, hasUnresolvedOutcome = true)
                    }
                    is CreationResult.Queued -> mutable.value.copy(submitting = false, saveState = FormSaveState.QueuedOffline,
                        closeRequested = true, hasUnresolvedOutcome = false)
                    is CreationResult.Failure -> {
                        if (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired) {
                            invalidate(); onSessionExpired(); return@launch
                        }
                        mutable.value.copy(submitting = false, saveState = FormSaveState.Failed(result.reason), hasUnresolvedOutcome = unresolved)
                    }
                    else -> mutable.value.copy(submitting = false, saveState = FormSaveState.Failed(FinanceFailure.Conflict), hasUnresolvedOutcome = unresolved)
                }
            } catch (cancelled: CancellationException) {
                if (!mutable.value.invalidated && isSessionCurrent()) mutable.value = mutable.value.copy(submitting = false,
                    saveState = FormSaveState.OutcomeUnknown(UnknownCreationReason.Interrupted), unknownEditing = false, hasUnresolvedOutcome = true)
                throw cancelled
            }
        }
    }
    internal fun consumeSaveCompletion(): Boolean {
        val done = mutable.value.saveState is FormSaveState.Succeeded || mutable.value.saveState == FormSaveState.QueuedOffline
        if (mutable.value.invalidated || !isSessionCurrent() || !done || savedNavigationHandled) return false
        savedNavigationHandled = true
        return true
    }
    fun touch(field: FormField) { if (editable()) mutable.value = mutable.value.copy(touched = mutable.value.touched + field) }
    fun amount(value: String) { if (editable()) mutable.value = mutable.value.copy(amount = value) }
    /** Signed-in forms only, and only a currency the form offers (the account base currency or a supported one). */
    fun currency(code: String) {
        val s = mutable.value
        if (!editable() || !s.currencySelectable) return
        if (com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies.options(s.baseCurrency).none { it.code == code }) return
        mutable.value = s.copy(currency = code)
    }
    fun description(value: String) { if (editable()) mutable.value = mutable.value.copy(description = value) }
    fun date(value: LocalDate) { if (editable()) mutable.value = mutable.value.copy(date = value, touched = mutable.value.touched + FormField.Date) }
    fun search(value: String) { if (editable()) mutable.value = mutable.value.copy(search = value) }
    fun category(id: Int) {
        if (editable() && mutable.value.categories.any { it.categoryId == id && it.type == mutable.value.type && it.isActive })
            mutable.value = mutable.value.copy(selectedCategoryId = id, touched = mutable.value.touched + FormField.Category)
    }
    /**
     * Category-first flow: the category was chosen on the picker screen. Applies its type and selects it.
     * Only active categories are accepted (the API rejects inactive ones for new transactions).
     */
    fun preselect(category: Category) {
        if (!editable() || !category.isActive) return
        if (mutable.value.type != category.type) type(category.type)
        val merged = (listOf(category) + mutable.value.categories).distinctBy { it.categoryId }
        mutable.value = mutable.value.copy(categories = Collections.unmodifiableList(merged), selectedCategoryId = category.categoryId)
    }
    fun type(value: TransactionType) {
        if (!editable() || mutable.value.type == value) return
        generation++; request?.cancel()
        mutable.value = mutable.value.copy(type = value, selectedCategoryId = null, categories = emptyList(),
            page = 0, total = 0, search = "", categoryError = null, loading = false)
        loadCategories()
    }
    fun loadCategories() {
        if (!editable() || mutable.value.loading) return
        if (mutable.value.page > 0 && !mutable.value.canLoadMore && mutable.value.categoryError == null) return
        read(if (mutable.value.page == 0) 1 else mutable.value.page + 1)
    }
    fun retryCategories() {
        if (!editable() || mutable.value.loading) return
        read(if (mutable.value.page == 0) 1 else mutable.value.page + 1)
    }
    private fun read(page: Int) {
        val version = ++generation
        val type = mutable.value.type
        mutable.value = mutable.value.copy(loading = true, categoryError = null)
        request = viewModelScope.launch {
            val result = try { repository.list(CategoryQuery(type = type, page = page, pageSize = 50)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }
            ensureActive()
            if (version != generation) return@launch
            if (!isSessionCurrent()) { invalidate(); return@launch }
            when (result) {
                is FinanceResult.Success -> {
                    val response = result.value
                    if (response.page != page || response.pageSize != 50 || response.items.any { it.type != type || !it.isActive }) {
                        mutable.value = mutable.value.copy(loading = false, categoryError = FinanceFailure.InvalidResponse)
                    } else {
                        val items = (mutable.value.categories + response.items).distinctBy { it.categoryId }
                        mutable.value = mutable.value.copy(categories = Collections.unmodifiableList(items), page = page,
                            total = if (response.items.isEmpty()) items.size.toLong() else response.totalCount, loading = false)
                    }
                }
                is FinanceResult.Failure -> {
                    mutable.value = mutable.value.copy(loading = false, categoryError = result.reason)
                    if (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired) {
                        invalidate(); onSessionExpired()
                    }
                }
            }
        }
    }
    /**
     * Authenticated: sends only through an injected creator (account API, or the test harness);
     * without one, saving stays unavailable. Mock Demo: local preview only, never a network write.
     */
    fun submit() {
        if (!editable()) return
        mutable.value = mutable.value.copy(touched = FormField.entries.toSet())
        if (workflow == FormWorkflow.Authenticated) {
            mappedDraft()?.let { draft ->
                if (reviewRateBeforeSave && mutable.value.foreignCurrency) requestRate(draft) else submitWithCreator(draft)
            }
            return
        }
        if (!mutable.value.valid) return
        mutable.value = mutable.value.copy(submitting = true)
        preview = viewModelScope.launch {
            delay(300)
            if (!isSessionCurrent()) { invalidate(); return@launch }
            mutable.value = mutable.value.copy(submitting = false, completed = true)
        }
    }
    private fun requestRate(draft: TransactionDraft) {
        if (mutable.value.rateLoading || (reviewedDraft == draft && mutable.value.rateReview != null)) return
        val version = ++quoteGeneration
        mutable.value = mutable.value.copy(rateLoading = true, rateError = false, rateFailure = null, rateReview = null)
        quoteJob = viewModelScope.launch {
            val result = try { historicalQuotes?.quote(draft.currencyCode, checkNotNull(base), draft.amount, draft.transactionDate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            ensureActive()
            if (version != quoteGeneration || !isSessionCurrent() || mutable.value.invalidated) return@launch
            val quote = (result as? FinanceResult.Success)?.value
            val valid = quote != null && quote.from == draft.currencyCode && quote.to == base &&
                quote.amount.compareTo(draft.amount) == 0 && quote.requestedDate == draft.transactionDate &&
                quote.rateDate <= draft.transactionDate && quote.rate.signum() > 0 &&
                quote.convertedAmount.compareTo(draft.amount.multiply(quote.rate).setScale(2, java.math.RoundingMode.HALF_UP)) == 0
            if (valid && mappedDraft() == draft) {
                reviewedDraft = draft; reviewedAt = System.nanoTime()
                mutable.value = mutable.value.copy(rateLoading = false, rateReview = quote)
            } else mutable.value = mutable.value.copy(rateLoading = false, rateError = true, rateFailure = (result as? FinanceResult.Failure)?.reason ?: FinanceFailure.InvalidResponse)
        }
    }
    fun cancelRateReview() {
        quoteGeneration++; quoteJob?.cancel(); reviewedDraft = null
        mutable.value = mutable.value.copy(rateLoading = false, rateReview = null, rateError = false, rateFailure = null)
    }
    fun confirmRateReview() {
        val draft = reviewedDraft ?: return
        if (!editable() || mappedDraft() != draft || mutable.value.rateReview == null) { cancelRateReview(); return }
        if (System.nanoTime() - reviewedAt > 120_000_000_000L) { cancelRateReview(); requestRate(draft); return }
        cancelRateReview()
        submitWithCreator(draft)
    }
    fun back() {
        if (mutable.value.submitting) return
        val succeeded = mutable.value.completed || mutable.value.saveState is FormSaveState.Succeeded || mutable.value.saveState == FormSaveState.QueuedOffline
        mutable.value = mutable.value.copy(discardConfirmation = mutable.value.dirty && !succeeded,
            closeRequested = !mutable.value.dirty || succeeded)
    }
    fun keepEditing() { mutable.value = mutable.value.copy(discardConfirmation = false) }
    fun discard() { invalidate() }
    fun invalidate() {
        cancelRateReview()
        creation?.invalidate(); creation = null; lastSubmitted = null
        generation++; request?.cancel(); preview?.cancel()
        mutable.value = AddTransactionUiState(date = today, initialDate = today, invalidated = true, closeRequested = true)
    }
    override fun onCleared() { invalidate(); super.onCleared() }
}
