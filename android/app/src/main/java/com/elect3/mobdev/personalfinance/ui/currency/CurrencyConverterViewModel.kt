package com.elect3.mobdev.personalfinance.ui.currency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elect3.mobdev.personalfinance.domain.currency.ConfirmedRate
import com.elect3.mobdev.personalfinance.domain.currency.ConverterResult
import com.elect3.mobdev.personalfinance.domain.currency.CurrencyConverter
import com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuote
import com.elect3.mobdev.personalfinance.domain.currency.ExchangeRateQuoteRepository
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrency
import com.elect3.mobdev.personalfinance.domain.repository.FinanceFailure
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Live quote state. Only [Ready] carries numbers, and only the server's. */
sealed interface LiveQuote {
    data object Idle : LiveQuote
    data object Loading : LiveQuote
    data class Ready(val quote: ExchangeRateQuote) : LiveQuote
    /** The phone could not reach the OWLI server (no network, timeout, TLS). */
    data object Offline : LiveQuote
    /** The server answered but the rate provider had no acceptable rate (HTTP 5xx). */
    data object ProviderUnavailable : LiveQuote
    /** The server rejected this currency, amount or date (HTTP 400). */
    data object NotSupported : LiveQuote
    /** The server does not offer live quotes yet (endpoint not deployed). */
    data object NotDeployed : LiveQuote
    data object Failed : LiveQuote
}

data class CurrencyConverterUiState(
    /** The account currency (PHP in Mock Demo). Server conversions always target it. */
    val baseCurrency: String,
    val demo: Boolean,
    val from: String,
    val to: String,
    val amount: String = "",
    /** Rates the server already applied to this account's transactions (always empty in Mock Demo). */
    val confirmed: List<ConfirmedRate> = emptyList(),
    /** Signed in with a working Add Transaction entry: the converter can hand off to it. Never in Mock Demo. */
    val canRecordTransaction: Boolean = false,
    /** Signed in with the quote endpoint wired: live rates can be requested. Never in Mock Demo. */
    val liveQuotesAvailable: Boolean = false,
    val liveQuote: LiveQuote = LiveQuote.Idle,
) {
    val options: List<SupportedCurrency> get() = SupportedCurrencies.options(baseCurrency)
    val result: ConverterResult get() = CurrencyConverter.result(amount, from, to, confirmed)
    /** A live quote can be requested for the current input (valid amount, two different currencies, signed in). */
    val canRequestQuote: Boolean get() = liveQuotesAvailable && result is ConverterResult.QuoteUnavailable && liveQuote != LiveQuote.Loading
}

/**
 * Currency converter. Live numbers come only from the OWLI server's read-only quote endpoint (same provider and
 * policy as transaction saving); without it the converter shows the honest "no live quote" state. It never
 * writes data and never estimates a rate.
 */
class CurrencyConverterViewModel(
    baseCurrency: String?,
    demo: Boolean,
    confirmed: List<ConfirmedRate>,
    canRecordTransaction: Boolean,
    private val quotes: ExchangeRateQuoteRepository? = null,
    private val isSessionCurrent: () -> Boolean = { true },
    private val onSessionExpired: () -> Unit = {},
) : ViewModel() {
    private val base = if (demo) "PHP" else baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "PHP"
    private val mutable = MutableStateFlow(CurrencyConverterUiState(
        baseCurrency = base, demo = demo,
        from = SupportedCurrencies.options(base).first { it.code != base }.code, to = base,
        confirmed = if (demo) emptyList() else confirmed.filter { it.baseCurrency == base },
        canRecordTransaction = canRecordTransaction && !demo,
        liveQuotesAvailable = quotes != null && !demo))
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0L

    private fun offered(code: String) = mutable.value.options.any { it.code == code }
    /** Any input change invalidates a shown quote, so a number is never shown for different inputs. */
    private fun update(block: (CurrencyConverterUiState) -> CurrencyConverterUiState) {
        generation++; request?.cancel()
        mutable.value = block(mutable.value).copy(liveQuote = LiveQuote.Idle)
    }
    fun from(code: String) { if (offered(code) && code != mutable.value.from) update { it.copy(from = code) } }
    fun to(code: String) { if (offered(code) && code != mutable.value.to) update { it.copy(to = code) } }
    fun swap() = update { it.copy(from = it.to, to = it.from) }
    fun amount(raw: String) { if (raw.length <= 24 && raw != mutable.value.amount) update { it.copy(amount = raw) } }

    /** Live-only convenience: uses the same validated input path and clears any previous quote. */
    fun quickAmount(value: Int) {
        if (!mutable.value.demo && value in listOf(100, 500, 1000)) amount(value.toString())
    }

    /** Requests a live quote for the current input. Also used by Refresh. */
    fun requestQuote() {
        val s = mutable.value
        val repository = quotes ?: return
        if (!s.canRequestQuote) return
        if (!isSessionCurrent()) { onSessionExpired(); return }
        val amount = CurrencyConverter.parseAmount(s.amount) ?: return
        val version = ++generation
        request?.cancel()
        mutable.value = s.copy(liveQuote = LiveQuote.Loading)
        request = viewModelScope.launch {
            val result = try { repository.quote(s.from, s.to, amount) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { FinanceResult.Failure(FinanceFailure.InvalidResponse) }
            if (version != generation) return@launch // inputs changed meanwhile: drop the stale answer
            if (!isSessionCurrent()) { mutable.value = mutable.value.copy(liveQuote = LiveQuote.Idle); onSessionExpired(); return@launch }
            mutable.value = mutable.value.copy(liveQuote = when (result) {
                is FinanceResult.Success -> {
                    val q = result.value
                    if (q.from == s.from && q.to == s.to && q.amount.compareTo(amount) == 0) LiveQuote.Ready(q) else LiveQuote.Failed
                }
                is FinanceResult.Failure -> stateOf(result)
            })
            if (result is FinanceResult.Failure && (result.reason == FinanceFailure.MissingSession || result.reason == FinanceFailure.SessionExpired))
                onSessionExpired()
        }
    }
    fun refresh() = requestQuote()

    override fun onCleared() { request?.cancel(); super.onCleared() }

    companion object {
        fun stateOf(failure: FinanceResult.Failure): LiveQuote = when {
            failure.isConnectivityFailure -> LiveQuote.Offline
            failure.reason == FinanceFailure.Timeout || failure.reason == FinanceFailure.SecureConnection -> LiveQuote.Offline
            failure.reason == FinanceFailure.Unavailable || failure.reason == FinanceFailure.RateLimited -> LiveQuote.ProviderUnavailable
            failure.reason == FinanceFailure.InvalidInput -> LiveQuote.NotSupported
            failure.reason == FinanceFailure.NotFound || failure.reason == FinanceFailure.NotConfigured -> LiveQuote.NotDeployed
            else -> LiveQuote.Failed
        }
    }
}
