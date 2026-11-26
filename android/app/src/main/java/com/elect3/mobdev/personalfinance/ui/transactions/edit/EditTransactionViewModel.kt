package com.elect3.mobdev.personalfinance.ui.transactions.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.currency.RateImpact
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrency
import com.elect3.mobdev.personalfinance.domain.currency.rateImpact
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.ui.transactions.add.validAmount
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bounded, user-facing reasons an edit was not confirmed. */
enum class EditFailure { Offline, Rejected, Conflict, NotFound, Unknown }
/** Bounded reasons a delete was not confirmed; the transaction is kept in every case. */
enum class DeleteFailure { BillPayment, Offline, Unknown }
enum class DeleteOutcome { Deleted, AlreadyDeleted }

data class EditTransactionUiState(
    val original: Transaction,
    val baseCurrency: String?,
    val amount: String,
    val currency: String,
    val date: LocalDate,
    val description: String,
    val categoryId: Int,
    val categoryName: String,
    val categories: List<Category> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoriesFailed: Boolean = false,
    val saving: Boolean = false,
    /** The server-confirmed row (authoritative rate, rate date and base amount). */
    val saved: Transaction? = null,
    val failure: EditFailure? = null,
    val invalidated: Boolean = false,
    /** The server answered 409: someone changed this transaction since it was read. Saving needs a reload first. */
    val stale: Boolean = false,
    val reloading: Boolean = false,
    val deleting: Boolean = false,
    val deleteFailure: DeleteFailure? = null,
    val deleted: DeleteOutcome? = null,
) {
    /** No usable version (stale, or a copy without one): the edit cannot be sent until the row is reloaded. */
    val needsReload get() = !locked && (stale || original.version == null)
    val busy get() = saving || reloading || deleting
    val canReload get() = needsReload && !busy && saved == null && deleted == null && !invalidated
    val canDelete get() = !locked && !busy && saved == null && deleted == null && !invalidated
    /** Bill-payment transactions cannot be edited (the API answers 409). */
    val locked get() = original.billId != null || original.billDueDate != null
    val amountValid get() = validAmount(amount)
    val parsedAmount: BigDecimal? get() = if (amountValid) BigDecimal(amount.replace(',', '.')) else null
    val normalizedDescription get() = description.trim().takeIf { it.isNotEmpty() }
    val descriptionValid get() = description.length <= 200
    val dateValid get() = date.year in 1..9999
    val currencyOptions: List<SupportedCurrency> get() = SupportedCurrencies.options(baseCurrency).let { offered ->
        // Always keep the transaction's stored currency selectable, even if it is outside the offered set.
        val stored = original.currencyCode
        if (offered.any { it.code == stored }) offered else offered + SupportedCurrency(stored, stored, stored)
    }
    val changed get() = parsedAmount?.compareTo(original.amount) != 0 || currency != original.currencyCode || date != original.transactionDate ||
        normalizedDescription != original.description || categoryId != original.categoryId
    val impact: RateImpact get() = rateImpact(original.currencyCode, original.transactionDate, original.amount, original.fxRate, original.fxRateDate,
        currency, date, parsedAmount, baseCurrency)
    val valid get() = !locked && amountValid && descriptionValid && dateValid && currencyOptions.any { it.code == currency } && !invalidated
    val canSave get() = valid && changed && !busy && saved == null && deleted == null && !needsReload
    val editable get() = !locked && !busy && saved == null && deleted == null && !invalidated
}

/**
 * Online-only edit of one server transaction. The draft keeps the transaction's type; the API decides the rate:
 * amount-only edits keep the stored rate, currency/date changes need a validated rate. Nothing is queued offline.
 */
class EditTransactionViewModel(
    original: Transaction,
    baseCurrency: String?,
    private val updates: TransactionUpdateRepository,
    private val categories: CategoryRepository?,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(form(original, EditTransactionUiState(original, baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) },
        "", "", original.transactionDate, "", 0, "")))
    val state = mutable.asStateFlow()
    private var save: Job? = null

    /** The form showing [t] exactly as the server has it. */
    private fun form(t: Transaction, s: EditTransactionUiState) = s.copy(original = t, amount = t.amount.setScale(2).toPlainString(),
        currency = t.currencyCode, date = t.transactionDate, description = t.description.orEmpty(), categoryId = t.categoryId,
        categoryName = t.categoryName, stale = false)

    init { loadCategories() }

    private fun edit(block: (EditTransactionUiState) -> EditTransactionUiState) {
        if (!isSessionCurrent()) { invalidate(); return }
        if (mutable.value.editable) mutable.value = block(mutable.value).copy(failure = null)
    }
    fun amount(value: String) = edit { it.copy(amount = value) }
    fun description(value: String) = edit { it.copy(description = value) }
    fun date(value: LocalDate) = edit { it.copy(date = value) }
    fun currency(code: String) = edit { s -> if (s.currencyOptions.any { it.code == code }) s.copy(currency = code) else s }
    fun category(id: Int) = edit { s ->
        val match = s.categories.firstOrNull { it.categoryId == id && it.type == s.original.type && it.isActive }
        if (match != null) s.copy(categoryId = match.categoryId, categoryName = match.name) else s
    }

    fun loadCategories() {
        val repository = categories ?: return
        if (mutable.value.categoriesLoading || mutable.value.locked) return
        mutable.value = mutable.value.copy(categoriesLoading = true, categoriesFailed = false)
        viewModelScope.launch {
            val type = mutable.value.original.type
            val result = try { repository.list(CategoryQuery(type = type, page = 1, pageSize = 50)) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { FinanceResult.Failure(FinanceFailure.Unavailable) }
            ensureActive()
            if (!isSessionCurrent()) { invalidate(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> mutable.value.copy(categoriesLoading = false,
                    categories = result.value.items.filter { it.type == type && it.isActive })
                is FinanceResult.Failure -> {
                    if (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired) { expire(); return@launch }
                    mutable.value.copy(categoriesLoading = false, categoriesFailed = true)
                }
            }
        }
    }

    fun submit() {
        val s = mutable.value
        if (!s.canSave || !isSessionCurrent()) { if (!isSessionCurrent()) invalidate(); return }
        val version = s.original.version ?: return
        val draft = try {
            TransactionDraft(s.original.type, s.categoryId, checkNotNull(s.parsedAmount), s.currency, s.date, s.normalizedDescription)
        } catch (_: IllegalArgumentException) { mutable.value = s.copy(failure = EditFailure.Rejected); return }
        mutable.value = s.copy(saving = true, failure = null)
        save = viewModelScope.launch {
            val result = try { updates.update(s.original.transactionId, draft, version) }
                catch (cancelled: CancellationException) { mutable.value = mutable.value.copy(saving = false, failure = EditFailure.Unknown); throw cancelled }
                catch (_: Exception) { FinanceResult.Failure(FinanceFailure.InvalidResponse) }
            if (!isSessionCurrent()) { invalidate(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> mutable.value.copy(saving = false, saved = result.value)
                is FinanceResult.Failure -> {
                    if (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired) { expire(); return@launch }
                    // Never overwrite the other change: a 409 keeps the user's values but blocks saving until a reload.
                    mutable.value.copy(saving = false, failure = failureOf(result.reason), stale = result.reason == FinanceFailure.Conflict)
                }
            }
        }
    }

    /** Re-reads the row (and its current version) from the server and shows it, replacing unsaved values. */
    fun reload() {
        val s = mutable.value
        if (!isSessionCurrent()) { invalidate(); return }
        if (!s.canReload) return
        mutable.value = s.copy(reloading = true, failure = null, deleteFailure = null)
        save = viewModelScope.launch {
            val result = guarded({ it.copy(reloading = false, failure = EditFailure.Unknown) }) { updates.get(s.original.transactionId) }
            if (!isSessionCurrent()) { invalidate(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> form(result.value, mutable.value).copy(reloading = false)
                is FinanceResult.Failure -> {
                    if (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired) { expire(); return@launch }
                    mutable.value.copy(reloading = false, failure = failureOf(result.reason))
                }
            }
        }
    }

    /** Deletes the server transaction (after the screen's confirmation). A 404 means it is already gone. */
    fun delete() {
        val s = mutable.value
        if (!isSessionCurrent()) { invalidate(); return }
        if (!s.canDelete) return
        mutable.value = s.copy(deleting = true, failure = null, deleteFailure = null)
        save = viewModelScope.launch {
            val result = guarded({ it.copy(deleting = false, deleteFailure = DeleteFailure.Unknown) }) { updates.delete(s.original) }
            if (!isSessionCurrent()) { invalidate(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> mutable.value.copy(deleting = false, deleted = DeleteOutcome.Deleted)
                is FinanceResult.Failure -> when (result.reason) {
                    FinanceFailure.MissingSession, FinanceFailure.SessionExpired -> { expire(); return@launch }
                    FinanceFailure.NotFound -> mutable.value.copy(deleting = false, deleted = DeleteOutcome.AlreadyDeleted)
                    FinanceFailure.Conflict -> mutable.value.copy(deleting = false, deleteFailure = DeleteFailure.BillPayment)
                    FinanceFailure.Unavailable, FinanceFailure.SecureConnection -> mutable.value.copy(deleting = false, deleteFailure = DeleteFailure.Offline)
                    // A timeout or unreadable answer may still have deleted it: say so rather than "nothing happened".
                    else -> mutable.value.copy(deleting = false, deleteFailure = DeleteFailure.Unknown)
                }
            }
        }
    }

    private suspend fun <T> guarded(onCancel: (EditTransactionUiState) -> EditTransactionUiState, call: suspend () -> FinanceResult<T>): FinanceResult<T> =
        try { call() }
        catch (cancelled: CancellationException) { mutable.value = onCancel(mutable.value); throw cancelled }
        catch (_: Exception) { FinanceResult.Failure(FinanceFailure.InvalidResponse) }

    private fun expire() { invalidate(); onSessionExpired() }
    fun invalidate() { save?.cancel(); mutable.value = mutable.value.copy(saving = false, reloading = false, deleting = false, invalidated = true) }
    override fun onCleared() { save?.cancel(); super.onCleared() }

    companion object {
        /** A PUT may have arrived before a timeout or broken connection, so those are never reported as "nothing changed". */
        fun failureOf(reason: FinanceFailure): EditFailure = when (reason) {
            FinanceFailure.InvalidInput, FinanceFailure.AccessDenied -> EditFailure.Rejected
            FinanceFailure.Conflict -> EditFailure.Conflict
            FinanceFailure.NotFound -> EditFailure.NotFound
            FinanceFailure.Unavailable, FinanceFailure.SecureConnection -> EditFailure.Offline
            else -> EditFailure.Unknown
        }
    }
}
