package com.elect3.mobdev.personalfinance.domain.currency

import com.elect3.mobdev.personalfinance.domain.model.Transaction
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CurrencyPreferences(
    val defaultCurrency: String = "PHP",
    val showOriginalAmounts: Boolean = true,
    val reviewRateBeforeSave: Boolean = false,
)
interface CurrencyPreferenceStorage {
    fun read(key: String): String?
    /** Atomic record replacement; false means no successful save can be reported. */
    fun write(key: String, value: String): Boolean
}
/** Device-only, per-user AND per-origin. Contains no credentials, financial amounts or base-currency mutation. */
class AccountCurrencyPreferences(storage: CurrencyPreferenceStorage, origin: String, accountId: Int) {
    private val backend = storage
    private val key: String
    val loadFailed: Boolean
    private val mutable: MutableStateFlow<CurrencyPreferences>
    init {
        val uri=URI(origin)
        require(accountId>0 && uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null)
        val namespace=MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        key="currency-v1:$namespace:$accountId"
        var failed=false
        val stored=try { backend.read(key) } catch (_: Exception) { failed=true; null }
        loadFailed=failed
        mutable=MutableStateFlow(decode(stored))
    }
    val preferences get()=mutable.asStateFlow()
    @Synchronized fun set(value: CurrencyPreferences): Boolean {
        if (!SupportedCurrencies.isSupported(value.defaultCurrency)) return false
        val encoded="1|${value.defaultCurrency}|${value.showOriginalAmounts}|${value.reviewRateBeforeSave}"
        val saved=try { backend.write(key,encoded) } catch (_: Exception) { false }
        if(saved) mutable.value=value
        return saved
    }
    private fun decode(raw: String?): CurrencyPreferences {
        val parts=raw?.split('|') ?: return CurrencyPreferences()
        if(parts.size!=4 || parts[0]!="1") return CurrencyPreferences()
        val code=parts[1].takeIf(SupportedCurrencies::isSupported) ?: "PHP"
        return CurrencyPreferences(code,parts[2].toBooleanStrictOrNull() ?: true,parts[3].toBooleanStrictOrNull() ?: false)
    }
}
data class HistoricalAmountDisplay(val primary: BigDecimal,val primaryCurrency: String,val secondary: BigDecimal?,val secondaryCurrency: String?)
/** Uses the immutable server snapshot only. Never multiplies by a current quote. */
fun historicalAmountDisplay(transaction: Transaction,showOriginal: Boolean,baseCurrency: String="PHP"): HistoricalAmountDisplay {
    require(baseCurrency.matches(Regex("[A-Z]{3}")))
    val foreign=transaction.currencyCode!=baseCurrency
    return if(foreign && showOriginal) HistoricalAmountDisplay(transaction.amount,transaction.currencyCode,transaction.amountBase,baseCurrency)
        else HistoricalAmountDisplay(transaction.amountBase,baseCurrency,null,null)
}
