package com.elect3.mobdev.personalfinance.ui.transactions.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.DemoSession
import com.elect3.mobdev.personalfinance.domain.model.TransactionType
import com.elect3.mobdev.personalfinance.domain.repository.CategoryQuery
import com.elect3.mobdev.personalfinance.domain.repository.FinanceResult
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Category-first flow: the picked category fixes the type and selection; inactive ones are refused. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionPreselectTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    @After fun clear() = store.clear()
    private fun model(session: DemoSession) = ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return AddTransactionViewModel(session.categories, FormWorkflow.MockDemo, "PHP") as T
        }
    })[AddTransactionViewModel::class.java]

    @Test fun preselectAppliesTypeAndCategoryAndSurvivesTheCategoryLoad() = runTest(main.dispatcher) {
        val session = DemoSession()
        val all = (session.categories.list(CategoryQuery(includeInactive = true, pageSize = 100)) as FinanceResult.Success).value.items
        val allowance = all.first { it.name == "Allowance" }
        val vm = model(session)
        vm.preselect(allowance); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(TransactionType.Income, s.type); assertEquals(allowance.categoryId, s.selectedCategoryId); assertTrue(s.categoryValid)
        assertFalse(s.hasUserInput)
        vm.amount("250.00"); assertTrue(vm.state.value.hasUserInput); assertTrue(vm.state.value.valid)
        vm.submit(); advanceUntilIdle()
        assertTrue(vm.state.value.completed)
    }

    @Test fun inactiveCategoryIsNotPreselected() = runTest(main.dispatcher) {
        val session = DemoSession()
        val vm = model(session)
        val inactive = (session.categories.list(CategoryQuery(pageSize = 100)) as FinanceResult.Success).value.items.first().copy(isActive = false)
        vm.preselect(inactive); advanceUntilIdle()
        assertNull(vm.state.value.selectedCategoryId)
    }
}
