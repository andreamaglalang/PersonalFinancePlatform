package com.elect3.mobdev.personalfinance.ui.currency

import androidx.lifecycle.ViewModel
import com.elect3.mobdev.personalfinance.domain.currency.ConfirmedRate
import com.elect3.mobdev.personalfinance.domain.currency.ConverterResult
import com.elect3.mobdev.personalfinance.domain.currency.CurrencyConverter
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrencies
import com.elect3.mobdev.personalfinance.domain.currency.SupportedCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) {
    val options: List<SupportedCurrency> get() = SupportedCurrencies.options(baseCurrency)
    val result: ConverterResult get() = CurrencyConverter.result(amount, from, to, confirmed)
}

/**
 * Read-only currency information and conversion helper. It never writes data, never calls a third-party rate
 * service and never estimates a rate: different currencies show the honest "no live quote" state.
 */
class CurrencyConverterViewModel(
    baseCurrency: String?,
    demo: Boolean,
    confirmed: List<ConfirmedRate>,
    canRecordTransaction: Boolean,
) : ViewModel() {
    private val base = if (demo) "PHP" else baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "PHP"
    private val mutable = MutableStateFlow(CurrencyConverterUiState(
        baseCurrency = base, demo = demo,
        from = SupportedCurrencies.options(base).first { it.code != base }.code, to = base,
        confirmed = if (demo) emptyList() else confirmed.filter { it.baseCurrency == base },
        canRecordTransaction = canRecordTransaction && !demo))
    val state = mutable.asStateFlow()

    private fun offered(code: String) = mutable.value.options.any { it.code == code }
    fun from(code: String) { if (offered(code)) mutable.value = mutable.value.copy(from = code) }
    fun to(code: String) { if (offered(code)) mutable.value = mutable.value.copy(to = code) }
    fun swap() { mutable.value = mutable.value.copy(from = mutable.value.to, to = mutable.value.from) }
    fun amount(raw: String) { if (raw.length <= 24) mutable.value = mutable.value.copy(amount = raw) }
}
