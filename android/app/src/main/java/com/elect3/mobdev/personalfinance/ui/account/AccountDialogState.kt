package com.elect3.mobdev.personalfinance.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elect3.mobdev.personalfinance.domain.currency.CurrencyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AccountSheet { Profile, Session, Appearance, Currency, Password, About, Logout }

/** Transient UI intent, never saved/restored as an account preference or navigation destination. */
internal class AccountSheetNavigation {
    var current by mutableStateOf<AccountSheet?>(null)
        private set
    fun open(sheet: AccountSheet) { current=sheet }
    fun dismiss() { current=null }
    fun leave(onLeave: () -> Unit) { dismiss(); onLeave() }
    fun back(onLeave: () -> Unit) { if(current!=null) dismiss() else leave(onLeave) }
}

internal sealed interface CurrencyPreferenceSaveStatus {
    data object Idle: CurrencyPreferenceSaveStatus
    data object Saving: CurrencyPreferenceSaveStatus
    data object Failed: CurrencyPreferenceSaveStatus
    data class Saved(val event: Long): CurrencyPreferenceSaveStatus
}

/** Feedback follows the actual atomic write result, not the optimistic picker selection. */
internal class CurrencyPreferenceSaveFeedback(
    private val write: suspend (CurrencyPreferences) -> Boolean,
    private val isCurrent: () -> Boolean,
    loadFailed: Boolean=false,
) {
    private val mutable=MutableStateFlow<CurrencyPreferenceSaveStatus>(if(loadFailed) CurrencyPreferenceSaveStatus.Failed else CurrencyPreferenceSaveStatus.Idle)
    val status=mutable.asStateFlow()
    private var sequence=0L
    suspend fun save(value: CurrencyPreferences) {
        if(!isCurrent() || mutable.value==CurrencyPreferenceSaveStatus.Saving) return
        mutable.value=CurrencyPreferenceSaveStatus.Saving
        val success=try { write(value) }
        catch(cancelled: CancellationException) { mutable.value=CurrencyPreferenceSaveStatus.Idle; throw cancelled }
        catch(_: Exception) { false }
        mutable.value=when {
            !isCurrent() -> CurrencyPreferenceSaveStatus.Idle
            success -> CurrencyPreferenceSaveStatus.Saved(++sequence)
            else -> CurrencyPreferenceSaveStatus.Failed
        }
    }
    fun clearSuccess(event: CurrencyPreferenceSaveStatus.Saved) {
        if(mutable.value==event) mutable.value=CurrencyPreferenceSaveStatus.Idle
    }
}
