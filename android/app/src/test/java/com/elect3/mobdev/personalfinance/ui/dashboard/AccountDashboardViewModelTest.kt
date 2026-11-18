package com.elect3.mobdev.personalfinance.ui.dashboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elect3.mobdev.personalfinance.data.mock.TransactionFixtures
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.domain.model.Transaction
import com.elect3.mobdev.personalfinance.domain.model.TransactionPage
import com.elect3.mobdev.personalfinance.domain.repository.TransactionFailure
import com.elect3.mobdev.personalfinance.testutil.ControlledTransactionRepository
import com.elect3.mobdev.personalfinance.testutil.MainDispatcherRule
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** M2: account Dashboard session safety and currency honesty. Fakes only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDashboardViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    private val repo = ControlledTransactionRepository()
    private val row = TransactionFixtures.items.first()
    private var current = true
    private var expired = 0

    private fun model(): DashboardViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, DashboardViewModel.Factory(LoadDashboardSummary(repo, accountCurrency = { "PHP" }),
            YearMonth.of(2026, 11), isSessionCurrent = { current }, onSessionExpired = { expired++ }))
            .get(DashboardViewModel::class.java)
    }

    private fun verify(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try { block() } finally {
            stores.forEach { it.clear() }
            repo.requests.filterNot { it.isCompleted }.forEach { it.fail() }
            runCurrent()
        }
    }

    private fun ControlledTransactionRepository.Request.complete(items: List<Transaction> = listOf(row)) =
        succeed(TransactionPage(items, page, pageSize, items.size.toLong()))

    @Test fun serverSessionExpiryNotifiesTheOwnerOnceAndBlocksFurtherReads() = verify {
        val vm = model(); runCurrent()
        repo.requests.single().fail(TransactionFailure.SessionExpired); runCurrent()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value); assertEquals(1, expired)
        vm.retry(); vm.refresh(); runCurrent()
        assertEquals(1, repo.requests.size); assertEquals(1, expired)
    }

    @Test fun responseArrivingAfterAnAccountSwitchIsNeverPublished() = verify {
        val vm = model(); runCurrent()
        current = false
        repo.requests.single().complete(); runCurrent()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value)
        assertEquals(0, expired) // The owner already changed sessions; no second sign-out.
    }

    @Test fun invalidateSessionHidesFiguresImmediatelyAndIgnoresTheLateRefresh() = verify {
        val vm = model(); runCurrent()
        repo.requests.single().complete(); runCurrent()
        assertTrue(vm.state.value is DashboardUiState.Content)
        vm.refresh(); runCurrent()
        vm.invalidateSession()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value)
        repo.requests.last().complete(); runCurrent()
        assertEquals(DashboardUiState.SessionExpired, vm.state.value)
        vm.refresh(); vm.retry(); runCurrent(); assertEquals(2, repo.requests.size)
    }

    @Test fun unsupportedCurrencyOnRefreshNeverKeepsTheEarlierSummaryVisible() = verify {
        val vm = model(); runCurrent()
        repo.requests.single().complete(); runCurrent()
        assertTrue(vm.state.value is DashboardUiState.Content)
        vm.refresh(); runCurrent()
        val converted = row.copy(transactionId = 999, currencyCode = "USD", fxRate = BigDecimal("56.00000000"),
            amountBase = row.amount.multiply(BigDecimal("56")))
        repo.requests.last().complete(listOf(row, converted)); runCurrent()
        assertEquals(DashboardUiState.InitialFailure(DashboardError.UnsupportedCurrency), vm.state.value)
    }

    @Test fun emptyAccountIsEmptyNotSampleContent() = verify {
        val vm = model(); runCurrent()
        repo.requests.single().complete(emptyList()); runCurrent()
        assertEquals(DashboardUiState.Empty, vm.state.value)
    }
}
