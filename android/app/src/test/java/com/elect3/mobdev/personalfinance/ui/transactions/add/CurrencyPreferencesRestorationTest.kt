package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.DemoCategoryRepository
import com.elect3.mobdev.personalfinance.domain.currency.*
import com.elect3.mobdev.personalfinance.domain.repository.*
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyPreferencesRestorationTest {
    @get:Rule val main=MainDispatcherRule()
    private class Disk: CurrencyPreferenceStorage {
        val records=mutableMapOf<String,String>()
        override fun read(key: String)=records[key]
        override fun write(key: String,value: String): Boolean {records[key]=value;return true}
    }
    @Test fun restoredUserPreferencesInitializeExpenseAndIncomeWithoutChangingBaseOrOtherUsers()=runTest(main.dispatcher) {
        val disk=Disk();val namespace="https://api.example.test/"
        val before=AccountCurrencyPreferences(disk,namespace,7)
        val selected=CurrencyPreferences("USD",showOriginalAmounts=false,reviewRateBeforeSave=true)
        assertTrue(before.set(selected))
        val restored=AccountCurrencyPreferences(disk,namespace,7)
        assertEquals(selected,restored.preferences.value)
        assertEquals(CurrencyPreferences(),AccountCurrencyPreferences(disk,namespace,8).preferences.value)
        val categories=DemoCategoryRepository()
        for(type in TransactionType.entries) {
            val category=(categories.list(CategoryQuery(type=type,pageSize=50)) as FinanceResult.Success).value.items.first()
            val vm=AddTransactionViewModel(categories,FormWorkflow.Authenticated,"PHP",defaultCurrency=restored.preferences.value.defaultCurrency)
            val owner=ViewModelStore().apply{put("form",vm)}
            try {
                runCurrent();vm.preselect(category);runCurrent()
                assertEquals(type,vm.state.value.type);assertEquals("USD",vm.state.value.currency)
                assertEquals("$",SupportedCurrencies.symbol(checkNotNull(vm.state.value.currency)))
                assertEquals("PHP",vm.state.value.baseCurrency)
                vm.currency("EUR");assertEquals("EUR",vm.state.value.currency)
                assertEquals("USD",restored.preferences.value.defaultCurrency)
            } finally {owner.clear()}
        }
        assertEquals(selected,AccountCurrencyPreferences(disk,namespace,7).preferences.value)
    }
    @Test fun anExistingDraftKeepsItsOverrideButTheNextFormUsesTheNewDefault()=runTest(main.dispatcher) {
        val disk=Disk();val prefs=AccountCurrencyPreferences(disk,"https://api.example.test/",7)
        val old=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",defaultCurrency=prefs.preferences.value.defaultCurrency)
        val owner=ViewModelStore().apply{put("old",old)}
        try {
            old.currency("JPY");assertTrue(prefs.set(CurrencyPreferences("USD")))
            assertEquals("JPY",old.state.value.currency)
            val next=AddTransactionViewModel(DemoCategoryRepository(),FormWorkflow.Authenticated,"PHP",defaultCurrency=prefs.preferences.value.defaultCurrency)
            owner.put("next",next);assertEquals("USD",next.state.value.currency);assertEquals("PHP",next.state.value.baseCurrency)
        } finally {owner.clear()}
    }
}
