package com.elect3.mobdev.personalfinance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.elect3.mobdev.personalfinance.data.mock.MockScenario
import com.elect3.mobdev.personalfinance.data.mock.MockTransactionRepository
import com.elect3.mobdev.personalfinance.domain.dashboard.LoadDashboardSummary
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardRoute
import com.elect3.mobdev.personalfinance.ui.dashboard.DashboardViewModel
import com.elect3.mobdev.personalfinance.ui.theme.PersonalFinanceTheme
import com.elect3.mobdev.personalfinance.ui.transactions.TransactionListViewModel
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Provider retains the ViewModel across configuration changes. Repositories are
        // created only when its factory is invoked, never during recomposition.
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                DashboardViewModel::class.java -> DashboardViewModel.Factory(
                    loader = LoadDashboardSummary(MockTransactionRepository(MockScenario.Normal)),
                    // Used only for an empty demo; nonempty data selects its latest month.
                    fallbackMonth = YearMonth.of(2026, 11),
                ).create(modelClass)
                // Retain Transactions construction for the future app-level destination owner.
                // This branch creates nothing until its ViewModel is explicitly requested.
                TransactionListViewModel::class.java -> TransactionListViewModel.Factory(
                    repository = MockTransactionRepository(MockScenario.Normal),
                    replacementFactory = { MockTransactionRepository(MockScenario.Normal) },
                ).create(modelClass)
                else -> throw IllegalArgumentException("Unsupported ViewModel class")
            }
        }
        val dashboardViewModel = ViewModelProvider(this, factory)
            .get(DashboardViewModel::class.java)
        setContent {
            PersonalFinanceTheme {
                // Temporary launch destination; replace with the future app-level host.
                DashboardRoute(viewModel = dashboardViewModel)
            }
        }
    }
}
