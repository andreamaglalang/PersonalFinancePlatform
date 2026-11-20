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
) {
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
    val canSave get() = valid && changed && !saving && saved == null
    val editable get() = !locked && !saving && saved == null && !invalidated
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
    private val mutable = MutableStateFlow(EditTransactionUiState(original, baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) },
        original.amount.setScale(2).toPlainString(), original.currencyCode, original.transactionDate, original.description.orEmpty(),
        original.categoryId, original.categoryName))
    val state = mutable.asStateFlow()
    private var save: Job? = null

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
        val draft = try {
            TransactionDraft(s.original.type, s.categoryId, checkNotNull(s.parsedAmount), s.currency, s.date, s.normalizedDescription)
        } catch (_: IllegalArgumentException) { mutable.value = s.copy(failure = EditFailure.Rejected); return }
        mutable.value = s.copy(saving = true, failure = null)
        save = viewModelScope.launch {
            val result = try { updates.update(s.original.transactionId, draft) }
                catch (cancelled: CancellationException) { mutable.value = mutable.value.copy(saving = false, failure = EditFailure.Unknown); throw cancelled }
                catch (_: Exception) { FinanceResult.Failure(FinanceFailure.InvalidResponse) }
            if (!isSessionCurrent()) { invalidate(); return@launch }
            mutable.value = when (result) {
                is FinanceResult.Success -> mutable.value.copy(saving = false, saved = result.value)
                is FinanceResult.Failure -> {
                    if (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired) { expire(); return@launch }
                    mutable.value.copy(saving = false, failure = failureOf(result.reason))
                }
            }
        }
    }

    private fun expire() { invalidate(); onSessionExpired() }
    fun invalidate() { save?.cancel(); mutable.value = mutable.value.copy(saving = false, invalidated = true) }
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
